package com.openrs.dash

import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
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

    @After fun reset() {                                 // theme and update state are process-wide
        setThemeMode(ThemeMode.NIGHT)
        updateState(com.openrs.dash.update.UpdateState.Idle)
    }

    /** UpdateManager's state is private: set it by reflection so the link pill reads "UPD" (test only). */
    private fun updateState(s: com.openrs.dash.update.UpdateState) {
        val f = com.openrs.dash.update.UpdateManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(com.openrs.dash.update.UpdateManager) as kotlinx.coroutines.flow.MutableStateFlow<com.openrs.dash.update.UpdateState>).value = s
    }

    /** #316: LINK_PAUSED is a link pause (PAUSED · ENGINE OFF with the link up, PAUSED · NO LINK with it down). */
    private enum class Rec { ACTIVE, PAUSED, LINK_PAUSED, OFF }

    /** [cogTaps]: how many of the two taps (width − 22 dp, width − 42 dp, on line 1) opened Settings. */
    private class Shot(val bmp: Bitmap, val d: Float, val heightDp: Float, val tags: Map<String, Rect>, val cogTaps: Int,
                       val recText: String? = null)

    private fun render(esc: EscStatus, rec: Rec, fontScale: Float, theme: ThemeMode, link: LinkUi = LinkUi.LIVE,
                       mode: DriveMode = DriveMode.NORMAL, updating: Boolean = false): Shot {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        setThemeMode(theme)
        updateState(if (updating) com.openrs.dash.update.UpdateState.Downloading(0.5f, 1L, 2L) else com.openrs.dash.update.UpdateState.Idle)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var settings = 0
        val vs = VehicleState(isConnected = link == LinkUi.LIVE, isReconnecting = link == LinkUi.RECONNECTING,
            driveMode = mode, escStatus = esc)
        val drive = DriveState(phase = DrivePhase.of(rec != Rec.OFF, rec == Rec.PAUSED || rec == Rec.LINK_PAUSED),
            pauseReason = when (rec) { Rec.PAUSED -> PauseReason.HAND; Rec.LINK_PAUSED -> PauseReason.LINK; else -> null },
            startTime = System.currentTimeMillis() - (2 * 3_600_000L + 14 * 60_000L), cumulativeDistanceKm = 187.3)
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Box(Modifier.fillMaxWidth()) {
                    AppHeader(vs = vs, prefs = UserPrefs(), onSettings = { settings++ }, onConnect = {}, onDisconnect = {},
                        onReconnect = {}, link = link, driveState = drive)
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
        full.recycle()
        val tags = HashMap<String, Rect>()
        var recText: String? = null
        fun walk(n: SemanticsNode) {
            if (!n.layoutInfo.isPlaced) return   // what is on screen: SubcomposeLayout keeps measured-only copies composed
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { tags[it] = n.boundsInRoot }
            if (n.config.getOrNull(SemanticsProperties.TestTag) == "headerRecLabel")
                recText = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
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
        tap(22f); tap(42f)
        // Each case is a new activity: destroy it, or 100+ renders run the test JVM out of memory
        controller.pause().stop().destroy()
        // ... and drain the looper: work left queued at exit stops later Compose tests in this JVM from recomposing (QA F3)
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        return Shot(bmp, d, h / d, tags, settings, recText)
    }

    private fun hue(p: Int): FloatArray = FloatArray(3).also { AColor.colorToHSV(p, it) }

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** Every case at one width; [expectTwoLines] per font scale. Returns the per-case log for the report. */
    private fun matrix(widthDp: Int, expectTwoLines: Map<Float, Boolean>) {
        for (theme in listOf(ThemeMode.NIGHT, ThemeMode.DAY)) for ((fs, two) in expectTwoLines) {
            val heights = HashSet<Float>()
            for (esc in listOf(EscStatus.PARTIAL, EscStatus.OFF, EscStatus.ON)) for (rec in Rec.values()) {
                // #316: the link pause only beside the widest ESC pill (Sport) — every render is a new activity, and the
                // added cases at every ESC ran the full suite's test JVM out of memory
                if (rec == Rec.LINK_PAUSED && esc != EscStatus.PARTIAL) continue
                val case = "$widthDp dp $theme font $fs $esc $rec"
                val s = render(esc, rec, fs, theme)
                heights += s.heightDp
                println("#283M [$case] height=${s.heightDp} rec=\"${s.recText}\" tags=${s.tags.mapValues { (_, r) ->
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
                // #316: a pause shows a whole label or none (dot only) — its own label, or plain PAUSED where it doesn't fit
                if (rec == Rec.PAUSED || rec == Rec.LINK_PAUSED) {
                    val full = if (rec == Rec.PAUSED) "PAUSED" else "PAUSED · ENGINE OFF"
                    assertTrue("[$case] REC label cut or wrong: ${s.recText}", s.recText == null || s.recText in setOf(full, "PAUSED"))
                }
                assertEquals("[$case] Settings cog 44 dp target", 2, s.cogTaps)
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

    /**
     * QA T1: at the switch points the line count must not depend on the link state or the drive mode either — RCN / CONN /
     * OFF (MODE / ESC placeholders), UPD (update downloading), CUSTOM / TRACK — recording or not. Synthetic states.
     */
    private fun linkModeSweep(widthDp: Int, expectTwoLines: Map<Float, Boolean>) {
        val links = listOf(LinkUi.LIVE to false, LinkUi.RECONNECTING to false, LinkUi.CONNECTING to false,
            LinkUi.OFFLINE to false, LinkUi.LIVE to true, LinkUi.OFFLINE to true)          // (link, update downloading)
        for ((fs, two) in expectTwoLines) for ((link, upd) in links)
            for (mode in listOf(DriveMode.NORMAL, DriveMode.CUSTOM, DriveMode.TRACK)) for (rec in listOf(Rec.ACTIVE, Rec.LINK_PAUSED, Rec.OFF)) {
                if (rec == Rec.LINK_PAUSED && mode != DriveMode.NORMAL) continue   // #316: one mode is enough (memory, as above)
                val case = "$widthDp dp font $fs ${link.pill}${if (upd) "+UPD" else ""} $mode $rec"
                val s = render(EscStatus.PARTIAL, rec, fs, ThemeMode.NIGHT, link, mode, upd)
                assertEquals("[$case] header lines", if (two) 88f else 44f, s.heightDp, 0.5f)
                val pills = s.tags["headerPills"]; assertNotNull("[$case] pills", pills); pills!!
                val linkR = s.tags["headerLink"]; assertNotNull("[$case] link pill", linkR)
                assertTrue("[$case] pills overlap the link pill: $pills / $linkR", !overlaps(pills, linkR!!))
                val dot = s.tags["headerRecDot"]
                if (rec == Rec.ACTIVE) {
                    assertNotNull("[$case] REC dot missing", dot)
                    assertTrue("[$case] dot not on line 1 or too small: $dot", dot!!.bottom <= 44 * s.d && dot.width >= 6.5f * s.d)
                    assertTrue("[$case] pills overlap the dot", !overlaps(pills, dot))
                }
                assertEquals("[$case] Settings cog 44 dp target", 2, s.cogTaps)
            }
    }

    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    @Test fun `360 dp switch points - link state and drive mode don't change the line count`() =
        linkModeSweep(360, mapOf(1.15f to false, 1.3f to true))

    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @Test fun `411 dp switch points - link state and drive mode don't change the line count`() =
        linkModeSweep(411, mapOf(1.3f to false, 2.0f to true))
}
