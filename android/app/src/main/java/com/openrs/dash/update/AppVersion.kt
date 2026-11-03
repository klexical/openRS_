package com.openrs.dash.update

import com.openrs.dash.BuildConfig

/**
 * Parsed semantic version from a GitHub release tag.
 * Supports stable (`2.2.6`) and RC (`2.2.6-rc.5`) versions.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val rc: Int? = null  // null = stable, N = rc.N
) : Comparable<AppVersion> {

    val isStable: Boolean get() = rc == null

    val displayName: String get() = buildString {
        append("$major.$minor.$patch")
        if (rc != null) append("-rc.$rc")
    }

    override fun compareTo(other: AppVersion): Int {
        val base = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (base != 0) return base
        // Same base version: stable > any RC
        return when {
            this.rc == null && other.rc == null -> 0
            this.rc == null -> 1   // stable beats RC
            other.rc == null -> -1
            else -> this.rc.compareTo(other.rc)
        }
    }

    companion object {
        private val TAG_REGEX = Regex("""android-v(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""")
        private val APK_REGEX = Regex("""openRS_v(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?\.apk""")

        /** Parse a GitHub release tag like `android-v2.2.6` or `android-v2.2.6-rc.5`. */
        fun fromTagName(tag: String): AppVersion? {
            val m = TAG_REGEX.matchEntire(tag) ?: return null
            return AppVersion(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt(),
                rc = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()
            )
        }

        /**
         * Parse an APK asset filename like `openRS_v2.2.6.apk` or `openRS_v2.2.6-rc.10.apk`.
         * Used as the authoritative version source for release detection — the rolling-RC
         * tag pattern keeps the same git tag across multiple RCs, so the APK filename is
         * the only place the actual RC number is recorded.
         */
        fun fromApkFilename(name: String): AppVersion? {
            val m = APK_REGEX.matchEntire(name) ?: return null
            return AppVersion(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt(),
                rc = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()
            )
        }

        private val NAME_REGEX = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""")

        /**
         * Parse an installed / release version name: "2.2.7-rc.7", "2.2.7", or a debug name such as
         * "2.2.7-rc.7-dev+1c3e602a9b1d.dirty" (the dev suffix is ignored). Null when it doesn't start with a version.
         */
        fun fromVersionName(name: String): AppVersion? {
            val m = NAME_REGEX.find(name) ?: return null
            return AppVersion(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt(),
                rc = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()
            )
        }

        /** The installed release identity (#214) — one source, so the RC is never appended twice or dropped. */
        fun current(): AppVersion = fromVersionName(BuildConfig.RELEASE_NAME) ?: AppVersion(0, 0, 0)
    }
}
