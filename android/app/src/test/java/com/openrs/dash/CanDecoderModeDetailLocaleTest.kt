package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The 0x420 detail key that corroborates a Sport / Track report (#252) must not follow the phone's locale. It was built
 * with `"%02X.%d".format(...)` and no locale, so under ar_EG and fa_IR the bit-0 digit came out Arabic-Indic / Persian
 * ("11.١") and never matched the "11.0" / "11.1" it is compared with: no report ever carried
 * [CanDecoder.MODE_RESOLVED_PAIR_TAG], which a drive-mode request needs before it reads as confirmed (DriveCommand). Found
 * while fixing #564. Frames go through decode + stampFresh, the path LinkSession takes.
 */
class CanDecoderModeDetailLocaleTest {

    private val saved = Locale.getDefault()
    private val locales = listOf(Locale.US, Locale("ar", "EG"), Locale.forLanguageTag("fa-IR"))

    @Before fun setUp() { Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(saved); CanDecoder.resetSessionState() }

    private val sportOrTrack1B0 = byteArrayOf(0, 0, 0, 0, 0, 0, 0x10, 0)
    private fun detail420(bit0: Int) = byteArrayOf(0, 0, 0, 0, 0, 0, 0x11, (0xCC or bit0).toByte())

    /** Two agreeing 0x420 details, then two 0x1B0 Sport-or-Track reports, ~600 ms / 100 ms apart like the car. */
    private fun resolve(bit0: Int): VehicleState {
        CanDecoder.resetSessionState()
        var vs = VehicleState(isConnected = true)
        fun feed(id: Int, data: ByteArray, at: Long) {
            val d = CanDecoder.decode(id, data, vs) ?: return
            vs = CanDecoder.stampFresh(id, vs, d, at)
        }
        feed(0x420, detail420(bit0), 10_000L)
        feed(0x420, detail420(bit0), 10_600L)
        feed(0x1B0, sportOrTrack1B0, 10_700L)
        feed(0x1B0, sportOrTrack1B0, 10_800L)
        return vs
    }

    @Test fun `a Sport or Track report is corroborated by its detail pair in every locale`() = locales.forEach { loc ->
        Locale.setDefault(loc)
        try {
            for ((bit0, mode) in listOf(0 to DriveMode.SPORT, 1 to DriveMode.TRACK)) {
                val vs = resolve(bit0)
                assertEquals("$loc", mode, vs.driveMode)
                assertTrue("$loc $mode: resolved pair missing from ${vs.fieldLastUpdateMs.keys}",
                    vs.fieldLastUpdateMs.containsKey(CanDecoder.MODE_RESOLVED_PAIR_TAG))
            }
        } finally { Locale.setDefault(Locale.US) }
    }
}
