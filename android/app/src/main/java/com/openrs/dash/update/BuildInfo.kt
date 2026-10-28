package com.openrs.dash.update

import com.openrs.dash.BuildConfig

/**
 * The app's release identity, derived from the single definition in `app/build.gradle.kts` (#214): base version,
 * RC stage / candidate, versionCode, source commit and build type. Settings, the updater, What's New, exports and crash
 * reports read it from here — never assemble a version string elsewhere.
 */
object BuildInfo {
    /** Published identity, e.g. "2.2.7-rc.7" or "2.2.7" (debug builds too — no dev suffix). */
    val releaseName: String get() = BuildConfig.RELEASE_NAME
    /** Base version, e.g. "2.2.7" — What's New content is keyed by it. */
    val base: String get() = BuildConfig.RELEASE_BASE
    val versionCode: Int get() = BuildConfig.VERSION_CODE
    /** Installed versionName: [releaseName] on release builds, plus "-dev+<commit>[.dirty]" on debug builds. */
    val versionName: String get() = BuildConfig.VERSION_NAME
    val sourceCommit: String get() = BuildConfig.SOURCE_COMMIT
    val sourceDirty: Boolean get() = BuildConfig.SOURCE_DIRTY
    val buildType: String get() = BuildConfig.BUILD_TYPE

    /** Settings: "2.2.7-rc.7 · Build 36" — debug builds add the source revision ("· debug 1c3e602*"). */
    val settingsLabel: String get() = settingsLabel(releaseName, versionCode, buildType, sourceCommit, sourceDirty)

    internal fun settingsLabel(name: String, code: Int, type: String, commit: String, dirty: Boolean): String =
        buildString {
            append(name).append(" · Build ").append(code)
            if (type != "release") append(" · ").append(type).append(' ').append(commit.take(7)).append(if (dirty) "*" else "")
        }
}
