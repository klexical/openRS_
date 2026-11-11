package com.openrs.dash

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.PauseReason
import com.openrs.dash.ui.RecordingStrip
import com.openrs.dash.ui.UserPrefs
import org.junit.Assert.assertEquals
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
 * #316 D-A (S3 on 9c65a0b: at 360 dp, font 2.0, "PAUSED · NO LINK" pushed the strip's other items into a tall column of
 * wrapped characters). DRIVE's recording strip follows the header's rule (#224): one line always; REC and the drive's
 * label (the time while recording) first, then distance, avg and points, each dropped whole from the right when it does
 * not fit — never wrapped, never cut; the label itself is never dropped or shortened. The production [RecordingStrip] at
 * 360 dp (inside DRIVE's 16 dp page margins), font 1.0 / 1.3 / 2.0, in every state it shows: recording (a long drive),
 * hand pause, link pause with the connection down and with it up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class RecordingStripFitTest {

    private class Item(val text: String, val bounds: Rect)

    /** The strip's bounds and its text items, left to right, as placed. */
    private fun render(drive: DriveState, connected: Boolean, fontScale: Float): Pair<Rect, List<Item>> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { RecordingStrip(drive, connected, UserPrefs()) }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val compose = (controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
        var strip: Rect? = null
        val items = mutableListOf<Item>()
        fun walk(n: SemanticsNode) {
            if (!n.layoutInfo.isPlaced) return
            if (n.config.getOrNull(SemanticsProperties.TestTag) == "recStrip") strip = n.boundsInRoot
            n.config.getOrNull(SemanticsProperties.Text)?.let { t -> items += Item(t.joinToString("") { it.text }, n.boundsInRoot) }
            n.children.forEach { walk(it) }
        }
        walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        controller.pause().stop().destroy()
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        return strip!! to items.sortedBy { it.bounds.left }
    }

    /** A long drive: 2 h 14 min, 187.3 km at an average of 83.6 km/h, 12,345 points. */
    private fun drive(phase: DrivePhase, reason: PauseReason? = null) = DriveState(
        phase = phase, pauseReason = reason, driveId = 1,
        startTime = System.currentTimeMillis() - (2 * 3_600_000L + 14 * 60_000L),
        cumulativeDistanceKm = 187.3, speedSum = 83.6 * 1_000, speedSamples = 1_000, totalPointCount = 12_345,
    )

    @Test fun `one line in every state and font scale - REC and the label always, the rest dropped whole from the right`() {
        val cases = listOf(
            Triple("recording", drive(DrivePhase.RECORDING), true),
            Triple("hand pause", drive(DrivePhase.PAUSED, PauseReason.HAND), true),
            Triple("no link", drive(DrivePhase.PAUSED, PauseReason.LINK), false),
            Triple("engine off", drive(DrivePhase.PAUSED, PauseReason.LINK), true),
        )
        val failures = mutableListOf<String>()
        for (fs in listOf(1.0f, 1.3f, 2.0f)) for ((name, d, connected) in cases) {
            val case = "360 dp font $fs $name"
            val (strip, items) = render(d, connected, fs)
            val texts = items.map { it.text }
            println("#316S [$case] strip=${strip.height}px items=$texts")
            val label = com.openrs.dash.data.DriveLabel.of(d, connected)!!.text
            // the label slot: the time while recording, the pause label while paused
            val second = if (d.phase == DrivePhase.RECORDING) Regex("""\d{2}:\d{2}:\d{2}""") else Regex(Regex.escape(label))
            val rest = listOf("116.4 mi", "avg 52 mph", "12345 pts")          // UserPrefs() default: MPH
            if (texts.size < 2 || texts[0] != "REC" || !second.matches(texts[1]))
                failures += "[$case] REC + the label first, whole: $texts"
            else if (texts.drop(2) != rest.take(texts.size - 2))
                failures += "[$case] the rest must be a whole prefix of $rest: ${texts.drop(2)}"
            val lineH = items.firstOrNull()?.bounds?.height ?: 0f
            for (it in items) {
                if (it.bounds.height > lineH * 1.5f) failures += "[$case] '${it.text}' wraps: ${it.bounds.height} vs ${lineH}px"
                if (it.bounds.right > strip.right + 0.5f || it.bounds.left < strip.left - 0.5f) failures += "[$case] '${it.text}' outside the strip"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `at font 1_0 every item fits - nothing is dropped in any state`() {
        for ((d, connected) in listOf(drive(DrivePhase.RECORDING) to true, drive(DrivePhase.PAUSED, PauseReason.LINK) to true)) {
            val (_, items) = render(d, connected, 1.0f)
            assertEquals("${items.map { it.text }}", 5, items.size)
        }
    }
}
