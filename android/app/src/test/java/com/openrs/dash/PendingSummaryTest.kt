package com.openrs.dash

import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.PendingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PendingSummary — the post-drive summary waits for TRIP and is shown once. */
class PendingSummaryTest {

    private fun drive(id: Long) = DriveEntity(id = id, startTime = 1_000L * id)

    @Test
    fun `a posted summary stays queued until consumed`() {
        val q = PendingSummary()
        q.post(drive(1))
        assertEquals(1L, q.drive.value?.id)   // still there however many times TRIP is (not) opened
        q.consume(1)
        assertNull(q.drive.value)
        q.consume(1)                          // closing twice is harmless
        assertNull(q.drive.value)
    }

    @Test
    fun `closing an older summary keeps a newer one queued`() {
        val q = PendingSummary()
        q.post(drive(1))
        q.post(drive(2))                      // second drive ended before the first summary was seen
        q.consume(1)
        assertEquals(2L, q.drive.value?.id)
        q.consume(2)
        assertNull(q.drive.value)
    }
}
