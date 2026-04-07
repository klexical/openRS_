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
    val weatherSummary: String? = null
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
    // ── Race readiness ───────────────────────────────────────
    val isRaceReady: Boolean = false
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
        DrivePointEntity::class
    ],
    version = 3,
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

        fun getInstance(context: Context): DriveDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DriveDatabase::class.java,
                    "openrs_sessions.db"      // same DB file — migration adds new tables
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
