package com.openrs.dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveMode
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.LinkStopReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * #547: a new connection must not inherit the previous link's drive mode, as it does not inherit ESC (#288,
 * `EscConnectResetTest`). The previous link left Sport, with its 0x1B0 run and 0x420 detail stamps (#252). The real
 * service (TestServices) starts a connection with a Wi-Fi transport present; after startConnection() a session has really
 * started, and the drive mode is UNKNOWN ("--") with no drive-mode stamp left from the old link, until this connection's
 * own frames resolve it. Red before the fix: the header and a recording drive kept Sport until the first 0x1B0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class DriveModeConnectResetTest {
    @After fun destroyServices() { TestServices.destroyAll() }

    private val appVs get() = OpenRSDashApp.instance.vehicleState

    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** One frame decoded and stamped as the connection's frame path does it. */
    private fun receive(id: Int, data: ByteArray) {
        val before = appVs.value
        val decoded = CanDecoder.decode(id, data, before) ?: return
        appVs.value = CanDecoder.stampFresh(id, before, decoded, System.currentTimeMillis())
    }

    @Test fun `a new connection starts with the drive mode unknown, then resolves from its own frames`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1").commit()
        // Wi-Fi transport present (as EscConnectResetTest does), so beginConnection gets past isTransportReady()
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)

        // The previous link: a documented Sport detail (0x420 byte 6 0x11, byte 7 bit 0 = 0) twice, then two Sport 0x1B0s
        CanDecoder.resetSessionState()
        repeat(2) { receive(CanDecoder.ID_DRIVE_MODE_EXT, frame(0, 0, 0, 0, 0, 0, 0x11, 0xCC)) }
        repeat(2) { receive(CanDecoder.ID_DRIVE_MODE, frame(0, 0, 0, 0, 0, 0, 0x10, 0)) }
        assertEquals("the previous link left Sport", DriveMode.SPORT, appVs.value.driveMode)
        assertTrue("with its drive-mode stamps", appVs.value.fieldLastUpdateMs.keys.any { it.startsWith(CanDecoder.MODE_RUN_PREFIX) })

        DiagnosticLogger.sessionStart("before", 0, transport = "before", prefs = null)
        val service = TestServices.create()
        service.startConnection()
        try {
            assertEquals("a session must have started (else the test is vacuous)", "TCP SLCAN (MeatPi Pro)", DiagnosticLogger.sessionTransport)
            val vs = appVs.value
            assertEquals("no mode before this connection reports one", DriveMode.UNKNOWN, vs.driveMode)
            assertEquals("no drive-mode stamp from the previous link", emptyList<String>(),
                vs.fieldLastUpdateMs.keys.filter { it.startsWith("driveMode") })
            assertNull(vs.fieldLastUpdateMs[CanDecoder.modeRunTag(DriveMode.SPORT)])

            // This connection's 0x1B0 with nibble 1 waits for its own 0x420 (has420Arrived, reset at the start) ...
            receive(CanDecoder.ID_DRIVE_MODE, frame(0, 0, 0, 0, 0, 0, 0x10, 0))
            assertEquals(DriveMode.UNKNOWN, appVs.value.driveMode)
            // ... and resolves once it has one: Track this time
            receive(CanDecoder.ID_DRIVE_MODE_EXT, frame(0, 0, 0, 0, 0, 0, 0x11, 0xCD))
            receive(CanDecoder.ID_DRIVE_MODE, frame(0, 0, 0, 0, 0, 0, 0x10, 0))
            assertEquals(DriveMode.TRACK, appVs.value.driveMode)
            assertTrue(appVs.value.fieldLastUpdateMs.containsKey(CanDecoder.modeRunTag(DriveMode.TRACK)))
        } finally {
            service.stopConnection(LinkStopReason.USER_DISCONNECT)
        }
    }
}
