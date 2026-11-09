package com.openrs.dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AppHeader
import com.openrs.dash.ui.LinkUi
import com.openrs.dash.ui.UserPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #283: the header's recording marker at the narrowest supported width. The production [AppHeader] is drawn (Robolectric
 * native graphics, 1080 px wide at xxhdpi = 360 dp, like the QA emulator) and its pixels are read: the REC dot and label
 * are the header's only Orange (#FF4D00, NIGHT) — ESC Sport / Launch are Warn, the link dot Ok, the wordmark accent blue.
 * So "the marker draws" here means Orange pixels a dot's size, left of the link pill, not a layout number or a
 * semantics node. The Settings cog is checked by real taps on its 44 dp target, which HeaderRow measures first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderRecMarkerTest {

    private class Shot(val bmp: Bitmap, val density: Float, val settingsTaps: () -> Int, val tap: (Float) -> Unit)

    private fun render(
        esc: EscStatus,
        mode: DriveMode = DriveMode.NORMAL,
        recording: Boolean = true,
        paused: Boolean = false,
        fontScale: Float? = null,
    ): Shot {
        // The header's pulses are infinite animations: with frames free-running, idle() never returns. Paused, a
        // frame runs only when the clock moves (16 ms each).
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var settings = 0
        val vs = VehicleState(isConnected = true, driveMode = mode, escStatus = esc)
        // A long drive (2 h 14 min, 187 km): the longest REC label the header ever has to drop
        val drive = DriveState(isRecording = recording, isPaused = paused,
            startTime = System.currentTimeMillis() - (2 * 3_600_000L + 14 * 60_000L), cumulativeDistanceKm = 187.3)
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale ?: base.fontScale)) {
                Box(Modifier.fillMaxWidth()) {
                    AppHeader(vs = vs, prefs = UserPrefs(), onSettings = { settings++ }, onConnect = {}, onDisconnect = {},
                        onReconnect = {}, link = LinkUi.LIVE, driveState = drive)
                }
            }
        }
        // First frames only: the REC pulse starts at full alpha
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val root: View = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val d = activity.resources.displayMetrics.density
        val header = activity.findViewById<View>(android.R.id.content)
        val loc = IntArray(2).also { header.getLocationInWindow(it) }
        val tap: (Float) -> Unit = { xFromRightDp ->
            val x = root.width - xFromRightDp * d
            val y = loc[1] + 22 * d                       // middle of the 44 dp header
            val t = SystemClock.uptimeMillis()
            root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        // Crop to the header row (44 dp from the content top)
        val top = loc[1]
        val crop = Bitmap.createBitmap(bmp, 0, top, bmp.width, (44 * d).toInt())
        return Shot(crop, d, { settings }, tap)
    }

    /** Orange REC pixels (dot or label at any pulse alpha ≥ ~0.5 over the NIGHT surface): x range and count. */
    private data class Marker(val count: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    private fun orange(b: Bitmap): Marker {
        var n = 0; var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val p = b.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
            if (r >= 120 && r - g >= 70 && bl <= 45) {
                n++; x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
            }
        }
        return Marker(n, x0, x1, y0, y1)
    }

    /** OPENRS_HEADER_SHOTS=<dir>: each case's header crop as PNG, for comparing a layout before and after a change. */
    private fun save(s: Shot, case: String) {
        val dir = System.getenv("OPENRS_HEADER_SHOTS") ?: return
        java.io.File(dir).mkdirs()
        java.io.FileOutputStream(java.io.File(dir, case.replace(' ', '_') + ".png")).use {
            s.bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertDotDrawn(s: Shot, case: String) {
        save(s, case)
        val m = orange(s.bmp)
        val dp = s.density
        println("#283 [$case] orange px=${m.count} x=${m.minX}..${m.maxX} (${"%.1f".format((m.maxX - m.minX + 1) / dp)} dp) " +
            "y=${m.minY}..${m.maxY} header=${"%.0f".format(s.bmp.width / dp)} dp")
        assertTrue("[$case] the REC marker draws nothing (no Orange pixel in the header)", m.count > 0)
        val w = (m.maxX - m.minX + 1) / dp; val h = (m.maxY - m.minY + 1) / dp
        assertTrue("[$case] marker too small: ${"%.1f".format(w)} × ${"%.1f".format(h)} dp", w >= 6f && h >= 6f)
        // Left of the link pill (52 dp minimum + 4 dp padding each side) and the 44 dp cog
        assertTrue("[$case] marker overlaps the link pill / cog (x=${m.maxX / dp} dp)", m.maxX < s.bmp.width - (44 + 56) * dp)
    }

    private fun assertCog(s: Shot, case: String) {
        // QA's tap point (width − 22 dp) and the target's inner edge (width − 42 dp) open Settings
        s.tap(22f); s.tap(42f)
        assertEquals("[$case] the Settings cog's 44 dp target", 2, s.settingsTaps())
    }

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun `360 dp LIVE NORMAL ESC SPORT recording keeps the REC dot and the cog`() {
        val s = render(EscStatus.PARTIAL)
        assertDotDrawn(s, "360 SPORT")
        assertCog(s, "360 SPORT")
    }

    // (The ignored "ESC LAUNCH" case is gone: raw 3 has no name and shows the placeholder — REQ-rc8-esc-owner-wording.)
    // Drive mode + ESC pairs in this class are synthetic: the two are independent readings.

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun `360 dp LIVE CUSTOM ESC SPORT recording keeps the REC dot and the cog`() {
        val s = render(EscStatus.PARTIAL, mode = DriveMode.CUSTOM)
        assertDotDrawn(s, "360 CUSTOM SPORT")
        assertCog(s, "360 CUSTOM SPORT")
    }

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun `360 dp ESC ON recording shows the dot (QA control)`() {
        val s = render(EscStatus.ON)
        assertDotDrawn(s, "360 ON")
        assertCog(s, "360 ON")
    }

    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @Test fun `411 dp ESC ON recording shows the REC label (control)`() {
        val s = render(EscStatus.ON)
        assertDotDrawn(s, "411 ON")
        val m = orange(s.bmp)
        assertTrue("[411 ON] REC label expected beside the dot (${(m.maxX - m.minX + 1) / s.density} dp)",
            (m.maxX - m.minX + 1) / s.density >= 18f)
        assertCog(s, "411 ON")
    }

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun `not recording draws no REC marker`() {
        val s = render(EscStatus.PARTIAL, recording = false)
        save(s, "360 SPORT idle")
        assertEquals("no Orange pixel without a recording", 0, orange(s.bmp).count)
        assertCog(s, "360 SPORT idle")
    }

    // Font scale (system "font size"): the pills' sp text grows. Measured at 360 dp SPORT: 1.15 → the fixed parts already
    // exceed the row by 5 dp, 1.3 → by 20 dp (the header overflows with no recording at all — pre-existing). An owner decision.
    @Ignore("#283: font scale > 1 at 360 dp — the header itself overflows; owner decision")
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun `360 dp ESC SPORT recording at font scale 1_15 keeps the REC dot`() {
        assertDotDrawn(render(EscStatus.PARTIAL, fontScale = 1.15f), "360 SPORT font 1.15")
    }
}
