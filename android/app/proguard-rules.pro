# ═══════════════════════════════════════════════════════
# openRS ProGuard Rules
# ═══════════════════════════════════════════════════════

# Keep VehicleState data class (used with StateFlow reflection)
-keep class com.openrs.dash.data.VehicleState { *; }
-keep class com.openrs.dash.data.DriveMode { *; }
-keep class com.openrs.dash.data.EscStatus { *; }


# Room entities (the legacy session tables in DriveDatabase)
-keep class com.openrs.dash.data.SessionEntity { *; }
-keep class com.openrs.dash.data.SnapshotEntity { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# Compose
-dontwarn androidx.compose.**
