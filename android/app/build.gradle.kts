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

// ── Release identity — the ONE place a release's version is set (#214) ─────────────────────────────────
// versionName, the Settings label, the APK filename, the expected tag (android-v<name>), BuildConfig, export and crash
// metadata all derive from these. versionCode must be strictly higher for every distributed production build (RC and
// stable); CI rejects a reused code. Room / export schema versions are separate and unaffected.
val releaseBase = "2.2.7"
val releaseStage = "rc"          // "rc" for a release candidate, "" for stable
val releaseCandidate = 8         // ignored when releaseStage is ""
val releaseVersionCode = 39     // 39 = rc.8 (38 = rc.7 maintenance asset openrs-2.2.7-rc.7-build38.apk, 37 = rc.7 tag)
val releaseName = if (releaseStage.isEmpty()) releaseBase else "$releaseBase-$releaseStage.$releaseCandidate"

// The old -PrcSuffix flag appended an RC label separately from versionName; refuse it so nothing builds a mismatch.
project.findProperty("rcSuffix")?.toString()?.takeIf { it.isNotBlank() }?.let {
    throw GradleException("-PrcSuffix is no longer used: the release ($releaseName) is set in app/build.gradle.kts (#214)")
}

// Source revision for BuildConfig / exports. Local builds append it to the debug versionName; "unknown" without git.
fun git(vararg args: String): String = try {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
} catch (_: Exception) { "" }
val sourceCommit = git("rev-parse", "--short=12", "HEAD").ifEmpty { "unknown" }
// Dirty = anything in the android/ tree (all the APK is built from) differs from HEAD: modified tracked files AND
// untracked files git doesn't ignore — a new Kotlin source or resource enters the build without being committed.
// Ignored files (build/, local.properties, keystore) don't count.
val sourceDirty = git("status", "--porcelain", "--untracked-files=all", "--", rootProject.projectDir.absolutePath).isNotEmpty()

val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().also { props ->
    if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
}

// API keys (#235): local.properties (contributors), else the environment (CI's publish-release passes its secrets).
// Optional for every build — no Maps key gives a blank map, no weather key no weather request. A published release needs
// Maps: scripts/check-release-config.sh fails the job before the build, scripts/verify-release.sh checks the APK.
// Never log a resolved value.
fun apiKey(name: String): String =
    (localProps[name] as? String)?.takeIf { it.isNotBlank() } ?: providers.environmentVariable(name).orNull.orEmpty()

android {
    namespace = "com.openrs.dash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openrs.dash"
        minSdk = 28
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "OPENWEATHER_API_KEY", "\"${apiKey("OPENWEATHER_API_KEY")}\"")

        buildConfigField("String", "RELEASE_BASE", "\"$releaseBase\"")
        buildConfigField("String", "RELEASE_NAME", "\"$releaseName\"")
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
        buildConfigField("boolean", "SOURCE_DIRTY", "$sourceDirty")

        // Google Maps API key — injected into AndroidManifest via manifestPlaceholders
        manifestPlaceholders["MAPS_API_KEY"] = apiKey("MAPS_API_KEY")
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

    // Rename output APKs from the release identity:
    //   debug:   openRS_v{releaseName}-staging-debug.apk
    //   release: openRS_v{releaseName}.apk   (e.g. openRS_v2.2.7-rc.7.apk — matches tag android-v2.2.7-rc.7)
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = when (variant.buildType.name) {
                "debug"   -> "openRS_v$releaseName-staging-debug.apk"
                "release" -> "openRS_v$releaseName.apk"
                else      -> output.outputFileName
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // Local iterations: the target release + source revision (+ ".dirty" with uncommitted changes) — never a
            // new public RC number per build. e.g. 2.2.7-rc.7-dev+1c3e602a9b1d.dirty
            versionNameSuffix = "-dev+$sourceCommit" + if (sourceDirty) ".dirty" else ""
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
        // #283: Robolectric tests that compose production UI (HeaderRecMarkerTest) need the app's drawables and fonts
        unitTests.isIncludeAndroidResources = true
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

    // ── Haze (Backdrop Blur / Glassmorphism) ──────────────────
    implementation("dev.chrisbanes.haze:haze:1.5.1")

    // ── Maps & Location ───────────────────────────────────────
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.maps.android:maps-compose:6.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── Testing ─────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20231013")
    // Real Room (production DriveDatabase + DriveDao) on the JVM for the recorder tests (#236); test-only, pinned.
    // Runs on SDK 35, the app's target (app/src/test/resources/robolectric.properties); Java 17 (CI) suffices for it.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
