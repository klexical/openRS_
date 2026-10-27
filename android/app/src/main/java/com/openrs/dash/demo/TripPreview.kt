package com.openrs.dash.demo

import android.location.Location
import com.openrs.dash.BuildConfig
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.DriveThermalRecord
import com.openrs.dash.data.DriveThermalSensor
import com.openrs.dash.data.ThermalSegment
import com.openrs.dash.ui.ThermalProfile
import com.openrs.dash.ui.driveLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DEBUG BUILDS ONLY — sample TRIP states for design review: populated history, a selected drive, the
 * recording / paused controls and the post-drive summary. Every value lives in memory: TripPage reads
 * these instead of Room while a [Mode] is set, and its write paths (delete, rename, tags, export,
 * bookmark, recorder start / pause / stop) do nothing in preview. Sample ids are negative, so they can
 * never match a Room row. Nothing here touches DriveRecorder, so demo's no-recording rule still holds.
 *
 * `adb shell am start --activity-single-top -n com.openrs.dash.debug/com.openrs.dash.ui.MainActivity
 *   --es trippreview history|selected|recording|paused|summary|compare|replay|export|off`
 * `summary:N` opens sample drive N's summary (1 Custom · 2 STREET → RACE · 3 TRACK · 4 legacy record · 5 none).
 */
object TripPreview {
    val AVAILABLE: Boolean = BuildConfig.DEBUG

    enum class Mode { HISTORY, SELECTED, RECORDING, PAUSED, SUMMARY, COMPARE, REPLAY, EXPORT }

    private val _mode = MutableStateFlow<Mode?>(null)
    val mode: StateFlow<Mode?> = _mode.asStateFlow()

    fun set(mode: Mode?) { if (AVAILABLE) _mode.value = mode }

    /** Sample drive the SUMMARY mode opens (negative id). */
    @Volatile var summaryDriveId: Long = -1L
        private set

    /** Parses the intent extra; returns false for an unknown value. */
    fun apply(arg: String): Boolean {
        if (arg.startsWith("summary:")) {
            val n = arg.substringAfter(':').toLongOrNull()?.takeIf { it in 1..5 } ?: return false
            summaryDriveId = -n; set(Mode.SUMMARY); return true
        }
        if (arg == "summary") summaryDriveId = -1L
        val m = when (arg) {
            "off", "exit" -> null
            else -> Mode.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) } ?: return false
        }
        set(m); return true
    }

    // ── Sample data ─────────────────────────────────────────────────────────

    private const val DAY_MS = 86_400_000L
    private const val HOME_LAT = 37.4220
    private const val HOME_LNG = -122.0841

    /** Thermal record (DriveThermalRecord) for a sample drive: one segment per (profile, limits, peaks). */
    private fun thermal(start: Long, vararg segs: Triple<String, ThermalProfile, Map<DriveThermalSensor, Double>>): String =
        DriveThermalRecord.encode(segs.mapIndexed { i, (id, p, peaks) ->
            ThermalSegment(id, start + i * 10 * 60_000L, p.driveLimits(), peaks)
        })

    /**
     * Five past drives across today, yesterday, this week and last week. Thermal records cover each basis:
     * Custom (oil flag under its own 124 °C limit), STREET → RACE mid-drive (peak under STREET, flag from RACE), TRACK
     * (oil critical, RDU flag, coolant just under the 95 % line), a legacy fixed-limit array, and none (temps, no record).
     */
    fun drives(now: Long = System.currentTimeMillis()): List<DriveEntity> {
        val today = now - (now % DAY_MS) + 7 * 3_600_000L      // ~07:00 UTC-ish anchor; only relative dates matter
        fun d(id: Long, start: Long, mins: Int, km: Double, block: (DriveEntity) -> DriveEntity) =
            block(DriveEntity(id = id, startTime = start, endTime = start + mins * 60_000L, distanceKm = km,
                avgSpeedKph = km / (mins / 60.0)))
        return listOf(
            d(-1, minOf(today + 2 * 3_600_000L, now - 3_600_000L), 27, 31.4) {
                it.copy(name = "Morning loop", maxSpeedKph = 148.0, peakRpm = 6400, peakBoostPsi = 21.8,
                    peakOilTempC = 118.0, peakCoolantTempC = 98.0, peakLateralG = 0.92, fuelUsedL = 3.1,
                    startFuelPct = 72.0, tags = "SPIRITED", aggressionScore = 72, avgFuelL100km = 9.9,
                    avgFuelMpg = 23.8, driveModeBreakdown = """{"Normal":0.4,"Sport":0.6}""",
                    weatherSummary = "18°C clear", pullCount = 6, knockPullCount = 1,
                    startOilTempC = 24.0, endOilTempC = 104.0, startCoolantTempC = 26.0, endCoolantTempC = 92.0,
                    thermalPeaksJson = thermal(it.startTime, Triple("custom",
                        ThermalProfile.STREET.copy(oilWarnC = 112.0, oilCritC = 124.0),
                        mapOf(DriveThermalSensor.OIL to 118.0, DriveThermalSensor.COOLANT to 98.0))))
            },
            d(-2, now - 5 * 3_600_000L, 14, 9.8) {
                it.copy(maxSpeedKph = 88.0, peakRpm = 3900, peakBoostPsi = 9.4, peakOilTempC = 122.0,
                    peakCoolantTempC = 99.0, peakLateralG = 0.31, fuelUsedL = 0.9, startFuelPct = 74.0,
                    aggressionScore = 18, avgFuelL100km = 9.2, avgFuelMpg = 25.6,
                    driveModeBreakdown = """{"Normal":1.0}""", pullCount = 0, knockPullCount = 0,
                    startOilTempC = 40.0, endOilTempC = 101.0, startCoolantTempC = 45.0, endCoolantTempC = 90.0,
                    // Peak 122 °C under STREET (warn, no flag); after the switch to RACE, 106 °C raises the flag
                    // (≥ 104.5 °C = 95 % of RACE's 110 °C) — the summary keeps the peak and names the RACE reading
                    thermalPeaksJson = thermal(it.startTime,
                        Triple("street", ThermalProfile.STREET, mapOf(DriveThermalSensor.OIL to 122.0, DriveThermalSensor.COOLANT to 99.0)),
                        Triple("race", ThermalProfile.RACE, mapOf(DriveThermalSensor.OIL to 106.0, DriveThermalSensor.COOLANT to 95.0))))
            },
            d(-3, now - DAY_MS - 2 * 3_600_000L, 42, 58.6) {
                it.copy(name = "Track day — session 2", maxSpeedKph = 196.0, peakRpm = 6800, peakBoostPsi = 23.1,
                    peakOilTempC = 131.0, peakCoolantTempC = 104.0, peakLateralG = 1.18, fuelUsedL = 8.7,
                    startFuelPct = 90.0, tags = "TRACK", aggressionScore = 94, avgFuelL100km = 14.8,
                    avgFuelMpg = 15.9, driveModeBreakdown = """{"Track":0.85,"Sport":0.15}""",
                    weatherSummary = "24°C sunny", pullCount = 31, knockPullCount = 4,
                    thermalPeaksJson = thermal(it.startTime, Triple("track", ThermalProfile.TRACK, mapOf(
                        DriveThermalSensor.OIL to 131.0, DriveThermalSensor.COOLANT to 104.0,
                        DriveThermalSensor.PTU to 88.0, DriveThermalSensor.RDU to 92.0))),
                    startOilTempC = 31.0, endOilTempC = 112.0, startCoolantTempC = 33.0, endCoolantTempC = 99.0)
            },
            d(-4, now - 3 * DAY_MS, 21, 17.2) {
                it.copy(maxSpeedKph = 112.0, peakRpm = 5200, peakBoostPsi = 15.6, peakOilTempC = 136.0,
                    peakCoolantTempC = 95.0, peakLateralG = 0.58, fuelUsedL = 1.8, startFuelPct = 61.0,
                    aggressionScore = 41, avgFuelL100km = 10.5, avgFuelMpg = 22.4,
                    driveModeBreakdown = """{"Normal":0.7,"Sport":0.3}""",
                    startOilTempC = 35.0, endOilTempC = 134.0, startCoolantTempC = 38.0, endCoolantTempC = 94.0,
                    // Legacy record (pre-profile recorder): drive-end readings against the old fixed limits
                    thermalPeaksJson = """[{"sensor":"Oil","peakC":134.0,"climbRateCPerMin":0.4,"critThresholdC":140.0},""" +
                        """{"sensor":"Coolant","peakC":94.0,"climbRateCPerMin":0.1,"critThresholdC":115.0}]""")
            },
            d(-5, now - 9 * DAY_MS, 33, 24.0) {
                it.copy(name = "Imported session", hasGps = false, maxSpeedKph = 104.0, peakRpm = 4800,
                    peakBoostPsi = 12.2, aggressionScore = 30,
                    // Temperatures but no thermal record: no badge, no limit colours
                    startOilTempC = 52.0, peakOilTempC = 112.0, endOilTempC = 104.0,
                    startCoolantTempC = 60.0, peakCoolantTempC = 96.0, endCoolantTempC = 92.0)
            },
        )
    }

    /** A loop around the emulator's default location; speed / rpm / boost follow the lap. */
    fun points(driveId: Long, count: Int = 240, startTime: Long = System.currentTimeMillis() - 30 * 60_000L,
               startFuelPct: Double = 72.0, fuelUsedPct: Double = 6.0): List<DrivePointEntity> {
        if (driveId == -5L) return emptyList()                       // the "no GPS" sample
        val scale = 1.0 + (-driveId % 3) * 0.25
        return List(count) { i ->
            val t = i.toDouble() / count
            val a = 2 * PI * t
            val speed = 55 + 45 * sin(4 * a) + 15 * sin(11 * a)
            val rpm = (2400 + speed * 28 + 600 * sin(9 * a)).toInt()
            val boost = (speed - 60) / 3.0 + 4 * sin(13 * a)
            // Boost-sweep inputs (TIP actual / desired kPa absolute, WGDC %) under load. The track-day sample (-3)
            // runs the wastegate to saturation near peak so the summary's WGDC ≥ 95 % state can be reviewed.
            val loaded = boost > 2.0
            val targetPsi = boost + 0.8 + 0.6 * sin(5 * a)
            val wgdc = (18 + boost * 3.6 + if (driveId == -3L) 14 else 0).coerceIn(0.0, 98.0)
            DrivePointEntity(
                driveId = driveId, timestamp = startTime + i * 5_000L,
                lat = HOME_LAT + 0.011 * scale * sin(a), lng = HOME_LNG + 0.016 * scale * cos(a) * (1 + 0.2 * sin(3 * a)),
                speedKph = speed.coerceAtLeast(0.0), rpm = rpm, gear = "${(speed / 25).toInt().coerceIn(1, 6)}",
                boostPsi = boost, coolantTempC = 88.0 + 6 * t, oilTempC = 92.0 + 18 * t, ambientTempC = 18.0,
                fuelLevelPct = startFuelPct - fuelUsedPct * t, lateralG = 0.6 * sin(7 * a), throttlePct = (speed / 1.4).coerceIn(0.0, 100.0),
                driveMode = if (t < 0.4) "Normal" else "Sport",
                wheelSpeedFL = speed, wheelSpeedFR = speed + 0.4, wheelSpeedRL = speed - 0.2, wheelSpeedRR = speed + 0.1,
                tipActualKpa = if (loaded) 101.325 + boost / 0.14503773 else 0.0,
                boostTargetKpa = if (loaded) 101.325 + targetPsi / 0.14503773 else 0.0,
                wgdcPct = if (loaded) wgdc else 0.0,
            )
        }
    }

    /** Recording in progress (or paused) — 18 minutes in, partway round the loop. */
    fun driveState(paused: Boolean, now: Long = System.currentTimeMillis()): DriveState {
        val start = now - 18 * 60_000L
        val pts = points(-100, count = 160, startTime = start, startFuelPct = 66.0, fuelUsedPct = 4.0)
        val last = pts.last()
        return DriveState(
            isRecording = true, isPaused = paused, driveId = -100,
            recentPoints = pts, totalPointCount = pts.size, startFuelPct = 66.0, startTime = start,
            currentLocation = Location("trip-preview").apply {
                latitude = last.lat; longitude = last.lng; bearing = 72f; accuracy = 4f; altitude = 21.0
            },
            cumulativeDistanceKm = 21.6,
            rpmSum = pts.sumOf { it.rpm.toDouble() }, rpmSamples = pts.size.toLong(),
            speedSum = pts.sumOf { it.speedKph }, speedSamples = pts.size.toLong(),
            maxSpeedKph = pts.maxOf { it.speedKph }, peakRpm = pts.maxOf { it.rpm }.toDouble(),
            peakBoostPsi = pts.maxOf { it.boostPsi }, peakLateralG = 0.61,
            pullCount = 3, knockPullCount = 0,
        )
    }
}
