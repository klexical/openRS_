package com.openrs.dash

import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.LinkStopLog
import com.openrs.dash.service.LinkStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/**
 * #212: each stop path of the real CanDataService writes its own reason. Hosted by Robolectric with the real app class. Only
 * stops with no connection job run here (no adapter, no transport), so these read LINK_STOP_IDLE; the job-running type and `rec`
 * sampled before an auto-pause are not covered, and need an emulator or phone export.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class LinkStopServiceTest {
    private val controller = TestServices.controller()
    private val service get() = controller.get()
    private fun lines(type: String) = DiagnosticLogger.sessionEvents.filter { it.type == type }.map { it.message }
    private fun idleReasons() = lines(DiagnosticLogger.EVENT_LINK_STOP_IDLE).map { it.substringAfter("reason=").substringBefore(' ') }
    private fun field(name: String) = CanDataService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun connectionMethod(m: String) {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("openrs_settings", Context.MODE_PRIVATE)
            .edit().putString("connection_method", m).commit()
    }

    @Before fun fresh() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        controller.create()
    }

    @org.junit.After fun destroyServices() { TestServices.destroyAll() }

    @Test fun `disconnect writes its reason, phase, generation and no recording`() {
        service.stopConnection(LinkStopReason.USER_DISCONNECT)
        val line = lines(DiagnosticLogger.EVENT_LINK_STOP_IDLE).single()
        assertTrue(line, Regex("reason=USER_DISCONNECT phase=OFF gen=\\d+ rec=off demo=no").matches(line))
    }

    @Test fun `Wi-Fi lost writes WIFI_LOST`() {
        connectionMethod("WIFI")
        val cb = field("wifiCallback") as ConnectivityManager.NetworkCallback
        cb.onLost(ShadowNetwork.newInstance(7))
        assertEquals(listOf("WIFI_LOST"), idleReasons())
    }

    @Test fun `Bluetooth off and turning off each write their own reason`() {
        connectionMethod("BLUETOOTH")
        val rx = field("bluetoothReceiver") as BroadcastReceiver
        for (state in listOf(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF)) {
            rx.onReceive(service, Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state))
        }
        assertEquals(listOf("BLUETOOTH_TURNING_OFF", "BLUETOOTH_OFF"), idleReasons())
    }

    @Test fun `destroying the service writes SERVICE_DESTROYED, and the earlier real reason is the one carried`() {
        service.stopConnection(LinkStopReason.WIFI_LOST)
        DiagnosticLogger.event(DiagnosticLogger.EVENT_LINK_STOP, "reason=WIFI_LOST phase=CONNECTED gen=3 rec=off demo=no")
        controller.destroy()
        assertEquals("SERVICE_DESTROYED", idleReasons().last())
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        val prev = lines(DiagnosticLogger.EVENT_LINK_PREV)
        assertEquals(prev.toString(), 1, prev.size)
        assertTrue(prev.single(), prev.single().contains("reason=WIFI_LOST"))
    }

    @Test fun `a task removal writes a line and changes nothing`() {
        val phase = OpenRSDashApp.instance.linkPhase.value
        service.onTaskRemoved(null)
        val line = lines(DiagnosticLogger.EVENT_LINK_TASK_REMOVED).single()
        assertTrue(line, Regex("phase=\\w+ job=no rec=off demo=no").matches(line))
        assertEquals(phase, OpenRSDashApp.instance.linkPhase.value)
        assertEquals("no stop line: a removal takes no ownership action", emptyList<String>(), idleReasons())
    }

    @Test fun `exit line - fields only, reason by name or raw number, description sanitized`() {
        val line = LinkStopLog.exitLine(10, 0, 400, 65_000L, "Stopped by AA:BB:CC:DD:EE:FF")
        assertTrue(line, line.startsWith("reason=USER_REQUESTED status=0 importance=400 age=65s description="))
        assertTrue(line, "AA:BB:CC:DD:EE:FF" !in line)
        assertTrue(LinkStopLog.exitLine(99, 1, 2, 0L, null).startsWith("reason=99 "))
        assertTrue(LinkStopLog.exitLine(4, 1, 2, 0L, null).endsWith("description="))
    }

    @Test fun `the labels stay fixed whatever R8 does to the constants`() {
        for (r in LinkStopReason.values()) assertEquals(r.name, r.label)
    }
}
