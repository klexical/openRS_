package com.openrs.dash.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ═══════════════════════════════════════════════════════════════════════════
// LEGACY SESSION TABLES — `sessions` and `snapshots`, kept in DriveDatabase. The service writes them and nothing reads
// them; removing them needs a database migration, a decision of its own (#391). Their own database class is gone (#391).
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
