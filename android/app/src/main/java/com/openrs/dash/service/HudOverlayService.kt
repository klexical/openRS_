package com.openrs.dash.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.R
import com.openrs.dash.ui.UserPrefsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Floating HUD overlay that shows boost, RPM, and oil temp on top of any app.
 *
 * Designed for track-day use when running a separate navigation app.
 * Piggybacks on [CanDataService]'s existing foreground notification rather
 * than starting its own — keeps the overlay lightweight.
 */
class HudOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var boostTv: TextView
    private lateinit var rpmTv: TextView
    private lateinit var oilTv: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        startUpdating()
    }

    override fun onDestroy() {
        scope.cancel()
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        super.onDestroy()
    }

    // ── Overlay construction ─────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val dp = { value: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
        }

        val mono: Typeface = ResourcesCompat.getFont(this, R.font.share_tech_mono)
            ?: Typeface.MONOSPACE

        // ── Background shape ─────────────────────────────────────────────
        val bg = GradientDrawable().apply {
            setColor(0xE005070A.toInt())                     // semi-transparent dark
            setStroke(dp(1f), 0xFF162030.toInt())             // border
            cornerRadius = dp(8f).toFloat()
        }

        // ── Root container ───────────────────────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(10f), dp(6f), dp(10f), dp(8f))
        }

        // ── Close button row ─────────────────────────────────────────────
        val closeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val closeBtn = TextView(this).apply {
            text = "\u2715"  // ✕
            setTextColor(0xFF547A96.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = mono
            setPadding(dp(6f), 0, 0, 0)
            setOnClickListener { stopSelf() }
        }
        closeRow.addView(closeBtn)
        root.addView(closeRow)

        // ── Data rows ────────────────────────────────────────────────────
        fun makeLabel(initialText: String): TextView = TextView(this).apply {
            text = initialText
            setTextColor(0xFFE8F4FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = mono
            includeFontPadding = false
        }

        boostTv = makeLabel("BST:  --.- PSI")
        rpmTv   = makeLabel("RPM:  ----")
        oilTv   = makeLabel("OIL:  ---\u00B0")

        root.addView(boostTv)
        root.addView(rpmTv)
        root.addView(oilTv)

        // ── WindowManager params ─────────────────────────────────────────
        val params = WindowManager.LayoutParams(
            dp(160f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16f)
            y = dp(80f)
        }

        // ── Drag support ─────────────────────────────────────────────────
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(root, params)
        overlayView = root
    }

    // ── Live data feed ───────────────────────────────────────────────────────

    private fun startUpdating() {
        scope.launch {
            combine(
                OpenRSDashApp.instance.vehicleState,
                UserPrefsStore.prefs
            ) { vs, prefs -> vs to prefs }
                .collect { (vs, prefs) ->
                    // Boost — respect user's preferred unit
                    val (bVal, bUnit) = prefs.displayBoost(vs.boostKpa)
                    boostTv.text = "BST: %s %s".format(bVal, bUnit)

                    // RPM
                    rpmTv.text = "RPM: %d".format(vs.rpm.toInt())

                    // Oil temp — respect user's preferred unit
                    val oilText = if (vs.oilTempC <= -99.0) "---"
                                  else prefs.displayTemp(vs.oilTempC)
                    oilTv.text = "OIL: %s%s".format(oilText, prefs.tempLabel)

                    delay(250) // throttle to ~4 Hz
                }
        }
    }
}
