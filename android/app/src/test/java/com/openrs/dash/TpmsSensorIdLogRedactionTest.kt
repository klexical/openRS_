package com.openrs.dash

import android.util.Log
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.VehicleState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * A TPMS sensor ID is a vehicle identifier, so it never reaches logcat (#518): release builds keep debug log calls, and
 * logcat reaches adb and bug reports. The parser's line for a 0x280B report from an unmapped sensor used to print the
 * ID in hex. Production path: [ObdResponseParser.parseBcmReassembled] with the reassembled report, then every line
 * Robolectric captured from `android.util.Log`, as logcat would show it. The sensor ID is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class TpmsSensorIdLogRedactionTest {

    private val unmapped = 0x0A0B0C09L
    /** The ID's forms a log line could carry: hex either case, bytes spaced, decimal. */
    private val forms = listOf("0A0B0C09", "0a0b0c09", "0A 0B 0C 09", "0a 0b 0c 09", unmapped.toString())

    @Before fun setUp() { ShadowLog.clear() }
    @After fun tearDown() { ShadowLog.clear() }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** 62 28 0B | sensor ID | pressure hi lo | temp | status (6 = live) | checksum: a valid report from [id]. */
    private fun report(id: Long) = bytes(0x62, 0x28, 0x0B,
        (id shr 24).toInt() and 0xFF, (id shr 16).toInt() and 0xFF, (id shr 8).toInt() and 0xFF, id.toInt() and 0xFF,
        0x02, 0x94, 65, 0x06, 0xA5)

    private fun everythingLogged(): String = ShadowLog.getLogs().joinToString("\n") {
        "${it.tag}: ${it.msg}" + (it.throwable?.let { t -> "\n" + Log.getStackTraceString(t) } ?: "")
    }

    @Test fun `a 0x280B report from an unmapped sensor is dropped and its ID never reaches the log`() {
        val mapped = VehicleState(tpmsSensorIdLF = 0x0A0B0C01L, tpmsSensorIdRF = 0x0A0B0C02L,
            tpmsSensorIdRR = 0x0A0B0C03L, tpmsSensorIdLR = 0x0A0B0C04L)
        var update: VehicleState? = null
        ObdResponseParser.parseBcmReassembled(report(unmapped), mapped) { update = it }

        assertNull("a report from an unmapped sensor updates no tyre", update)
        val logged = everythingLogged()
        assertTrue("not vacuous: the drop is logged", ShadowLog.getLogsForTag("OBD").any { "0x280B" in it.msg })
        forms.forEachIndexed { i, f -> assertFalse("the sensor ID reached the log (form $i)", logged.contains(f)) }
    }
}
