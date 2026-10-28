package com.openrs.dash

import com.openrs.dash.can.LinkPhase
import com.openrs.dash.service.LinkOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Connection ownership (#212) with the interleavings forced: latches hold one side inside its critical section while
 * the other side tries to run, so the outcome doesn't depend on scheduling.
 */
class LinkOwnershipTest {

    /** A user cancel as CanDataService performs it: block auto-connect and end ownership, under the lock. */
    private fun LinkOwnership.cancel() = transition { requireManualConnect(); end(LinkPhase.OFF) }

    private fun thread(block: () -> Unit) = Thread(block).apply { isDaemon = true; start() }

    @Test fun `a callback that passed its check before a cancel can't start a connection that outlives the cancel`() {
        val own = LinkOwnership()
        val checked = CountDownLatch(1); val proceed = CountDownLatch(1)
        val cancelDone = CountDownLatch(1)
        var startedGen = -1
        val callback = thread {
            own.autoStart(blocked = { checked.countDown(); proceed.await(); false }) { startedGen = own.begin() }
        }
        assertTrue(checked.await(2, TimeUnit.SECONDS))                  // eligibility passed, start not yet run
        thread { own.cancel(); cancelDone.countDown() }
        assertFalse("cancel ran inside the callback's check-and-start", cancelDone.await(200, TimeUnit.MILLISECONDS))
        proceed.countDown()
        callback.join(2_000)
        assertTrue(cancelDone.await(2, TimeUnit.SECONDS))
        // The start happened, but the cancel came after it as a whole: that job no longer owns the link
        assertTrue(startedGen > 0)
        assertFalse(own.owns(startedGen))
        assertEquals(LinkPhase.OFF, own.phase)
        // …and no later callback starts again
        assertFalse(own.autoStart(blocked = { false }) { own.begin() })
    }

    @Test fun `a callback after a cancel is refused, and an explicit connect clears the block`() {
        val own = LinkOwnership()
        own.begin(); own.cancel()
        var starts = 0
        assertFalse(own.autoStart(blocked = { false }) { starts++ })
        own.transition { own.clearManualConnect(); own.begin() }
        assertTrue(own.autoStart(blocked = { false }) { starts++ })
        assertEquals(1, starts)
    }

    @Test fun `a stop can't land between a job's ownership check and its phase publication`() {
        val own = LinkOwnership()
        val gen = own.begin()
        val inPublish = CountDownLatch(1); val proceed = CountDownLatch(1)
        val stopDone = CountDownLatch(1)
        var published: LinkPhase? = null
        val job = thread {
            own.publish(gen, LinkPhase.CONNECTED) { inPublish.countDown(); proceed.await(); published = it }
        }
        assertTrue(inPublish.await(2, TimeUnit.SECONDS))
        thread { own.end(LinkPhase.OFF); stopDone.countDown() }
        assertFalse("stop ran inside the publication", stopDone.await(200, TimeUnit.MILLISECONDS))
        proceed.countDown()
        job.join(2_000)
        assertTrue(stopDone.await(2, TimeUnit.SECONDS))
        assertEquals(LinkPhase.CONNECTED, published)
        assertEquals("the stop is the last word", LinkPhase.OFF, own.phase)
        assertFalse(own.publish(gen, LinkPhase.RECONNECTING) { published = it })
        assertEquals(LinkPhase.OFF, own.phase)
    }

    @Test fun `a merge queued by an old job is dropped after a reconnect, the new job's merge applies`() {
        val own = LinkOwnership()
        val old = own.begin()
        val queued = { gen: Int, log: MutableList<String>, tag: String -> own.deliver(gen) { log += tag } }
        val applied = mutableListOf<String>()
        val new = own.transition { own.end(LinkPhase.OFF); own.begin() }   // reconnect: old ends, new begins
        assertFalse(queued(old, applied, "old merge"))                     // delivered late, after the reconnect
        assertTrue(queued(new, applied, "new merge"))
        assertEquals(listOf("new merge"), applied)
    }

    @Test fun `an old job's late phase never overwrites the newer session's phase`() {
        val own = LinkOwnership()
        val old = own.begin()
        val new = own.transition { own.end(LinkPhase.OFF); own.begin() }
        assertTrue(own.publish(new, LinkPhase.CONNECTED) {})
        assertFalse(own.publish(old, LinkPhase.RECONNECTING) {})
        assertEquals(LinkPhase.CONNECTED, own.phase)
        assertTrue(own.owns(new)); assertFalse(own.owns(old))
    }

    // ── Cancel policy: one rule for pill, DRIVE and notification, enforced where the request is handled ──

    private class Session(val own: LinkOwnership = LinkOwnership()) {
        var recording = false
        var stops = 0
        fun cancel() = own.cancel(recording = { recording }) { stops++; own.end(LinkPhase.OFF) }
    }

    @Test fun `cancel during an initial attempt with no recording stops it and blocks auto-connect`() {
        val s = Session(); val gen = s.own.begin()
        assertTrue(s.cancel())
        assertEquals(1, s.stops)
        assertFalse(s.own.owns(gen))
        assertTrue(s.own.manualConnectRequired)
    }

    @Test fun `a stale Cancel delivered after the link connected does nothing`() {
        val s = Session(); val gen = s.own.begin()
        s.own.publish(gen, LinkPhase.CONNECTED) {}
        assertFalse(s.cancel())
        assertEquals(0, s.stops)
        assertTrue(s.own.owns(gen))
        assertFalse(s.own.manualConnectRequired)
    }

    @Test fun `recording, link lost, RCN retry, CONN, Cancel — refused, the session keeps running`() {
        val s = Session(); s.recording = true
        val first = s.own.begin()
        s.own.publish(first, LinkPhase.CONNECTED) {}
        s.own.publish(first, LinkPhase.RECONNECTING) {}
        assertFalse("no Cancel while reconnecting", s.cancel())
        val retry = s.own.transition { s.own.end(LinkPhase.OFF); s.own.begin() }   // RCN tap = reconnect()
        assertEquals(LinkPhase.CONNECTING, s.own.phase)
        assertFalse("CONN while recording can't be cancelled", s.cancel())
        assertEquals(0, s.stops)
        assertTrue(s.own.owns(retry))
        assertFalse(s.own.manualConnectRequired)
    }

    @Test fun `a Cancel tapped before a recording began but handled after it is refused`() {
        val s = Session(); val gen = s.own.begin()
        s.recording = true                                              // manual START on TRIP while still connecting
        assertFalse(s.cancel())
        assertTrue(s.own.owns(gen))
    }

    @Test fun `a Cancel racing the CONNECTED publication is decided after it — refused`() {
        val s = Session(); val gen = s.own.begin()
        val inPublish = CountDownLatch(1); val proceed = CountDownLatch(1)
        var result: Boolean? = null
        val done = CountDownLatch(1)
        val job = thread { s.own.publish(gen, LinkPhase.CONNECTED) { inPublish.countDown(); proceed.await() } }
        assertTrue(inPublish.await(2, TimeUnit.SECONDS))
        thread { result = s.cancel(); done.countDown() }
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        proceed.countDown(); job.join(2_000)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(false, result)
        assertTrue(s.own.owns(gen))
    }
}
