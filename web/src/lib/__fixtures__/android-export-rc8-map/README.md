Synthetic drive CSV as the rc.8 exporter writes it with the #271 MAP columns (`map_f40b_kpa_abs`, `map_f40b_saturated`)
appended after the existing ones: three points with TIP 190 kPa and MAP 180 / 255 (saturated) / not recorded. Point times are
relative (1000–1002 ms) and the export time is the fixtures' synthetic 2026-01-01 00:00:00 UTC, so no real run time is held.
Written by `MapF40bStorageTest`: `OPENRS_EXPORT_SAMPLES_MAP=<dir> ./gradlew testDebugUnitTest --tests '*MapF40bStorageTest*'`.
`import.androidExportRc8Map.test.ts` imports it: the appended columns must not break the import.
