package com.openrs.dash

import android.location.Location
import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * #512 — a process death mid-drive (an out-of-memory crash while moving) lost the points still in the recorder's
 * 30-point buffer, and the drive closed at the next launch got a summary from its stored points only: fuel used, the
 * thermal record, trims and pulls lived in memory and were lost. Now every recorded point is written as it is recorded,
 * and the loop keeps the summary STOP would write on the open row (every `DriveRecorder.CHECKPOINT_MS`), so the closed
 * drive keeps them.
 *
 * The death is simulated: from the moment of death every database call of the dead recorder fails (the process is gone,
 * nothing more reaches the disk, its finally blocks included), then its scope is cancelled. A new recorder on the same
 * database is the next launch. The real DriveRecorder on an in-memory Room database (RecorderHarness), the recorder's clock
 * set by the test.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderProcessDeathTest {

    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    @Volatile private var dead = false

    /** The recorder's DAO; every call fails once the process is [dead]. */
    private fun dyingDao(): DriveDao {
        val real = h.db.driveDao()
        return Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (dead) throw IllegalStateException("process gone")
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as DriveDao
    }

    private fun fix(i: Int) = Location("t").apply {
        latitude = 51.5 + i * 1e-4; longitude = -0.1; bearing = 0f; speed = 20f; time = System.currentTimeMillis()
    }

    /** A warm, moving car using fuel: oil and coolant stamped current, the tank falling 0.1 % a second from 50 %. */
    private fun state(i: Int, t: Long) = VehicleState(
        isConnected = true, rpm = 3_000.0, speedKph = 70.0, lastUpdate = t,
        oilTempC = 95.0 + i * 0.5, coolantTempC = 88.0 + i * 0.1, fuelLevelPct = 50.0 - i * 0.1,
        fieldLastUpdateMs = mapOf("oilTempC" to t, "coolantTempC" to t, "speedKph" to t, "fuelLevelPct" to t),
    )

    @Test fun `a process death mid-drive - every recorded point is stored and the closed drive keeps fuel and thermal data`() {
        val base = h.now
        h.vs.value = state(0, base)
        val rec = DriveRecorder(h.app, h.vs, WeatherRepository(""), h.db, dyingDao(), { _, _ -> null }, h.gps, { },
            clock = { h.now })
        assertEquals(CommandResult.Done, rec.startDrive())
        check(h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING && h.gps.opened.get() > 0 }) { "no start" }
        val id = rec.driveState.value.driveId

        val n = 45                                             // not a multiple of the old 30-point batch
        for (i in 0 until n) {
            h.now = base + i * 1_000L
            h.vs.value = state(i, h.now)
            val before = rec.driveState.value.totalPointCount
            h.gps.current!!.trySend(fix(i))
            check(h.waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
        h.settle()                                             // the last point's write lands (it is the one second at risk)
        val usedBeforeDeath = rec.driveState.value.fuelUsedL
        assertTrue("the drive used fuel before the death: $usedBeforeDeath", usedBeforeDeath > 0.0)

        dead = true                                            // the process dies: nothing more is written
        rec.cancel()

        // The next launch: a new recorder closes the open drive from what is on disk
        h.now = base + n * 1_000L + 5_000L
        h.recorder()
        check(h.waitUntil { (h.dao.getDrive(id)?.endTime ?: 0L) != 0L }) { "the drive was never closed" }
        val d = h.dao.getDrive(id)!!

        assertEquals("every recorded point is stored", n, h.dao.getPoints(id).size)
        assertTrue("fuel used kept (${d.fuelUsedL} L)", d.fuelUsedL > 0.0)
        assertNotNull("thermal record kept", d.thermalPeaksJson)
        assertTrue("thermal record has the oil peak: ${d.thermalPeaksJson}", d.thermalPeaksJson!!.contains("OIL", ignoreCase = true))
        assertEquals("ends at its last stored point", base + (n - 1) * 1_000L, d.endTime)
    }

    @Test fun `a death after one point - that point is stored and the drive is closed at it`() {
        val base = h.now
        h.vs.value = state(0, base)
        val rec = DriveRecorder(h.app, h.vs, WeatherRepository(""), h.db, dyingDao(), { _, _ -> null }, h.gps, { },
            clock = { h.now })
        assertEquals(CommandResult.Done, rec.startDrive())
        check(h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING && h.gps.opened.get() > 0 }) { "no start" }
        val id = rec.driveState.value.driveId
        h.now = base
        h.gps.current!!.trySend(fix(0))
        check(h.waitUntil { rec.driveState.value.totalPointCount == 1 }) { "point not recorded" }
        h.settle()
        dead = true
        rec.cancel()
        h.now = base + 5_000L
        h.recorder()
        check(h.waitUntil { (h.dao.getDrive(id)?.endTime ?: 0L) != 0L }) { "the drive was never closed" }
        assertEquals(1, h.dao.getPoints(id).size)
        assertEquals("no fuel figure from one point", 0.0, h.dao.getDrive(id)!!.fuelUsedL, 0.0)
    }
}
