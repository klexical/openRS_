Synthetic diagnostic snapshot as the rc.8 exporter writes it, with no session data: no frames, no vehicle state, no identifiers,
and a fixed time in place of the run's own (2026-01-01 00:00:00 UTC in the file names, `generatedAt` and `exportedAt`), so it holds
no real run time or machine UTC offset. Every fixture uses a synthetic time. Written by `ExportSchemaVersionTest`:
`OPENRS_EXPORT_SCHEMA_SAMPLES=<dir> ./gradlew testDebugUnitTest --tests '*ExportSchemaVersionTest'`, then copied to
`diag-schema-current/`. `import.exportSchema.test.tsx` imports it, so a key name that differs between the app and Sapphire fails there.
