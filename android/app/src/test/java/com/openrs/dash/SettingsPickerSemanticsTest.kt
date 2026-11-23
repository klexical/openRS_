package com.openrs.dash

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.UNIT_ROWS
import com.openrs.dash.ui.UnitsSection
import com.openrs.dash.ui.units.Region
import com.openrs.dash.ui.units.UnitText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #504: a screen reader hears which option of each Settings picker is chosen. The production [UnitsSection] (the Region
 * picker and the eleven unit rows, all on the shared SegmentedPicker that PERF's knock scope and every other Settings
 * picker use): each option is a selectable radio button inside a selectable group, exactly one option of each row is
 * selected and it is the staged one, and the short region names carry their full names as content descriptions.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsPickerSemanticsTest {

    /** The merged-tree node of each option: its text, selected state, role, content description and its group. */
    private class Option(val text: String, val selected: Boolean?, val role: Role?, val description: String?,
                         val inGroup: Boolean, val groupId: Int?)

    private fun options(units: com.openrs.dash.ui.units.UnitSet): List<Option> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                Box(Modifier.verticalScroll(rememberScrollState()).width(314.dp)) { UnitsSection(units) {} }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val content = controller.get().findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            as android.view.ViewGroup
        val root = (content.getChildAt(0) as ViewRootForTest).semanticsOwner.rootSemanticsNode
        val out = mutableListOf<Option>()
        fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun walk(n: SemanticsNode, group: SemanticsNode?) {
            val isGroup = n.config.getOrNull(SemanticsProperties.SelectableGroup) != null
            val g = if (isGroup) n else group
            val role = n.config.getOrNull(SemanticsProperties.Role)
            val selected = n.config.getOrNull(SemanticsProperties.Selected)
            if (selected != null || role != null) {
                out += Option(textOf(n) ?: "", selected, role,
                    n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "), g != null, g?.id)
            }
            n.children.forEach { walk(it, g) }
        }
        walk(root, null)
        controller.pause().stop().destroy()
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        return out
    }

    private val fullNames = mapOf(
        "EU" to "Europe and international", "UK" to "United Kingdom", "US" to "United States", "CA" to "Canada",
        "AU / NZ" to "Australia and New Zealand",
    )

    @Test fun `every UNITS option is a radio button in a selectable group, one selected per row - the staged one`() {
        for (region in Region.entries.mapNotNull { r -> r.units?.let { r to it } }) {
            val (r, units) = region
            val all = options(units)
            val regionOpts = all.filter { o -> Region.entries.any { it.label == o.text } }
            assertEquals("$r: region options", Region.entries.size, regionOpts.size)
            assertEquals("$r: one region group", 1, regionOpts.mapNotNull { it.groupId }.distinct().size)
            assertEquals("$r: selected region", listOf(r.label), regionOpts.filter { it.selected == true }.map { it.text })
            // The unit rows' groups in tree order (several rows share option labels: psi / bar / kPa)
            val regionGroup = regionOpts.first().groupId
            val groups = all.mapNotNull { it.groupId }.distinct().filter { it != regionGroup }
            assertEquals("$r: one group per unit row", UNIT_ROWS.size, groups.size)
            for ((row, g) in UNIT_ROWS.zip(groups)) {
                val opts = all.filter { it.groupId == g }
                assertEquals("$r / ${row.label}: options", row.options.map { it.second }, opts.map { it.text })
                val chosen = row.options.first { it.first == row.get(units) }.second
                assertEquals("$r / ${row.label}: selected", listOf(chosen), opts.filter { it.selected == true }.map { it.text })
            }
            all.forEach { o ->
                assertEquals("$r / ${o.text}: role", Role.RadioButton, o.role)
                assertTrue("$r / ${o.text}: no selected state", o.selected != null)
                assertTrue("$r / ${o.text}: not in a selectable group", o.inGroup)
            }
        }
    }

    @Test fun `Custom is the one selected region when the units match no preset`() {
        val custom = Region.EUROPE.units!!.copy(torque = Region.US.units!!.torque)
        val regionOpts = options(custom).filter { o -> Region.entries.any { it.label == o.text } }
        assertEquals(listOf(Region.CUSTOM.label), regionOpts.filter { it.selected == true }.map { it.text })
    }

    @Test fun `short region names are read as their full names`() {
        val regionOpts = options(Region.US.units!!).filter { o -> Region.entries.any { it.label == o.text } }
        for ((short, full) in fullNames) {
            assertEquals(short, full, regionOpts.single { it.text == short }.description)
        }
        // A unit option keeps its own text as what is read (no description)
        val torque = options(Region.US.units!!).filter { it.text == UnitText.LBFT }
        assertTrue(torque.isNotEmpty() && torque.all { it.description == null })
    }
}
