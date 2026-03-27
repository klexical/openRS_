package com.openrs.dash.data

import android.content.Context
import androidx.room.*

// ═══════════════════════════════════════════════════════════════════════════
// SESSION HISTORY — Room entities, DAO, and database
// ═══════════════════════════════════════════════════════════════════════════

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,           // epoch millis
    val endTime: Long = 0,         // 0 = still active
    val peakRpm: Double = 0.0,
    val peakBoostPsi: Double = 0.0,
    val peakOilTempC: Double = -99.0,
    val peakCoolantTempC: Double = -99.0,
    val peakSpeedKph: Double = 0.0,
    val totalFrames: Long = 0
)

@Entity(
    tableName = "snapshots",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,           // epoch millis
    val rpm: Double = 0.0,
    val speedKph: Double = 0.0,
    val boostKpa: Double = 0.0,
    val oilTempC: Double = -99.0,
    val coolantTempC: Double = -99.0,
    val throttlePct: Double = 0.0
)

@Dao
interface SessionDao {
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

    @Query("DELETE FROM snapshots WHERE sessionId IN (SELECT id FROM sessions WHERE startTime < :cutoff)")
    fun deleteOldSnapshots(cutoff: Long)

    @Query("DELETE FROM sessions WHERE startTime < :cutoff")
    fun deleteOldSessions(cutoff: Long)
}

@Database(entities = [SessionEntity::class, SnapshotEntity::class], version = 1, exportSchema = false)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: SessionDatabase? = null

        fun getInstance(context: Context): SessionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SessionDatabase::class.java,
                    "openrs_sessions.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
