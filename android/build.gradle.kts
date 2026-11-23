// ═══════════════════════════════════════════════════════
// openRS — Root Build Configuration
// ═══════════════════════════════════════════════════════

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
    // Screenshot tests on Robolectric (MainScreensScreenshotTest); test-only. 1.60.0 is the last release built for
    // Kotlin 2.0 metadata: 1.61.0 and later carry 2.3 metadata, which this Kotlin 2.0.21 compiler refuses
    id("io.github.takahirom.roborazzi") version "1.79.0" apply false
}
