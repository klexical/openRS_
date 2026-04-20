package com.openrs.dash.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE HISTORY — Room entities, DAO, and database (replaces SessionDatabase)
// ═══════════════════════════════════════════════════════════════════════════

@Entity(tableName = "drives")
data class DriveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,                        // epoch millis
    val endTime: Long = 0,                      // 0 = still active
    val name: String? = null,                   // user-assigned drive name
    val hasGps: Boolean = true,                 // false for legacy migrated sessions
    val sessionId: Long = 0,                    // links to diagnostic session (for unified export)
    val distanceKm: Double = 0.0,
    val avgSpeedKph: Double = 0.0,
    val maxSpeedKph: Double = 0.0,
    val peakRpm: Int = 0,
    val peakBoostPsi: Double = 0.0,
    val peakOilTempC: Double = -99.0,
    val peakCoolantTempC: Double = -99.0,
    val peakLateralG: Double = 0.0,
    val fuelUsedL: Double = 0.0,
    val startFuelPct: Double = 0.0,
    val totalFrames: Long = 0,
    val driveModeBreakdown: String = "{}",      // JSON map: {"Normal":0.6,"Sport":0.3}
    val weatherSummary: String? = null,
    val startOilTempC: Double = -99.0,
    val endOilTempC: Double = -99.0,
    val startCoolantTempC: Double = -99.0,
    val endCoolantTempC: Double = -99.0,
    val tags: String = "",                        // comma-separated: "TRACK,SPIRITED"
    val aggressionScore: Int = 0,                 // 0-100 drive intensity rating
    // ── Fuel economy (v8) ───────────────────────────────────
    val avgFuelL100km: Double = 0.0,
    val avgFuelMpg: Double = 0.0,
    // ── Thermal peaks (v8) ──────────────────────────────────
    val thermalPeaksJson: String? = null           // JSON: per-sensor peak temps + climb rates
)

@Entity(
    tableName = "drive_points",
    foreignKeys = [ForeignKey(
        entity = DriveEntity::class,
        parentColumns = ["id"],
        childColumns = ["driveId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("driveId")]
)
data class DrivePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driveId: Long,
    val timestamp: Long,                        // epoch millis
    val lat: Double,
    val lng: Double,
    // ── Drivetrain ────────────────────────────────────────────
    val speedKph: Double = 0.0,
    val rpm: Int = 0,
    val gear: String = "",
    val boostPsi: Double = 0.0,
    // ── Temperatures ─────────────────────────────────────────
    val coolantTempC: Double = -99.0,
    val oilTempC: Double = -99.0,
    val ambientTempC: Double = -99.0,
    val rduTempC: Double = -99.0,
    val ptuTempC: Double = -99.0,
    // ── Fuel ─────────────────────────────────────────────────
    val fuelLevelPct: Double = -1.0,
    // ── Dynamics ─────────────────────────────────────────────
    val lateralG: Double = 0.0,
    val throttlePct: Double = 0.0,
    // ── Context ──────────────────────────────────────────────
    val driveMode: String = "Normal",
    // ── Wheel speeds (km/h) ──────────────────────────────────
    val wheelSpeedFL: Double = 0.0,
    val wheelSpeedFR: Double = 0.0,
    val wheelSpeedRL: Double = 0.0,
    val wheelSpeedRR: Double = 0.0,
    // ── TPMS (BCM Mode 22, polled ~30 s) ─────────────────────
    val tirePressLF: Double = -1.0,
    val tirePressRF: Double = -1.0,
    val tirePressLR: Double = -1.0,
    val tirePressRR: Double = -1.0,
    val tireTempLF: Double = -99.0,
    val tireTempRF: Double = -99.0,
    val tireTempLR: Double = -99.0,
    val tireTempRR: Double = -99.0,
    // ── Extended dynamics (v5) ───────────────────────────────
    val longitudinalG: Double = 0.0,
    val brakePressure: Double = 0.0,
    val steeringAngle: Double = 0.0,
    // ── Race readiness ───────────────────────────────────────
    val isRaceReady: Boolean = false
)

// ═══════════════════════════════════════════════════════════════════════════
// DTC HISTORY — persisted scan results for trending and comparison
// ═══════════════════════════════════════════════════════════════════════════

@Entity(tableName = "dtc_scans")
data class DtcScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,           // epoch millis
    val moduleCount: Int,          // number of modules queried
    val totalCodes: Int,           // total DTCs found
    val scanDurationMs: Long = 0,  // how long the scan took
    val moduleStatuses: String = "{}" // JSON: {"PCM":"OK","AWD":"TIMEOUT",...}
)

@Entity(
    tableName = "dtc_codes",
    foreignKeys = [ForeignKey(
        entity = DtcScanEntity::class,
        parentColumns = ["id"],
        childColumns = ["scanId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scanId"), Index("code")]
)
data class DtcCodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanId: Long,
    val module: String,
    val code: String,
    val description: String,
    val status: String,            // "ACTIVE", "PENDING", "PERMANENT", "UNKNOWN"
    val freezeFrameJson: String? = null  // reserved for freeze frame data
)

// ═══════════════════════════════════════════════════════════════════════════
// LAP TIMER — persisted lap results linked to drives
// ═══════════════════════════════════════════════════════════════════════════

@Entity(tableName = "lap_sessions")
data class LapSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driveId: Long,                      // FK to drives (not enforced — drive may be deleted)
    val startFinishLat: Double,
    val startFinishLng: Double,
    val startFinishBearing: Float,
    val createdAt: Long                     // epoch millis
)

@Entity(
    tableName = "laps",
    foreignKeys = [ForeignKey(
        entity = LapSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class LapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val lapNumber: Int,
    val lapTimeMs: Long,
    val peakRpm: Int = 0,
    val peakBoostPsi: Double = 0.0,
    val peakLateralG: Double = 0.0,
    val peakSpeedKph: Double = 0.0
)

// ═══════════════════════════════════════════════════════════════════════════
// PERFORMANCE RUNS — persisted 0-60 / 0-100 timer results
// ═══════════════════════════════════════════════════════════════════════════

@Entity(tableName = "perf_runs")
data class PerfRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driveId: Long? = null,              // FK to drives (nullable — may run without recording)
    val timestamp: Long,                    // epoch millis
    val zeroTo60Ms: Long,
    val zeroTo100Ms: Long? = null,
    val peakRpm: Double = 0.0,
    val peakBoostPsi: Double = 0.0,
    val launchRpm: Double = 0.0,
    val densityAltFt: Double? = null,
    val ambientTempC: Double = -99.0
)

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE BOOKMARKS — user-placed markers during recording
// ═══════════════════════════════════════════════════════════════════════════

@Entity(
    tableName = "drive_bookmarks",
    foreignKeys = [ForeignKey(
        entity = DriveEntity::class,
        parentColumns = ["id"],
        childColumns = ["driveId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("driveId")]
)
data class DriveBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driveId: Long,
    val timestamp: Long,                     // epoch millis
    val lat: Double,
    val lng: Double,
    val label: String = "",                  // optional user label
    val speedKph: Double = 0.0,
    val rpm: Int = 0,
    val boostPsi: Double = 0.0
)

@Dao
interface DriveDao {
    // ── Drive lifecycle ──────────────────────────────────────
    @Insert
    fun insertDrive(drive: DriveEntity): Long

    @Update
    fun updateDrive(drive: DriveEntity)

    @Query("SELECT * FROM drives WHERE id = :id")
    fun getDrive(id: Long): DriveEntity?

    @Query("SELECT * FROM drives ORDER BY startTime DESC LIMIT :limit")
    fun getRecentDrives(limit: Int = 50): List<DriveEntity>

    @Query("SELECT COUNT(*) FROM drives")
    fun getDriveCount(): Int

    @Query("DELETE FROM drives WHERE id IN (SELECT id FROM drives ORDER BY startTime ASC LIMIT :count)")
    fun deleteOldestDrives(count: Int)

    @Query("DELETE FROM drives WHERE id = :id")
    fun deleteDrive(id: Long)

    @Query("UPDATE drives SET name = :name WHERE id = :id")
    fun updateDriveName(id: Long, name: String?)

    @Query("UPDATE drives SET tags = :tags WHERE id = :id")
    fun updateDriveTags(id: Long, tags: String)

    /**
     * Drives whose endTime is still 0 — i.e. they were active (recording or
     * paused) when the app was killed or crashed. The in-memory recorder
     * state is lost at app restart, so these need to be finalized on the
     * next launch to keep "endTime == 0" meaning "truly in progress."
     */
    @Query("SELECT * FROM drives WHERE endTime = 0 ORDER BY startTime DESC")
    fun getUnfinishedDrives(): List<DriveEntity>

    /** Max point timestamp for a drive — used to close out an orphan cleanly. */
    @Query("SELECT MAX(timestamp) FROM drive_points WHERE driveId = :driveId")
    fun getLastPointTimestamp(driveId: Long): Long?

    // ── Drive points (telemetry at ~1 Hz) ────────────────────
    @Insert
    fun insertPoints(points: List<DrivePointEntity>)

    @Query("SELECT * FROM drive_points WHERE driveId = :driveId ORDER BY timestamp")
    fun getPoints(driveId: Long): List<DrivePointEntity>

    @Query("SELECT COUNT(*) FROM drive_points WHERE driveId = :driveId")
    fun getPointCount(driveId: Long): Int

    @Query("SELECT * FROM drive_points WHERE driveId = :driveId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentPoints(driveId: Long, limit: Int): List<DrivePointEntity>

    // ── Legacy session pruning (kept during migration period) ──
    @Query("DELETE FROM snapshots WHERE sessionId IN (SELECT id FROM sessions WHERE startTime < :cutoff)")
    fun deleteOldSnapshots(cutoff: Long)

    @Query("DELETE FROM sessions WHERE startTime < :cutoff")
    fun deleteOldSessions(cutoff: Long)

    // ── DTC history ────────────────────────────────────────────
    @Insert
    fun insertDtcScan(scan: DtcScanEntity): Long

    @Insert
    fun insertDtcCodes(codes: List<DtcCodeEntity>)

    @Query("SELECT * FROM dtc_scans ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentDtcScans(limit: Int = 20): List<DtcScanEntity>

    @Query("SELECT * FROM dtc_codes WHERE scanId = :scanId")
    fun getDtcCodes(scanId: Long): List<DtcCodeEntity>

    @Query("SELECT * FROM dtc_codes WHERE code = :code ORDER BY scanId DESC")
    fun getCodeHistory(code: String): List<DtcCodeEntity>

    @Query("SELECT DISTINCT code FROM dtc_codes ORDER BY code")
    fun getAllKnownCodes(): List<String>

    @Query("SELECT COUNT(*) FROM dtc_scans")
    fun getDtcScanCount(): Int

    @Query("DELETE FROM dtc_scans WHERE id IN (SELECT id FROM dtc_scans ORDER BY timestamp ASC LIMIT :count)")
    fun deleteOldestDtcScans(count: Int)

    /** Get codes from the most recent scan (for diff comparison). */
    @Query("SELECT dc.* FROM dtc_codes dc INNER JOIN dtc_scans ds ON dc.scanId = ds.id ORDER BY ds.timestamp DESC LIMIT 500")
    fun getLastScanCodes(): List<DtcCodeEntity>

    // ── Lap timer history ─────────────────────────────────────
    @Insert
    fun insertLapSession(session: LapSessionEntity): Long

    @Insert
    fun insertLaps(laps: List<LapEntity>)

    @Query("SELECT * FROM lap_sessions WHERE driveId = :driveId ORDER BY createdAt DESC")
    fun getLapSessionsForDrive(driveId: Long): List<LapSessionEntity>

    @Query("SELECT * FROM laps WHERE sessionId = :sessionId ORDER BY lapNumber")
    fun getLaps(sessionId: Long): List<LapEntity>

    @Query("SELECT MIN(lapTimeMs) FROM laps")
    fun getPersonalBestLapMs(): Long?

    @Query("SELECT * FROM laps ORDER BY lapTimeMs ASC LIMIT 1")
    fun getFastestLap(): LapEntity?

    @Query("SELECT COUNT(*) FROM laps")
    fun getTotalLapCount(): Int

    // ── Drive bookmarks ─────────────────────────────────────────
    @Insert
    fun insertBookmark(bookmark: DriveBookmarkEntity): Long

    @Query("SELECT * FROM drive_bookmarks WHERE driveId = :driveId ORDER BY timestamp ASC")
    fun getBookmarks(driveId: Long): List<DriveBookmarkEntity>

    @Query("SELECT COUNT(*) FROM drive_bookmarks WHERE driveId = :driveId")
    fun getBookmarkCount(driveId: Long): Int

    // ── Performance runs ────────────────────────────────────────
    @Insert
    fun insertPerfRun(run: PerfRunEntity): Long

    @Query("SELECT * FROM perf_runs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentPerfRuns(limit: Int = 20): List<PerfRunEntity>

    @Query("SELECT MIN(zeroTo60Ms) FROM perf_runs WHERE zeroTo60Ms > 0")
    fun getPersonalBest60Ms(): Long?

    @Query("SELECT MIN(zeroTo100Ms) FROM perf_runs WHERE zeroTo100Ms > 0")
    fun getPersonalBest100Ms(): Long?

    @Query("SELECT * FROM perf_runs WHERE zeroTo60Ms = (SELECT MIN(zeroTo60Ms) FROM perf_runs WHERE zeroTo60Ms > 0) LIMIT 1")
    fun getBestPerfRun60(): PerfRunEntity?

    @Query("SELECT * FROM perf_runs WHERE zeroTo100Ms = (SELECT MIN(zeroTo100Ms) FROM perf_runs WHERE zeroTo100Ms > 0) LIMIT 1")
    fun getBestPerfRun100(): PerfRunEntity?

    @Query("SELECT COUNT(*) FROM perf_runs")
    fun getPerfRunCount(): Int

    // ── Legacy session DAO (kept during migration period) ────
    @Insert
    fun insertSession(session: SessionEntity): Long

    @Update
    fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startTime DESC LIMIT :limit")
    fun getRecentSessions(limit: Int = 30): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun getSession(id: Long): SessionEntity?

    @Insert
    fun insertSnapshot(snapshot: SnapshotEntity)

    @Query("SELECT * FROM snapshots WHERE sessionId = :sessionId ORDER BY timestamp")
    fun getSnapshots(sessionId: Long): List<SnapshotEntity>
}

@Database(
    entities = [
        SessionEntity::class,
        SnapshotEntity::class,
        DriveEntity::class,
        DrivePointEntity::class,
        DtcScanEntity::class,
        DtcCodeEntity::class,
        LapSessionEntity::class,
        LapEntity::class,
        PerfRunEntity::class,
        DriveBookmarkEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class DriveDatabase : RoomDatabase() {
    abstract fun driveDao(): DriveDao

    companion object {
        @Volatile private var INSTANCE: DriveDatabase? = null

        /** Migration from v1 (sessions+snapshots only) → v2 (adds drives+drive_points). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create the new drives table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS drives (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER NOT NULL DEFAULT 0,
                        hasGps INTEGER NOT NULL DEFAULT 1,
                        sessionId INTEGER NOT NULL DEFAULT 0,
                        distanceKm REAL NOT NULL DEFAULT 0.0,
                        avgSpeedKph REAL NOT NULL DEFAULT 0.0,
                        maxSpeedKph REAL NOT NULL DEFAULT 0.0,
                        peakRpm INTEGER NOT NULL DEFAULT 0,
                        peakBoostPsi REAL NOT NULL DEFAULT 0.0,
                        peakOilTempC REAL NOT NULL DEFAULT -99.0,
                        peakCoolantTempC REAL NOT NULL DEFAULT -99.0,
                        peakLateralG REAL NOT NULL DEFAULT 0.0,
                        fuelUsedL REAL NOT NULL DEFAULT 0.0,
                        startFuelPct REAL NOT NULL DEFAULT 0.0,
                        totalFrames INTEGER NOT NULL DEFAULT 0,
                        driveModeBreakdown TEXT NOT NULL DEFAULT '{}',
                        weatherSummary TEXT
                    )
                """.trimIndent())

                // Create the new drive_points table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS drive_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        driveId INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        speedKph REAL NOT NULL DEFAULT 0.0,
                        rpm INTEGER NOT NULL DEFAULT 0,
                        gear TEXT NOT NULL DEFAULT '',
                        boostPsi REAL NOT NULL DEFAULT 0.0,
                        coolantTempC REAL NOT NULL DEFAULT -99.0,
                        oilTempC REAL NOT NULL DEFAULT -99.0,
                        ambientTempC REAL NOT NULL DEFAULT -99.0,
                        rduTempC REAL NOT NULL DEFAULT -99.0,
                        ptuTempC REAL NOT NULL DEFAULT -99.0,
                        fuelLevelPct REAL NOT NULL DEFAULT -1.0,
                        lateralG REAL NOT NULL DEFAULT 0.0,
                        throttlePct REAL NOT NULL DEFAULT 0.0,
                        driveMode TEXT NOT NULL DEFAULT 'Normal',
                        wheelSpeedFL REAL NOT NULL DEFAULT 0.0,
                        wheelSpeedFR REAL NOT NULL DEFAULT 0.0,
                        wheelSpeedRL REAL NOT NULL DEFAULT 0.0,
                        wheelSpeedRR REAL NOT NULL DEFAULT 0.0,
                        tirePressLF REAL NOT NULL DEFAULT -1.0,
                        tirePressRF REAL NOT NULL DEFAULT -1.0,
                        tirePressLR REAL NOT NULL DEFAULT -1.0,
                        tirePressRR REAL NOT NULL DEFAULT -1.0,
                        tireTempLF REAL NOT NULL DEFAULT -99.0,
                        tireTempRF REAL NOT NULL DEFAULT -99.0,
                        tireTempLR REAL NOT NULL DEFAULT -99.0,
                        tireTempRR REAL NOT NULL DEFAULT -99.0,
                        isRaceReady INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (driveId) REFERENCES drives(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Index on driveId for efficient point lookups
                db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_points_driveId ON drive_points(driveId)")

                // Migrate existing sessions → drives (without GPS)
                db.execSQL("""
                    INSERT INTO drives (startTime, endTime, hasGps, peakRpm, peakBoostPsi,
                                        peakOilTempC, peakCoolantTempC, maxSpeedKph, totalFrames)
                    SELECT startTime, endTime, 0,
                           CAST(peakRpm AS INTEGER), peakBoostPsi,
                           peakOilTempC, peakCoolantTempC, peakSpeedKph, totalFrames
                    FROM sessions
                """.trimIndent())
            }
        }

        /** Migration v2 → v3: add user-editable drive name column. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drives ADD COLUMN name TEXT DEFAULT NULL")
            }
        }

        /** Migration v3 → v4: add start/end temp columns for drive summary. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drives ADD COLUMN startOilTempC REAL NOT NULL DEFAULT -99.0")
                db.execSQL("ALTER TABLE drives ADD COLUMN endOilTempC REAL NOT NULL DEFAULT -99.0")
                db.execSQL("ALTER TABLE drives ADD COLUMN startCoolantTempC REAL NOT NULL DEFAULT -99.0")
                db.execSQL("ALTER TABLE drives ADD COLUMN endCoolantTempC REAL NOT NULL DEFAULT -99.0")
            }
        }

        /** Migration v4 → v5: dynamics fields on points + tags/score on drives. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Extended dynamics per point
                db.execSQL("ALTER TABLE drive_points ADD COLUMN longitudinalG REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE drive_points ADD COLUMN brakePressure REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE drive_points ADD COLUMN steeringAngle REAL NOT NULL DEFAULT 0.0")
                // Drive-level metadata
                db.execSQL("ALTER TABLE drives ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE drives ADD COLUMN aggressionScore INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Migration v5 → v6: DTC scan history tables. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS dtc_scans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    moduleCount INTEGER NOT NULL,
                    totalCodes INTEGER NOT NULL,
                    scanDurationMs INTEGER NOT NULL DEFAULT 0,
                    moduleStatuses TEXT NOT NULL DEFAULT '{}'
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS dtc_codes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    scanId INTEGER NOT NULL,
                    module TEXT NOT NULL,
                    code TEXT NOT NULL,
                    description TEXT NOT NULL,
                    status TEXT NOT NULL,
                    freezeFrameJson TEXT,
                    FOREIGN KEY (scanId) REFERENCES dtc_scans(id) ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dtc_codes_scanId ON dtc_codes(scanId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dtc_codes_code ON dtc_codes(code)")
            }
        }

        /** Migration v6 → v7: lap timer + performance run tables. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Lap sessions
                db.execSQL("""CREATE TABLE IF NOT EXISTS lap_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    driveId INTEGER NOT NULL,
                    startFinishLat REAL NOT NULL,
                    startFinishLng REAL NOT NULL,
                    startFinishBearing REAL NOT NULL,
                    createdAt INTEGER NOT NULL
                )""")
                // Individual laps
                db.execSQL("""CREATE TABLE IF NOT EXISTS laps (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sessionId INTEGER NOT NULL,
                    lapNumber INTEGER NOT NULL,
                    lapTimeMs INTEGER NOT NULL,
                    peakRpm INTEGER NOT NULL DEFAULT 0,
                    peakBoostPsi REAL NOT NULL DEFAULT 0.0,
                    peakLateralG REAL NOT NULL DEFAULT 0.0,
                    peakSpeedKph REAL NOT NULL DEFAULT 0.0,
                    FOREIGN KEY (sessionId) REFERENCES lap_sessions(id) ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_laps_sessionId ON laps(sessionId)")
                // Performance runs (0-60 / 0-100)
                db.execSQL("""CREATE TABLE IF NOT EXISTS perf_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    driveId INTEGER,
                    timestamp INTEGER NOT NULL,
                    zeroTo60Ms INTEGER NOT NULL,
                    zeroTo100Ms INTEGER,
                    peakRpm REAL NOT NULL DEFAULT 0.0,
                    peakBoostPsi REAL NOT NULL DEFAULT 0.0,
                    launchRpm REAL NOT NULL DEFAULT 0.0,
                    densityAltFt REAL,
                    ambientTempC REAL NOT NULL DEFAULT -99.0
                )""")
            }
        }

        /** Migration v7 → v8: fuel economy + thermal peaks on drives. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drives ADD COLUMN avgFuelL100km REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE drives ADD COLUMN avgFuelMpg REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE drives ADD COLUMN thermalPeaksJson TEXT DEFAULT NULL")
            }
        }

        /** Migration v8 → v9: drive bookmarks table. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS drive_bookmarks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    driveId INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lng REAL NOT NULL,
                    label TEXT NOT NULL DEFAULT '',
                    speedKph REAL NOT NULL DEFAULT 0.0,
                    rpm INTEGER NOT NULL DEFAULT 0,
                    boostPsi REAL NOT NULL DEFAULT 0.0,
                    FOREIGN KEY (driveId) REFERENCES drives(id) ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_bookmarks_driveId ON drive_bookmarks(driveId)")
            }
        }

        fun getInstance(context: Context): DriveDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DriveDatabase::class.java,
                    "openrs_sessions.db"      // same DB file — migration adds new tables
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
