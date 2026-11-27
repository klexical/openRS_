package com.openrs.dash

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import android.view.View
import android.view.ViewGroup
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.ReadinessCard
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.ThermalLimits
import com.openrs.dash.ui.ThermalReadinessEngine
import com.openrs.dash.ui.ThermalState
import com.openrs.dash.ui.Tokens
import com.openrs.dash.ui.UserPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #592: at 360 dp and font 2.0 the THERMAL readiness card's STREET / TRACK / RACE chips took the row, the CHECKLIST
 * toggle beside them got a column one character wide and stacked C / H / E / C / K … down the card's edge, and the card
 * grew to most of the screen. The production [ReadinessCard] under the app's MaterialTheme at 360 dp, font 1.0 / 1.3 /
 * 2.0, with a TEMP CRITICAL verdict (the longest reason the WARNING demo shows) and with and without the CUSTOM chip:
 * CHECKLIST is one line, every chip label is one line, the chips share one row (except four chips at font 2.0, wider
 * than the card, which wrap whole), the toggle keeps its 44 dp height, and the card stays within half the screen (the
 * card is meant to be about the height of the old readiness card, so oil and coolant stay in view).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class ReadinessCardFitTest {

    private companion object {
        val SCALES = listOf(1.0f, 1.3f, 2.0f)
        const val MAX_CARD_DP = 360f
        const val T0 = 1_700_000_000_000L
    }

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        idle(8)
    }

    private fun idle(frames: Int) = repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap { nodes(it) }

    private fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun composeRoot(v: View): ViewRootForTest? = v as? ViewRootForTest
        ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { composeRoot(g.getChildAt(it)) } }

    /** Oil and PTU over the STREET critical limits, fresh: TEMP CRITICAL, "Oil and PTU above critical limit". */
    private fun overheating(p: UserPrefs) = ThermalReadinessEngine().evaluate(
        VehicleState(
            isConnected = true, oilTempC = 150.0, coolantTempC = 95.0, ptuTempC = 160.0, rduTempC = 60.0,
            fieldLastUpdateMs = listOf("oilTempC", "coolantTempC", "ptuTempC", "rduTempC").associateWith { T0 - 500L },
        ),
        ThermalLimits.from(p), T0, 1_000L,
    ).also { check(it.state == ThermalState.OVERHEATING) { "expected TEMP CRITICAL, got ${it.state}" } }

    private fun problems(fontScale: Float, custom: Boolean): List<String> {
        RuntimeEnvironment.setFontScale(fontScale)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val base = UserPrefs()
        val p = if (custom) base.copy(customThermal = base.thermalProfile) else base
        val verdict = overheating(p)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                // THERMAL's page margin
                Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.PagePad)) {
                    Box(Modifier.testTag("card")) { ReadinessCard(verdict, p) }
                }
            }
        }
        try {
            idle(60)
            val case = "font $fontScale${if (custom) " +CUSTOM" else ""}"
            val density = controller.get().resources.displayMetrics.density
            val root = composeRoot(controller.get().window.decorView)!!.semanticsOwner.unmergedRootSemanticsNode
            val all = nodes(root).filter { it.layoutInfo.isPlaced }
            val out = mutableListOf<String>()
            val label = all.firstOrNull { textOf(it) == "CHECKLIST" }
            if (label == null) out += "[$case] CHECKLIST not found"
            label?.let { n ->
                val layout = mutableListOf<TextLayoutResult>()
                n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
                val lines = layout.firstOrNull()?.lineCount
                if (lines != 1) out += "[$case] CHECKLIST takes $lines lines"
                val toggle = all.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
                    .filter { it.boundsInRoot.contains(n.boundsInRoot.center) }
                    .minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
                if (toggle == null) out += "[$case] CHECKLIST toggle not found"
                else if (toggle.boundsInRoot.height / density < 43.5f)
                    out += "[$case] CHECKLIST toggle ${toggle.boundsInRoot.height / density} dp tall"
            }
            val chips = listOf("STREET", "TRACK", "RACE") + if (custom) listOf("CUSTOM") else emptyList()
            val chipNodes = chips.mapNotNull { c -> all.firstOrNull { textOf(it) == c } }
            if (chipNodes.size != chips.size) out += "[$case] chips found: ${chipNodes.map { textOf(it) }}"
            val tops = chipNodes.map { it.boundsInRoot.top }
            // Four chips (CUSTOM) are wider than the card at font 2.0: there they may wrap, whole chips only
            val oneRow = !(custom && fontScale > 1.3f)
            if (oneRow && tops.isNotEmpty() && tops.max() - tops.min() > 0.5f) out += "[$case] chips not on one row: $tops"
            for (c in chipNodes) {
                val layout = mutableListOf<TextLayoutResult>()
                c.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
                if (layout.firstOrNull()?.lineCount != 1) out += "[$case] chip '${textOf(c)}' wraps"
            }
            val cardHeight = all.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "card" }
                ?.boundsInRoot?.height ?: 0f
            if (cardHeight / density > MAX_CARD_DP) out += "[$case] card ${cardHeight / density} dp tall"
            println("#592 [$case] checklist=${label?.boundsInRoot} card=${cardHeight / density} dp")
            return out
        } finally {
            controller.pause().stop().destroy()
            idle(8)
        }
    }

    @Test fun `readiness card keeps CHECKLIST one line and the chips one row - 360 dp, font 1_0 1_3 2_0`() {
        val out = SCALES.flatMap { fs -> listOf(false, true).flatMap { problems(fs, it) } }
        assertEquals(emptyList<String>(), out)
    }
}
