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

        /** The currently installed app version, derived from BuildConfig. */
        fun current(): AppVersion {
            val parts = BuildConfig.VERSION_NAME.split(".")
            val rcSuffix = BuildConfig.RC_SUFFIX
            return AppVersion(
                major = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                minor = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                patch = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                rc = if (rcSuffix.isNotEmpty()) {
                    rcSuffix.removePrefix("rc.").toIntOrNull()
                } else null
            )
        }
    }
}
