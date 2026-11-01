package com.openrs.dash

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.LapSessionEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The production database's foreign keys on Robolectric's SQLite (#236): the recorder tests rely on them, so check
 * they are really enforced rather than assumed — points cascade with their drive, a point for a missing drive is
 * refused, and `lap_sessions` (no foreign key, by schema) accepts an orphan — which is why `deleteOrphanLapSessions`
 * exists and why the recorder must not write laps for a drive deleted during STOP.
 */
@RunWith(RobolectricTestRunner::class)
class DriveDbForeignKeyTest {

    private lateinit var db: DriveDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), DriveDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    private fun scalar(sql: String): Long = db.openHelper.writableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun `foreign keys are on`() {
        assertEquals(1L, scalar("PRAGMA foreign_keys"))
    }

    @Test
    fun `a point for a missing drive is refused`() {
        assertThrows(SQLiteConstraintException::class.java) {
            db.driveDao().insertPoints(listOf(DrivePointEntity(driveId = 999, timestamp = 1, lat = 1.0, lng = 1.0)))
        }
    }

    @Test
    fun `deleting a drive removes its points`() {
        val dao = db.driveDao()
        val id = dao.insertDrive(DriveEntity(startTime = 1_000))
        dao.insertPoints(listOf(DrivePointEntity(driveId = id, timestamp = 1, lat = 1.0, lng = 1.0)))
        assertEquals(1, dao.getPointCount(id))
        dao.deleteDrive(id)
        assertEquals(0, dao.getPointCount(id))
    }

    @Test
    fun `lap sessions have no foreign key - an orphan is accepted until deleteOrphanLapSessions`() {
        val dao = db.driveDao()
        dao.insertLapSession(LapSessionEntity(driveId = 999, startFinishLat = 1.0, startFinishLng = 1.0, startFinishBearing = 0f, createdAt = 1))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM lap_sessions WHERE driveId NOT IN (SELECT id FROM drives)"))
        dao.deleteOrphanLapSessions()
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lap_sessions"))
    }
}
