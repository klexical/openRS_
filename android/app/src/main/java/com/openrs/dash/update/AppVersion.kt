package com.openrs.dash.update

import com.openrs.dash.BuildConfig

/**
 * Parsed semantic version from a GitHub release tag, APK name or installed version name.
 * Supports stable (`2.2.6`), RC (`2.2.6-rc.5`) and nightly (`2.2.7-nightly.20261201`, #322) versions.
 * Within one base: every RC < every nightly < stable; RCs order by number, nightlies by date. Across bases the base
 * decides. A nightly is its own stage: its date is in [nightly], never in [rc].
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val rc: Int? = null,       // N = rc.N
    val nightly: Int? = null,  // YYYYMMDD = nightly.YYYYMMDD (#322); never set together with rc
) : Comparable<AppVersion> {

    val isStable: Boolean get() = rc == null && nightly == null
    val isNightly: Boolean get() = nightly != null

    val displayName: String get() = buildString {
        append("$major.$minor.$patch")
        if (rc != null) append("-rc.$rc")
        if (nightly != null) append("-nightly.%08d".format(nightly))
    }

    /** Stage within one base: RC (0) < nightly (1) < stable (2). */
    private val stage: Int get() = when {
        rc != null -> 0
        nightly != null -> 1
        else -> 2
    }

    override fun compareTo(other: AppVersion): Int {
        val base = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (base != 0) return base
        // Same base version: RC < nightly < stable, then the RC number or the nightly's date
        return compareValuesBy(this, other, { it.stage }, { it.rc ?: 0 }, { it.nightly ?: 0 })
    }

    companion object {
        /** The suffix after the base: `-rc.N` or `-nightly.YYYYMMDD` (exactly eight digits). Groups: rc, nightly. */
        private const val STAGE = """(?:-rc\.(\d+)|-nightly\.(\d{8}))?"""
        private val TAG_REGEX = Regex("""android-v(\d+)\.(\d+)\.(\d+)$STAGE""")
        private val APK_REGEX = Regex("""openRS_v(\d+)\.(\d+)\.(\d+)$STAGE\.apk""")

        private fun of(m: MatchResult) = AppVersion(
            major = m.groupValues[1].toInt(),
            minor = m.groupValues[2].toInt(),
            patch = m.groupValues[3].toInt(),
            rc = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt(),
            nightly = m.groupValues[5].takeIf { it.isNotEmpty() }?.toInt(),
        )

        /** Parse a GitHub release tag like `android-v2.2.6`, `android-v2.2.6-rc.5` or `android-v2.2.7-nightly.20261201`. */
        fun fromTagName(tag: String): AppVersion? = TAG_REGEX.matchEntire(tag)?.let(::of)

        /**
         * Parse an APK asset filename like `openRS_v2.2.6.apk`, `openRS_v2.2.6-rc.10.apk` or
         * `openRS_v2.2.7-nightly.20261201.apk`.
         * The updater takes a release's version from its tag and only the asset named exactly
         * `openRS_v<name>.apk` for that tag (#364); this parser checks that a file name and a tag agree.
         */
        fun fromApkFilename(name: String): AppVersion? = APK_REGEX.matchEntire(name)?.let(::of)

        /**
         * The whole name, then an optional debug suffix `-dev+<commit>[.dirty]` (the commit is "unknown" without git):
         * any other suffix makes the name no version at all (#322: `2.2.7-nightly.…` used to read as stable 2.2.7).
         */
        private val NAME_REGEX = Regex("""(\d+)\.(\d+)\.(\d+)$STAGE(?:-dev\+\S*)?""")

        /**
         * Parse an installed / release version name: "2.2.7-rc.7", "2.2.7", "2.2.7-nightly.20261201", or a debug name
         * such as "2.2.7-rc.7-dev+1c3e602a9b1d.dirty" (the dev suffix is ignored). Null for any other name.
         */
        fun fromVersionName(name: String): AppVersion? = NAME_REGEX.matchEntire(name)?.let(::of)

        /** The installed release identity (#214) — one source, so the RC is never appended twice or dropped. */
        fun current(): AppVersion = fromVersionName(BuildConfig.RELEASE_NAME) ?: AppVersion(0, 0, 0)
    }
}
