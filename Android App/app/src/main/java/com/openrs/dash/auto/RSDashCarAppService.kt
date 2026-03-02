package com.openrs.dash.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point.
 *
 * Declared in AndroidManifest.xml with the automotive app descriptor.
 * AA discovers this service and calls onCreateSession() when the user
 * opens the app on the head unit.
 *
 * Uses ALLOW_ALL_HOSTS_VALIDATOR for development — required when
 * sideloading via AAAD or AA Developer Mode. For Play Store release,
 * replace with specific host package validation.
 */
class RSDashCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = RSDashSession()
}
