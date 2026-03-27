import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ── Release signing ───────────────────────────────────────────────────────────
// Credentials are read from keystore.properties (gitignored).
// To set up on a new machine: copy openrs-release.jks + keystore.properties
// into the android/ directory. See docs/signing-setup.md for details.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().also { props ->
    if (keystorePropsFile.exists()) props.load(keystorePropsFile.inputStream())
}

val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().also { props ->
    if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
}

android {
    namespace = "com.openrs.dash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openrs.dash"
        minSdk = 28
        targetSdk = 35
        versionCode = 31
        versionName = "2.2.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "OPENWEATHER_API_KEY",
            "\"${localProps["OPENWEATHER_API_KEY"] ?: ""}\"")

        val rc = project.findProperty("rcSuffix")?.toString()?.trim().orEmpty()
        buildConfigField("String", "RC_SUFFIX", "\"$rc\"")

    }

    signingConfigs {
        create("release") {
            val sf = keystoreProps["storeFile"] as? String
            if (sf != null) {
                storeFile     = rootProject.file(sf)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias      = keystoreProps["keyAlias"]      as String
                keyPassword   = keystoreProps["keyPassword"]   as String
            }
        }
    }

    // Rename output APKs:
    //   debug:   openRS_v{version}-staging-debug.apk
    //   release: openRS_v{version}-{rcSuffix}.apk  (or openRS_v{version}.apk when no RC)
    val rc = project.findProperty("rcSuffix")?.toString()?.trim().orEmpty()
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = when (variant.buildType.name) {
                "debug"   -> "openRS_v${variant.versionName}-staging-debug.apk"
                "release" -> if (rc.isNotEmpty())
                                 "openRS_v${variant.versionName}-${rc}.apk"
                             else
                                 "openRS_v${variant.versionName}.apk"
                else      -> output.outputFileName
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false  // AGP 8.7 lint crashes with Compose BOM 2025.11.00
    }
}

dependencies {
    // ── Jetpack Compose (Phone UI) ──────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2025.11.00")
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

    // ── Room (Session History) ──────────────────────────────
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── Trip Map ────────────────────────────────────────────
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

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
