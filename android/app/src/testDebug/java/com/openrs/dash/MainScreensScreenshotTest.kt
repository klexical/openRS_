package com.openrs.dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.demo.DemoSimulator
import com.openrs.dash.ui.AppHeader
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.BottomNavBar
import com.openrs.dash.ui.DrivePage
import com.openrs.dash.ui.GaragePage
import com.openrs.dash.ui.LinkUi
import com.openrs.dash.ui.LocalThemeAccent
import com.openrs.dash.ui.PerfPage
import com.openrs.dash.ui.SettingsDialog
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.TempsPage
import com.openrs.dash.ui.ThermalReadinessSession
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.UserPrefsStore
import com.openrs.dash.ui.WhatsNewDialog
import com.openrs.dash.ui.legibleAccent
import com.openrs.dash.ui.trip.TripPage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.TimeZone

/**
 * Screenshot tests of the main screens at 360 dp (the smallest phone width the layout is checked at), at the phone's font
 * size 1.0, 1.3 and 2.0: DRIVE, PERF, THERMAL, TRIP, GARAGE (CONTROL and DIAG), Settings (its first page and
 * UNITS) and What's New, each under
 * the app's header and bottom tab bar as MainActivity lays them out. Each image is the screen as it opens (no scrolling).
 *
 * Every image is built from demo data only: [DemoSimulator] frames (a pure function of scenario and time) with the demo
 * VIN removed; no recorded drive, VIN, position or device name, and the drive database is empty. The goldens live in
 * `src/testDebug/screenshots/`. `./gradlew test` compares against them (Roborazzi verify, `roborazzi.test.verify` in
 * gradle.properties) and fails on a changed screen; after an intended layout change re-record with
 * `./gradlew recordRoborazziDebug --tests '*MainScreensScreenshotTest*'` on Linux (CI's runner renders the same pixels as
 * a Linux host; another OS's native graphics differ) and commit the new PNGs with the change.
 *
 * Debug variant only (`src/testDebug/`): demo data and the What's New entry are debug-build paths, and the release unit
 * tests would draw a different Settings (no demo section).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi", application = OpenRSDashApp::class)
class MainScreensScreenshotTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font {0}")
        fun scales(): List<Array<Any>> = listOf(arrayOf(1.0f), arrayOf(1.3f), arrayOf(2.0f))

        /** A bound on the images per screen: a page that scrolls further than this many screenfuls fails loudly. */
        private const val MAX_PAGES = 12

        /** Mid grey: a masked text reads as a plain bar on every theme. */
        private const val MASK = 0xFF808080.toInt()
    }

    /**
     * The goldens, saved at a third of the xxhdpi pixels: 360 × 720, one pixel per dp, to keep the repository small (a
     * layout change rewrites every image it shows in). Rendered at xxhdpi first, so text is measured as on a phone.
     */
    private val options get() = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 1.0 / 3),
    )

    /** The raw screen: full size, always written, never compared ([screen]). */
    private val rawOptions get() = RoborazziOptions(
        taskType = RoborazziTaskType.Record,
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 1.0),
    )

    private var savedTz: TimeZone? = null
    private var savedLocale: Locale? = null

    @Before fun setUp() {
        // Dates, times and number formats: the same on every machine
        savedTz = TimeZone.getDefault(); savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC")); Locale.setDefault(Locale.US)
        RuntimeEnvironment.setFontScale(fontScale)
        UserPrefsStore.resetForTest()
    }

    @After fun tearDown() {
        OpenRSDashApp.instance.liveTelemetry.showForTest(VehicleState())
        RuntimeEnvironment.setFontScale(1f)
        UserPrefsStore.resetForTest()
        RuntimeEnvironment.getApplication().getSharedPreferences("openrs_settings", 0).edit().clear().commit()
        savedTz?.let { TimeZone.setDefault(it) }; savedLocale?.let { Locale.setDefault(it) }
        idle(8)
    }

    private fun idle(frames: Int) = repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private val font get() = "font" + fontScale.toString().replace('.', '_')

    /**
     * A demo frame: [scenario] at [tMs] into its loop, every freshness tag stamped now. Without the demo VIN: no image
     * carries a VIN, even the simulator's made-up one.
     */
    private fun demo(scenario: DemoScenario, tMs: Long): VehicleState = DemoSimulator.apply(
        VehicleState(), scenario, tMs, DriveMode.SPORT, EscStatus.fromInt(0), System.currentTimeMillis(), stamp = true,
    ).copy(vin = "")

    /** MainActivity's frame: header, the page, the bottom tab bar over it. */
    @Composable private fun Frame(tab: Int, vs: VehicleState, page: @Composable () -> Unit) {
        val prefs = UserPrefs()
        val accent = legibleAccent(prefs.themeAccent, false)
        CompositionLocalProvider(LocalThemeAccent provides accent) {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = accent)) {
                val haze = remember { HazeState() }
                Box(Modifier.fillMaxSize().background(Bg)) {
                    Box(Modifier.fillMaxSize().background(Bg).hazeSource(haze)) {
                        Column(Modifier.fillMaxSize()) {
                            AppHeader(vs = vs, prefs = prefs, onSettings = {}, onConnect = {}, onDisconnect = {},
                                onReconnect = {}, link = if (vs.isConnected) LinkUi.LIVE else LinkUi.OFFLINE)
                            Box(Modifier.weight(1f)) { page() }
                        }
                    }
                    BottomNavBar(selected = tab, onSelect = {}, hazeState = haze, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    /**
     * Composes [content] with [vs] as the app's live state (and the thermal readiness session's input), lets entrance
     * animations finish, runs [before] (taps), and checks or records the whole screen (dialog windows included) as
     * `<name>_<font>.png`.
     */
    private fun shoot(name: String, vs: VehicleState, before: () -> Unit = {}, paged: Boolean = true,
                      content: @Composable () -> Unit) {
        OpenRSDashApp.instance.liveTelemetry.showForTest(vs)
        ThermalReadinessSession.reset()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { content() }
            settle()
            before()
            settle()
            // The screen as it opens, then (font 1.0 and 1.3) one image per further screenful down to the end of what scrolls.
            // Font 2.0 keeps the first screenful only: the repository holds every image, and each layout change rewrites them
            var page = 1
            while (true) {
                val file = if (page == 1) "${name}_$font" else "${name}_p${page}_$font"
                val shot = screen(file)
                val paint = Paint().apply { color = MASK }
                Canvas(shot).apply {
                    val r = Rect()
                    masks().forEach { region -> RegionIterator(region).let { while (it.next(r)) drawRect(r, paint) } }
                }
                shot.captureRoboImage("src/testDebug/screenshots/$file.png", options)
                if (!paged || fontScale > 1.3f || !scrollOnePage()) break
                check(++page <= MAX_PAGES) { "$name at font $fontScale scrolls past $MAX_PAGES screenfuls" }
            }
        } finally {
            controller.pause().stop().destroy()
            idle(8)
        }
    }

    /**
     * Entrance animations (≤ 1 s of the test clock), then work answered from IO threads in real time (PERF's and DIAG's Room
     * reads of the empty drive database) until the main looper has had half a second of it.
     */
    private fun settle() {
        idle(80)
        repeat(10) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        idle(10)
    }

    /** The whole screen at full size, always written (never compared), so the masks can be drawn on it. */
    private fun screen(name: String): Bitmap {
        val raw = File("build/tmp/screenshots-raw/${name}_$font.png").apply { parentFile?.mkdirs() }
        captureScreenRoboImage(raw, rawOptions)
        return BitmapFactory.decodeFile(raw.path).copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * What changes from run to run and says nothing about layout, painted over in screen pixels (each box keeps its size):
     * - the debug build's source revision, new with every commit, wherever a screen shows it (Settings' ABOUT line draws a
     *   fixed label instead, [pinnedVersion]);
     * - the header's LIVE pill: its dot pulses for ever, and how far it has got when the image is taken depends on how long
     *   Robolectric waits for background work first (seen on pages after a scroll), so it differed between runs.
     * A box covers only what shows of its window: a dialog drawn over it hides the part under the dialog's content (What's
     * New's title sits over the header's LIVE pill), and only what shows of the pill around Settings is painted over.
     */
    private fun masks(): List<Region> {
        val commit = com.openrs.dash.update.BuildInfo.sourceCommit.take(7)
        val out = mutableListOf<Region>()
        // Each window's content on screen, bottom window first (the order the windows were added)
        val covers = mutableListOf<Rect>()
        val boxes = mutableListOf<Pair<Int, Rect>>()
        for (window in windows()) {
            val root = composeRoot(window) ?: continue
            // Where the screen image puts this window: placed by its gravity on the display (a dialog is centred)
            val lp = window.layoutParams as? android.view.WindowManager.LayoutParams
            val dm = window.resources.displayMetrics
            val frame = Rect()
            android.view.Gravity.apply(lp?.gravity ?: 0, window.width, window.height,
                Rect(0, 0, dm.widthPixels, dm.heightPixels), lp?.x ?: 0, lp?.y ?: 0, frame)
            fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                ?: n.children.firstNotNullOfOrNull { textOf(it) }
            fun walk(n: SemanticsNode) {
                val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                val livePill = n.config.getOrNull(SemanticsActions.OnClick) != null && textOf(n) == LinkUi.LIVE.pill
                if ((text != null && commit.length == 7 && commit in text) || livePill) {
                    val b = n.boundsInWindow
                    boxes += covers.size to Rect(frame.left + b.left.toInt(), frame.top + b.top.toInt(),
                        frame.left + kotlin.math.ceil(b.right).toInt(), frame.top + kotlin.math.ceil(b.bottom).toInt())
                }
                n.children.forEach { walk(it) }
            }
            val content = root.semanticsOwner.unmergedRootSemanticsNode
            walk(content)
            val c = content.boundsInWindow
            covers += Rect(frame.left + c.left.toInt(), frame.top + c.top.toInt(),
                frame.left + c.right.toInt(), frame.top + c.bottom.toInt())
        }
        for ((index, box) in boxes) {
            val region = Region(box)
            // The window that holds the box is covers[index]; every window after it is drawn over it
            for (above in covers.drop(index + 1)) region.op(above, Region.Op.DIFFERENCE)
            if (!region.isEmpty) out += region
        }
        return out
    }

    /** Every attached window's root view: the activity's, and each dialog's (a dialog is a window of its own). */
    private fun windows(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        return (wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<View>)
            .filter { it.isAttachedToWindow }
    }

    private fun composeRoot(v: View): ViewRootForTest? = v as? ViewRootForTest
        ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { composeRoot(g.getChildAt(it)) } }

    /** Taps the clickable whose text starts with [text], in the topmost window that has one (a dialog over the page). */
    private fun clickText(text: String) {
        fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            ?: n.children.firstNotNullOfOrNull { textOf(it) }
        fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { all(it) }
        val (window, node) = windows().asReversed().firstNotNullOf { w ->
            composeRoot(w)?.let { root ->
                all(root.semanticsOwner.unmergedRootSemanticsNode).firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick) != null && textOf(it)?.startsWith(text) == true
                }?.let { w to it }
            }
        }
        node.config[SemanticsActions.OnClick].action!!.invoke()
        idle(40)
        // A dialog window gets no layout pass of its own here after a recomposition: lay it out again
        relayout(window)
    }

    /**
     * Scrolls the topmost window's largest vertical scroller (the page, a Settings category, What's New's list) down by
     * its own height less a 48 dp overlap, so a row cut at one image's bottom edge is whole in the next. False when it is
     * already at its end, or nothing scrolls. A layout pass follows: a dialog window gets none of its own here.
     */
    private fun scrollOnePage(): Boolean {
        fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { all(it) }
        val (window, node) = windows().asReversed().firstNotNullOfOrNull { w ->
            composeRoot(w)?.let { root ->
                all(root.semanticsOwner.unmergedRootSemanticsNode)
                    .filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                        it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                    .maxByOrNull { it.size.height }?.let { w to it }
            }
        } ?: return false
        val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
        if (range.value() >= range.maxValue()) return false
        val overlap = 48 * window.resources.displayMetrics.density
        node.config[SemanticsActions.ScrollBy].action!!.invoke(0f, node.size.height - overlap)
        idle(40)   // the scroll is animated
        relayout(window)
        idle(4)
        return true
    }

    private fun relayout(window: View) {
        (composeRoot(window) as? View)?.requestLayout()   // without it the same measure specs measure nothing again
        window.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(window.height, View.MeasureSpec.EXACTLY))
        window.layout(window.left, window.top, window.right, window.bottom)
    }

    private val driving get() = demo(DemoScenario.DRIVING, 9_500)   // mid-pull, 3rd gear
    private val warnings get() = demo(DemoScenario.WARNING, 50_000) // every alert on

    @Test fun drive() = shoot("drive", driving) {
        Frame(0, driving) { DrivePage(vs = driving, p = UserPrefs(), link = LinkUi.LIVE) }
    }

    @Test fun perf() = shoot("perf", driving) {
        Frame(1, driving) { PerfPage(driving, UserPrefs(), onReset = {}) }
    }

    @Test fun thermal() = shoot("thermal", warnings) {
        Frame(2, warnings) { TempsPage(warnings, UserPrefs()) }
    }

    /** TRIP as it opens with no drive recording: the map's place, its controls, the live readout and START. */
    @Test fun trip() = shoot("trip", driving) {
        // settled = false: no Google map (it needs Play services); its place keeps the page's layout
        Frame(3, driving) { TripPage(DriveState(), driving, UserPrefs(), settled = false) }
    }

    @Test fun garage() = shoot("garage", driving) {
        Frame(4, driving) { GaragePage(debugLines = emptyList(), vs = driving, p = UserPrefs(), snackbarHostState = remember { SnackbarHostState() }) }
    }

    @Test fun diag() = shoot("diag", driving, before = { clickText("DIAG") }) {
        Frame(4, driving) { GaragePage(debugLines = emptyList(), vs = driving, p = UserPrefs(), snackbarHostState = remember { SnackbarHostState() }) }
    }

    /**
     * Settings' ABOUT strip with a fixed debug label: the build's own ("… · debug <commit>*") wraps beside UPDATES, so its
     * length (the dirty star) would move the strip's height between a working tree and CI (#594).
     */
    private val pinnedVersion = com.openrs.dash.update.BuildInfo.settingsLabel("2.2.7-nightly.20261123", 99, "debug", "0000000", dirty = false)

    @Test fun settings() = shoot("settings", driving) {
        Frame(0, driving) { SettingsDialog(onDismiss = {}, versionLabel = pinnedVersion) }
    }

    /** Settings → UNITS: the region presets, every unit row and the TPMS thresholds. */
    @Test fun settingsUnits() = shoot("settings_units", driving, before = { clickText("UNITS") }) {
        Frame(0, driving) { SettingsDialog(onDismiss = {}, versionLabel = pinnedVersion) }
    }

    /** What's New: the first screenful only (the list below is release-note text, one style throughout). */
    @Test fun whatsNew() = shoot("whats_new", driving, paged = false) {
        Frame(0, driving) { WhatsNewDialog(onDismiss = {}) }
    }
}
