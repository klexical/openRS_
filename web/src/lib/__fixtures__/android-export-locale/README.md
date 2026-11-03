Synthetic three-point drive exported by the rc.7 Android builders with the default locale set to pl_PL
(`OPENRS_LOCALE_SAMPLES=<dir> ./gradlew testDebugUnitTest --tests '*ExportLocaleTest'`). No vehicle or user data.
`import.localeExport.test.ts` imports it as is, and derives the pre-rc.7 decimal-comma corruption from it.
