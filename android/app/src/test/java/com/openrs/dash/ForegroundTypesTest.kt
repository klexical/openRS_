package com.openrs.dash

import com.openrs.dash.service.ForegroundController
import com.openrs.dash.service.ForegroundController.TypeChange
import com.openrs.dash.service.ForegroundTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #261: the foreground service carries the location type only while a drive records (not paused) with fine location granted,
 * and adds it only while the app is visible (Android 14+ while-in-use rule); a pause, a stop or the link end drops it. The
 * rule ([ForegroundTypes]) and its application ([ForegroundController.applyTypes]) on a recording host. JVM only.
 */
class ForegroundTypesTest {
    private class Host : ForegroundController.Host {
        val calls = mutableListOf<String>()
        var refuseLocation = false
        override fun startSelf() { calls += "startSelf" }
        override fun enterForeground(location: Boolean) {
            if (location && refuseLocation) throw SecurityException("while-in-use location not allowed")
            calls += if (location) "fg+location" else "fg"
        }
        override fun exitForeground() { calls += "exit" }
        override fun stopSelf() { calls += "stopSelf" }
    }

    @Test fun `location is wanted only while recording, not paused, with fine location granted`() {
        for (granted in listOf(true, false)) {
            assertEquals("recording, granted=$granted", granted, ForegroundTypes.wantsLocation(recording = true, paused = false, fineGranted = granted))
            assertFalse("paused, granted=$granted", ForegroundTypes.wantsLocation(recording = true, paused = true, fineGranted = granted))
            assertFalse("stopped, granted=$granted", ForegroundTypes.wantsLocation(recording = false, paused = false, fineGranted = granted))
        }
    }

    @Test fun `the type is added only while visible, kept once held, dropped when no longer wanted`() {
        assertEquals(ForegroundTypes.Next.ADD_LOCATION, ForegroundTypes.next(want = true, has = false, visible = true))
        assertEquals(ForegroundTypes.Next.DEFER_LOCATION, ForegroundTypes.next(want = true, has = false, visible = false))
        assertEquals("held while the app goes behind", ForegroundTypes.Next.KEEP, ForegroundTypes.next(want = true, has = true, visible = false))
        assertEquals(ForegroundTypes.Next.KEEP, ForegroundTypes.next(want = true, has = true, visible = true))
        for (visible in listOf(true, false)) {
            assertEquals(ForegroundTypes.Next.DROP_LOCATION, ForegroundTypes.next(want = false, has = true, visible = visible))
            assertEquals(ForegroundTypes.Next.KEEP, ForegroundTypes.next(want = false, has = false, visible = visible))
        }
    }

    @Test fun `recording while visible adds it, a pause drops it, a resume adds it again, the link end clears it`() {
        val host = Host(); val fg = ForegroundController(host)
        fg.promote()
        assertEquals(listOf("startSelf", "fg"), host.calls)            // a connection starts with connectedDevice only
        assertEquals(TypeChange.Added, fg.applyTypes(wantLocation = true, appVisible = true))
        assertTrue(fg.hasLocation)
        assertEquals("held while the app goes behind", TypeChange.None, fg.applyTypes(wantLocation = true, appVisible = false))
        assertEquals(TypeChange.Dropped, fg.applyTypes(wantLocation = false, appVisible = false))    // pause / stop
        assertFalse(fg.hasLocation)
        assertEquals(TypeChange.Added, fg.applyTypes(wantLocation = true, appVisible = true))      // resume in front
        fg.promote()                                                   // a reconnect refresh keeps the types it has
        assertEquals("fg+location", host.calls.last())
        fg.demote()                                                    // the link end (N2's path) takes the type with it
        assertFalse(fg.hasLocation)
        assertEquals(listOf("startSelf", "fg", "fg+location", "fg", "fg+location", "fg+location", "exit", "stopSelf"), host.calls)
    }

    @Test fun `recording started behind - connectedDevice only, reported once, added when the app is visible`() {
        val host = Host(); val fg = ForegroundController(host)
        fg.promote(); host.calls.clear()
        assertEquals(TypeChange.Deferred, fg.applyTypes(wantLocation = true, appVisible = false))
        assertEquals("reported once", TypeChange.None, fg.applyTypes(wantLocation = true, appVisible = false))
        assertTrue(fg.locationDeferred)
        assertTrue("nothing written while behind", host.calls.isEmpty())
        assertEquals(TypeChange.Added, fg.applyTypes(wantLocation = true, appVisible = true))
        assertFalse(fg.locationDeferred)
        assertEquals(listOf("fg+location"), host.calls)
    }

    @Test fun `a refused add keeps the foreground as it was and is reported once, apart from a deferral`() {
        val host = Host().apply { refuseLocation = true }; val fg = ForegroundController(host)
        fg.promote()
        assertEquals(TypeChange.Deferred, fg.applyTypes(wantLocation = true, appVisible = false))
        val c = fg.applyTypes(wantLocation = true, appVisible = true)
        assertTrue("$c", c is TypeChange.Refused && c.error is SecurityException)
        assertEquals("reported once", TypeChange.None, fg.applyTypes(wantLocation = true, appVisible = true))
        assertTrue(fg.isForeground); assertFalse(fg.hasLocation)
    }

    @Test fun `a reconnect refresh refused with location falls back to connectedDevice and stays foreground`() {
        val host = Host(); val fg = ForegroundController(host)
        fg.promote(); fg.applyTypes(wantLocation = true, appVisible = true)
        host.refuseLocation = true; host.calls.clear()
        fg.promote()                                                   // e.g. a reconnect from the background
        assertEquals(listOf("fg"), host.calls)
        assertTrue(fg.isForeground); assertFalse(fg.hasLocation)
        assertEquals("then reported by the caller's applyTypes", TypeChange.Deferred, fg.applyTypes(wantLocation = true, appVisible = false))
    }

    @Test fun `never promotes or demotes - not foreground, nothing happens (N2's demote order is unchanged)`() {
        val host = Host(); val fg = ForegroundController(host)
        assertEquals(TypeChange.None, fg.applyTypes(wantLocation = true, appVisible = true))
        fg.promote(); fg.demote(); host.calls.clear()
        assertEquals(TypeChange.None, fg.applyTypes(wantLocation = true, appVisible = true))
        assertEquals(TypeChange.None, fg.applyTypes(wantLocation = false, appVisible = true))
        assertTrue(host.calls.isEmpty()); assertFalse(fg.isForeground)
    }
}
