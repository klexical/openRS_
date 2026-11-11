package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Seam 1 and 2 of #316 for a Robolectric test (TEST-SEAMS.md): the real DriveRecorder on an in-memory database, a fake
 * location source, a clock the test sets, and optionally one DAO call held open. Tests read only what a caller can:
 * command results, driveState, and the drives in the database. Call [close] from an `@After`.
 */
class RecorderHarness {

    class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> = Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** Holds the [nth] call of [method] before the real call, until [release]. */
    class Hold(val method: String, val nth: Int = 1) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val used = AtomicInteger()
        fun awaitEntered() = check(entered.await(5, TimeUnit.SECONDS)) { "$method never reached" }
    }

    val app: Application = ApplicationProvider.getApplicationContext()
    val db: DriveDatabase
    val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 2_000.0, speedKph = 40.0))
    val gps = FakeGps()
    /** The recorder's clock (seam 2). */
    @Volatile var now = 1_000_000L
    private val recorders = mutableListOf<DriveRecorder>()
    val dao: DriveDao get() = db.driveDao()

    init {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    fun recorder(hold: Hold? = null, autoRecord: Boolean = false): DriveRecorder =
        DriveRecorder(app, vs, WeatherRepository(""), db, heldDao(hold), { _, _ -> null }, gps, { }, clock = { now })
            .also { recorders += it; it.onAutoRecordSwitched(autoRecord) }

    private fun heldDao(hold: Hold?): DriveDao {
        val real = db.driveDao()
        if (hold == null) return real
        return Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (m.name == hold.method && hold.used.incrementAndGet() == hold.nth) {
                hold.entered.countDown(); check(hold.release.await(10, TimeUnit.SECONDS)) { "hold never released" }
            }
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as DriveDao
    }

    fun close() { recorders.forEach { it.cancel() }; db.close() }

    fun waitUntil(ms: Long = 5_000, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (c()) return true; Thread.sleep(5) }
        return c()
    }

    fun join(job: kotlinx.coroutines.Job) = runBlocking { withTimeout(10_000) { job.join() } }

    /** START, then wait until the drive is recording. */
    fun DriveRecorder.recording(): DriveRecorder {
        assertEquals(CommandResult.Done, startDrive())
        check(waitUntil { driveState.value.phase == DrivePhase.RECORDING }) { "drive did not start" }
        return this
    }

    /** The recorder settles: nothing more happens on its own within a short time. */
    fun settle() = Thread.sleep(150)
}
