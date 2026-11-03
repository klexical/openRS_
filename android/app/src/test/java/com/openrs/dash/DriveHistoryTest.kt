package com.openrs.dash

import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.ui.trip.DriveHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * TRIP's drive history (#228): the observed query is the list's only source; delete / tag / rename only write. The DAO
 * is a stand-in for Room's — an in-memory `drives` table whose observation re-emits after every write, like Room's
 * invalidation — and it fails the test on any other call, so an edit that re-reads the list itself (the removed
 * `getRecentDrives` reloads, which could land after a newer emission and put an older list back) is caught here.
 */
class DriveHistoryTest {

    private class RoomLikeDao(vararg initial: DriveEntity) {
        val table = MutableStateFlow(initial.toList())
        val calls = mutableListOf<String>()

        val dao: DriveDao = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            calls += m.name
            when (m.name) {
                "observeRecentDrives" -> { val limit = args[0] as Int; table.map { t -> t.sortedByDescending { it.startTime }.take(limit) } }
                "updateDrive" -> { val d = args[0] as DriveEntity; table.value = table.value.map { if (it.id == d.id) d else it } }
                "deleteDrive" -> { val id = args[0] as Long; table.value = table.value.filterNot { it.id == id } }
                "deleteOrphanLapSessions" -> Unit
                "updateDriveTags" -> { val id = args[0] as Long; table.value = table.value.map { if (it.id == id) it.copy(tags = args[1] as String) else it } }
                "updateDriveName" -> { val id = args[0] as Long; table.value = table.value.map { if (it.id == id) it.copy(name = args[1] as String?) else it } }
                else -> throw AssertionError("history must not call DriveDao.${m.name} (the list comes from the observed query only)")
            }
        } as DriveDao
    }

    private val older = DriveEntity(id = 1, startTime = 1_000, endTime = 2_000, name = "Morning")
    private val stopped = DriveEntity(id = 2, startTime = 5_000)            // STOP pressed, end not yet written (ACTIVE)
    private fun ended(d: DriveEntity) = d.copy(endTime = 9_000, name = "Bay View Drive", distanceKm = 4.2)

    @Test
    fun `STOP then the end write - the observed row completes without any reload`() = runTest(UnconfinedTestDispatcher()) {
        val db = RoomLikeDao(older, stopped)
        var shown = emptyList<DriveEntity>()
        val collector = launch { DriveHistory.observe(db.dao, 50).collect { shown = it } }
        assertEquals(0L, shown.first { it.id == 2L }.endTime)

        db.dao.updateDrive(ended(stopped))                                   // DriveRecorder's finalize, later on IO

        assertEquals(9_000L, shown.first { it.id == 2L }.endTime)
        assertEquals("Bay View Drive", shown.first { it.id == 2L }.name)
        collector.cancel()
    }

    @Test
    fun `STOP then tag, rename and delete - each edit only writes and the list follows`() = runTest(UnconfinedTestDispatcher()) {
        val db = RoomLikeDao(older, stopped)
        var shown = emptyList<DriveEntity>()
        val collector = launch { DriveHistory.observe(db.dao, 50).collect { shown = it } }
        db.dao.updateDrive(ended(stopped))

        DriveHistory.setTags(db.dao, 2, "track,wet")
        assertEquals("track,wet", shown.first { it.id == 2L }.tags)
        assertEquals(9_000L, shown.first { it.id == 2L }.endTime)            // the edit didn't bring back the ACTIVE row

        DriveHistory.rename(db.dao, 2, "Hill run")
        assertEquals("Hill run", shown.first { it.id == 2L }.name)
        DriveHistory.rename(db.dao, 1, null)
        assertNull(shown.first { it.id == 1L }.name)

        DriveHistory.delete(db.dao, 1)
        assertEquals(listOf(2L), shown.map { it.id })
        assertEquals(db.table.value, shown)

        assertFalse(db.calls.contains("getRecentDrives"))
        assertEquals(listOf("deleteDrive", "deleteOrphanLapSessions"), db.calls.dropWhile { it != "deleteDrive" })
        collector.cancel()
    }

    @Test
    fun `edits while the end is still unwritten - the end write lands last and the list ends complete`() = runTest(UnconfinedTestDispatcher()) {
        val db = RoomLikeDao(older, stopped)
        var shown = emptyList<DriveEntity>()
        val collector = launch { DriveHistory.observe(db.dao, 50).collect { shown = it } }

        // The history shows right after STOP when the link is down, so edits can come before the finalize's write
        DriveHistory.setTags(db.dao, 1, "commute")
        DriveHistory.delete(db.dao, 99)                                      // nothing to delete — still re-emits fine
        db.dao.updateDrive(ended(stopped))

        assertEquals(db.table.value.sortedByDescending { it.startTime }, shown)
        assertTrue(shown.first { it.id == 2L }.endTime > 0)
        assertEquals("commute", shown.first { it.id == 1L }.tags)
        collector.cancel()
    }

    @Test
    fun `the limit is the observed query's - newest first`() = runTest {
        val db = RoomLikeDao(older, stopped, DriveEntity(id = 3, startTime = 3_000, endTime = 4_000))
        assertEquals(listOf(2L, 3L), DriveHistory.observe(db.dao, 2).first().map { it.id })
    }
}
