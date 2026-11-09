Synthetic diagnostic snapshot as the rc.8 exporter writes it with the #253 DID inventory and ISO-TP count: `didInventory`
(rows for PCM 0x033E answered, 0x03ED rejected, 0x0466 never answered — no `lastPositiveRelMs` — and BCM 0x4028 answered) and
`linkIntegrity.isoTpRefused` 1, plus the summary's DID INVENTORY section. Seeded counts, no frames, no vehicle state, no
identifiers, and the fixtures' synthetic time (2026-01-01 00:00:00 UTC in the file names, `generatedAt` and `exportedAt`).
Written by `ExportSchemaVersionTest`: `OPENRS_EXPORT_SAMPLES_INVENTORY=<dir> ./gradlew testDebugUnitTest --tests
'*ExportSchemaVersionTest'`. `import.androidExportRc8Inventory.test.ts` imports it: the added fields must not change the import.
