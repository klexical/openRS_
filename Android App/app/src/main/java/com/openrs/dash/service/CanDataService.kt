package com.openrs.dash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openrs.dash.R
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.WiCanConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CanDataService : Service() {

    companion object {
        const val CHANNEL_ID = "openrs_can_channel"
        const val NOTIFICATION_ID = 1
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wican = WiCanConnection()
    private var connectionJob: Job? = null

    val connectionState: StateFlow<WiCanConnection.State> = wican.state

    inner class LocalBinder : Binder() {
        fun getService(): CanDataService = this@CanDataService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Ready"))
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    fun startConnection() {
        if (connectionJob?.isActive == true) return

        connectionJob = scope.launch {
            launch {
                wican.state.collect { state ->
                    val text = when (state) {
                        is WiCanConnection.State.Connected -> "Connected"
                        is WiCanConnection.State.Connecting -> "Connecting..."
                        is WiCanConnection.State.Disconnected -> "Disconnected"
                        is WiCanConnection.State.Error -> "Error"
                    }
                    try { updateNotification(text) } catch (_: Exception) {}
                    OpenRSDashApp.instance.vehicleState.update {
                        it.copy(isConnected = state is WiCanConnection.State.Connected)
                    }
                }
            }

            // Use hybrid mode: ATMA + OBD PID queries
            wican.connectHybrid(
                onObdUpdate = { obdState ->
                    // Merge OBD fields into current state (don't overwrite CAN fields)
                    OpenRSDashApp.instance.vehicleState.update { current ->
                        current.copy(
                            // Mode 1
                            calcLoad = obdState.calcLoad,
                            shortFuelTrim = obdState.shortFuelTrim,
                            longFuelTrim = obdState.longFuelTrim,
                            timingAdvance = obdState.timingAdvance,
                            fuelRailPressure = obdState.fuelRailPressure,
                            barometricPressure = obdState.barometricPressure,
                            commandedAfr = obdState.commandedAfr,
                            o2Voltage = obdState.o2Voltage,
                            afrSensor1 = obdState.afrSensor1,
                            // Mode 22 — PCM temps
                            chargeAirTempC = obdState.chargeAirTempC,
                            manifoldChargeTempC = obdState.manifoldChargeTempC,
                            octaneAdjustRatio = obdState.octaneAdjustRatio,
                            catalyticTempC = obdState.catalyticTempC,
                            // Mode 22 — AFR
                            afrActual = obdState.afrActual,
                            afrDesired = obdState.afrDesired,
                            lambdaActual = obdState.lambdaActual,
                            // Mode 22 — ETC / TIP / WGDC
                            etcAngleActual = obdState.etcAngleActual,
                            etcAngleDesired = obdState.etcAngleDesired,
                            tipActualKpa = obdState.tipActualKpa,
                            tipDesiredKpa = obdState.tipDesiredKpa,
                            wgdcDesired = obdState.wgdcDesired,
                            // Mode 22 — VCT
                            vctIntakeAngle = obdState.vctIntakeAngle,
                            vctExhaustAngle = obdState.vctExhaustAngle,
                            // Mode 22 — Oil / Knock
                            oilLifePct = obdState.oilLifePct,
                            ignCorrCyl1 = obdState.ignCorrCyl1,
                            // Mode 22 — TPMS (BCM)
                            tirePressLF = obdState.tirePressLF,
                            tirePressRF = obdState.tirePressRF,
                            tirePressLR = obdState.tirePressLR,
                            tirePressRR = obdState.tirePressRR,
                            tireTempLF = obdState.tireTempLF,
                            tireTempRF = obdState.tireTempRF,
                            tireTempLR = obdState.tireTempLR,
                            tireTempRR = obdState.tireTempRR,
                        )
                    }
                },
                getCurrentState = { OpenRSDashApp.instance.vehicleState.value }
            ).collect { (canId, data) ->
                val current = OpenRSDashApp.instance.vehicleState.value
                val updated = CanDecoder.decode(canId, data, current)
                if (updated != null) {
                    OpenRSDashApp.instance.vehicleState.value = updated
                        .withPeaksUpdated()
                        .copy(framesPerSecond = wican.fps)
                }
            }
        }
    }

    fun stopConnection() {
        connectionJob?.cancel()
        connectionJob = null
        OpenRSDashApp.instance.vehicleState.update { it.copy(isConnected = false) }
        try { updateNotification("Disconnected") } catch (_: Exception) {}
    }

    fun resetPeaks() {
        OpenRSDashApp.instance.vehicleState.update { it.withPeaksReset() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "RS Dash CAN Data", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "WiCAN connection status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RS Dash")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true).setSilent(true).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
