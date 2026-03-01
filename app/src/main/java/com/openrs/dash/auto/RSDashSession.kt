package com.openrs.dash.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.openrs.dash.auto.screens.MainDashScreen

/**
 * Android Auto session.
 *
 * Each AA connection creates one session. We push MainDashScreen
 * as the initial screen. The user can navigate to AWD and Performance
 * detail screens via action buttons.
 */
class RSDashSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return MainDashScreen(carContext)
    }
}
