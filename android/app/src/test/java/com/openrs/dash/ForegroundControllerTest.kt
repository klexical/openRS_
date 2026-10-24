package com.openrs.dash

import com.openrs.dash.service.ForegroundController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ForegroundController — the start / foreground contract behind CanDataService. Recreating the activity
 * while disconnected used to raise "startForegroundService() did not then call startForeground()".
 */
class ForegroundControllerTest {

    private class FakeHost(var failStartForeground: Boolean = false, var failStartSelf: Boolean = false) :
        ForegroundController.Host {
        val calls = mutableListOf<String>()
        override fun startSelf() { if (failStartSelf) throw IllegalStateException("bg start"); calls += "startSelf" }
        override fun enterForeground() { if (failStartForeground) throw IllegalStateException("fgs"); calls += "enterForeground" }
        override fun exitForeground() { calls += "exitForeground" }
        override fun stopSelf() { calls += "stopSelf" }
    }

    @Test
    fun `a bound service with no connection is never started or foreground`() {
        val host = FakeHost()
        val fg = ForegroundController(host)
        fg.demote()   // stopConnection() with nothing running (offline, demo entry, onDestroy)
        assertTrue(host.calls.isEmpty())
        assertFalse(fg.isForeground)
    }

    @Test
    fun `every self-start is followed by startForeground`() {
        val host = FakeHost()
        val fg = ForegroundController(host)
        fg.promote()
        assertEquals(listOf("startSelf", "enterForeground"), host.calls)
        assertTrue(fg.isForeground)
    }

    @Test
    fun `reconnecting while foreground refreshes without a second self-start`() {
        val host = FakeHost()
        val fg = ForegroundController(host)
        fg.promote(); fg.promote()
        assertEquals(listOf("startSelf", "enterForeground", "enterForeground"), host.calls)
    }

    @Test
    fun `ending the connection leaves the foreground and the started state`() {
        val host = FakeHost()
        val fg = ForegroundController(host)
        fg.promote(); host.calls.clear()
        fg.demote()
        assertEquals(listOf("exitForeground", "stopSelf"), host.calls)
        assertFalse(fg.isForeground)
        host.calls.clear(); fg.demote()
        assertTrue("second stop is a no-op", host.calls.isEmpty())
    }

    @Test
    fun `a refused startForeground undoes the self-start`() {
        val host = FakeHost(failStartForeground = true)
        val fg = ForegroundController(host)
        try { fg.promote(); fail("expected the host's exception") } catch (_: IllegalStateException) {}
        assertEquals(listOf("startSelf", "stopSelf"), host.calls)
        assertFalse(fg.isForeground)
        host.failStartForeground = false; host.calls.clear()
        fg.promote()   // a later attempt starts cleanly
        assertEquals(listOf("startSelf", "enterForeground"), host.calls)
    }

    @Test
    fun `a refused background self-start leaves nothing to undo`() {
        val host = FakeHost(failStartSelf = true)
        val fg = ForegroundController(host)
        try { fg.promote(); fail("expected the host's exception") } catch (_: IllegalStateException) {}
        assertTrue(host.calls.isEmpty())
        fg.demote()
        assertTrue(host.calls.isEmpty())
    }
}
