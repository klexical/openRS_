plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openrs.dash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openrs.dash"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "2.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

dependencies {
    // ── aauto-sdk (unofficial) — custom full-screen AA UI identical to phone app
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // ── Android Auto (Car App Library — kept for RSDashCarAppService fallback) ────
    implementation("androidx.car.app:app:1.4.0")

    // ── Jetpack Compose (Phone UI) ──────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ── Kotlin Coroutines ───────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── AndroidX Core ───────────────────────────────────────
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // ── Testing ─────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
