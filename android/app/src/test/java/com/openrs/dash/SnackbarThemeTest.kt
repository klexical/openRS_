package com.openrs.dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.openrs.dash.ui.AppSnackbarHost
import com.openrs.dash.ui.ThemeMode
import com.openrs.dash.ui.setThemeMode
import org.junit.After
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
 * #368: the app's message bar (MainActivity's host, [AppSnackbarHost]) takes the theme's raised surface and ink in NIGHT,
 * DAY and ULTRA; before, it drew Material's default inverse surface, a light box on the dark themes. Read from the drawn
 * pixels: beside the message text (the bar's own padding) and inside the glyphs. Expected values are the palette table in
 * `.claude/rules/ui-compose.md` ("Theme Colors"): Surf3 and Ink.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class SnackbarThemeTest {
    private fun idleFrames(n: Int) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    @After fun reset() {                     // the theme is process-wide Compose state: restore it and drain its work
        setThemeMode(ThemeMode.NIGHT)
        idleFrames(8)
    }

    private class Shot(val bmp: Bitmap, val text: Rect, val d: Float)

    private fun render(theme: ThemeMode): Shot {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        setThemeMode(theme)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        try {
            val state = SnackbarHostState()
            activity.setContent {
                LaunchedEffect(Unit) { state.showSnackbar(MESSAGE) }
                Box(Modifier.fillMaxSize()) { AppSnackbarHost(state, Modifier.align(Alignment.BottomCenter)) }
            }
            idleFrames(40)                   // the bar's enter animation settles
            val root: View = activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(full))
            val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val loc = IntArray(2).also { compose.getLocationInWindow(it) }
            val bmp = Bitmap.createBitmap(full, 0, loc[1], full.width, compose.height)
            full.recycle()
            var text: Rect? = null
            fun walk(n: SemanticsNode) {
                if (n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == MESSAGE }) text = n.boundsInRoot
                n.children.forEach { walk(it) }
            }
            walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            return Shot(bmp, text ?: error("the message is not shown"), activity.resources.displayMetrics.density)
        } finally {
            controller.pause().stop().destroy(); idleFrames(8)
        }
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private fun check(theme: ThemeMode, surf3: Int, ink: Int) {
        val s = render(theme)
        // 8 dp left of the text, on its vertical centre: inside the bar's 16 dp start padding
        val bar = s.bmp.getPixel((s.text.left - 8 * s.d).toInt(), s.text.center.y.toInt())
        assertEquals("$theme bar", hex(surf3), hex(bar))
        val glyphs = buildSet {
            for (x in s.text.left.toInt() until s.text.right.toInt()) for (y in s.text.top.toInt() until s.text.bottom.toInt())
                add(s.bmp.getPixel(x, y) and 0xFFFFFF)
        }
        assertTrue("$theme text ink ${hex(ink)} among the glyph pixels", (ink and 0xFFFFFF) in glyphs)
    }

    @Test fun `NIGHT - the bar is Surf3 with the ink`() = check(ThemeMode.NIGHT, 0x141B26, 0xE8F4FF)
    @Test fun `DAY - the bar is Surf3 with the ink`() = check(ThemeMode.DAY, 0xD4DCE8, 0x080D16)
    @Test fun `ULTRA - the bar is Surf3 with the ink`() = check(ThemeMode.ULTRA, 0x0C0C0C, 0xE8F4FF)

    private companion object { const val MESSAGE = "Copied 0x0304" }
}
