package com.openrs.dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AppHeader
import com.openrs.dash.ui.LinkUi
import com.openrs.dash.ui.ThemeMode
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.setThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * #283 two-line header (owner: keep the system font size). The production [AppHeader] at 360 and 411 dp, font scale
 * 1.0 / 1.15 / 1.3 / 2.0, NIGHT and DAY, ESC Sport / Off / Normal (Normal = the traction symbol alone; Sport is the longest
 * ESC text — #288), recording active / paused / not recording. MODE NORMAL + ESC pairs are synthetic. For every case:
 * - the header is one 44 dp line or two (88 dp), and that height is the same for every ESC and REC state at a given
 *   width, font scale and theme (the page under it never jumps);
 * - while recording (active or paused) the REC dot ("headerRecDot") is ≥ 6.5 dp, on line 1, and drawn: the pixel at its
 *   centre is Orange-hued (active) or differs from the header surface (paused, grey);
 * - the MODE / ESC pills ("headerPills") overlap neither the link pill ("headerLink") nor the dot, and stay on screen;
 * - real taps at width − 22 dp and width − 42 dp on line 1 open Settings (the cog's 44 dp target).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderFitMatrixTest {

    @After fun night() = setThemeMode(ThemeMode.NIGHT)   // theme is process-wide state

    private enum class Rec { ACTIVE, PAUSED, OFF }

    private class Shot(val bmp: Bitmap, val d: Float, val heightDp: Float, val tags: Map<String, Rect>, val settings: () -> Int,
                       val tap: (Float) -> Unit)

    private fun render(esc: EscStatus, rec: Rec, fontScale: Float, theme: ThemeMode): Shot {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        setThemeMode(theme)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var settings = 0
        val vs = VehicleState(isConnected = true, driveMode = DriveMode.NORMAL, escStatus = esc)
        val drive = DriveState(isRecording = rec != Rec.OFF, isPaused = rec == Rec.PAUSED,
            startTime = System.currentTimeMillis() - (2 * 3_600_000L + 14 * 60_000L), cumulativeDistanceKm = 187.3)
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Box(Modifier.fillMaxWidth()) {
                    AppHeader(vs = vs, prefs = UserPrefs(), onSettings = { settings++ }, onConnect = {}, onDisconnect = {},
                        onReconnect = {}, link = LinkUi.LIVE, driveState = drive)
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val root: View = activity.window.decorView
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(full))
        val d = activity.resources.displayMetrics.density
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val compose = content.getChildAt(0) as ViewGroup
        val loc = IntArray(2).also { compose.getLocationInWindow(it) }
        val h = compose.height
        val bmp = Bitmap.createBitmap(full, 0, loc[1], full.width, h)
        val tags = HashMap<String, Rect>()
        fun walk(n: SemanticsNode) {
            if (!n.layoutInfo.isPlaced) return   // what is on screen: SubcomposeLayout keeps measured-only copies composed
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { tags[it] = n.boundsInRoot }
            n.children.forEach { walk(it) }
        }
        walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        val tap: (Float) -> Unit = { fromRightDp ->
            val x = root.width - fromRightDp * d; val y = loc[1] + 22 * d
            val t = SystemClock.uptimeMillis()
            root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        return Shot(bmp, d, h / d, tags, { settings }, tap)
    }

    private fun hue(p: Int): FloatArray = FloatArray(3).also { AColor.colorToHSV(p, it) }

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** Every case at one width; [expectTwoLines] per font scale. Returns the per-case log for the report. */
    private fun matrix(widthDp: Int, expectTwoLines: Map<Float, Boolean>) {
        for (theme in listOf(ThemeMode.NIGHT, ThemeMode.DAY)) for ((fs, two) in expectTwoLines) {
            val heights = HashSet<Float>()
            for (esc in listOf(EscStatus.PARTIAL, EscStatus.OFF, EscStatus.ON)) for (rec in Rec.values()) {
                val case = "$widthDp dp $theme font $fs $esc $rec"
                val s = render(esc, rec, fs, theme)
                heights += s.heightDp
                println("#283M [$case] height=${s.heightDp} tags=${s.tags.mapValues { (_, r) ->
                    "%.0f..%.0f×%.0f..%.0f".format(r.left / s.d, r.right / s.d, r.top / s.d, r.bottom / s.d) }}")
                assertEquals("[$case] header lines", if (two) 88f else 44f, s.heightDp, 0.5f)
                val pills = s.tags["headerPills"]; assertNotNull("[$case] pills", pills); pills!!
                val link = s.tags["headerLink"]; assertNotNull("[$case] link pill", link)
                assertTrue("[$case] pills overlap the link pill: $pills / $link", !overlaps(pills, link!!))
                assertTrue("[$case] pills off screen: $pills", pills.left >= 0f && pills.right <= s.bmp.width)
                val dot = s.tags["headerRecDot"]
                if (rec == Rec.OFF) assertNull("[$case] no REC dot without a recording", dot)
                else {
                    assertNotNull("[$case] REC dot missing", dot)
                    assertTrue("[$case] dot < 6.5 dp: $dot", dot!!.width >= 6.5f * s.d && dot.height >= 6.5f * s.d)
                    assertTrue("[$case] dot not on line 1: $dot", dot.bottom <= 44 * s.d)
                    assertTrue("[$case] pills overlap the dot", !overlaps(pills, dot))
                    val px = s.bmp.getPixel(dot.center.x.toInt(), dot.center.y.toInt())
                    val surf = s.bmp.getPixel(2, 2)
                    if (rec == Rec.ACTIVE) {
                        val (hh, sat, v) = hue(px).let { Triple(it[0], it[1], it[2]) }
                        assertTrue("[$case] dot centre is not Orange: h=$hh s=$sat v=$v", hh in 8f..28f && sat > 0.5f && v > 0.5f)
                    } else {
                        val diff = listOf(16, 8, 0).sumOf { sh -> Math.abs(((px shr sh) and 0xFF) - ((surf shr sh) and 0xFF)) }
                        assertTrue("[$case] paused dot not drawn (centre = surface, diff $diff)", diff >= 30)
                    }
                }
                s.tap(22f); s.tap(42f)
                assertEquals("[$case] Settings cog 44 dp target", 2, s.settings())
            }
            assertEquals("$widthDp dp $theme font $fs: one height for every ESC / REC state", 1, heights.size)
        }
    }

    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    @Test fun `360 dp — one line at font 1_0 and 1_15, two lines at 1_3 and 2_0`() =
        matrix(360, mapOf(1.0f to false, 1.15f to false, 1.3f to true, 2.0f to true))

    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @Test fun `411 dp — one line at font 1_0 and 1_3, two lines at 2_0`() =
        matrix(411, mapOf(1.0f to false, 1.3f to false, 2.0f to true))
}
