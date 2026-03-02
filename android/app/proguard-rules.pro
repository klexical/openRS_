# ═══════════════════════════════════════════════════════
# openRS ProGuard Rules
# ═══════════════════════════════════════════════════════

# Keep VehicleState data class (used with StateFlow reflection)
-keep class com.openrs.dash.data.VehicleState { *; }
-keep class com.openrs.dash.data.DriveMode { *; }
-keep class com.openrs.dash.data.EscStatus { *; }

# Keep Android Auto service (discovered via manifest)
-keep class com.openrs.dash.auto.RSDashCarAppService { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# Compose
-dontwarn androidx.compose.**
