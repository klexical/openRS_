/**
 * Export contract version (`exportSchemaVersion` at the root of manifest.json and of the diagnostic detail JSON; the app's
 * EXPORT_SCHEMA_VERSION, DiagnosticReportBuilder.kt). Absent = exported before the field existed (rc.7 and earlier).
 * manifest.json's own `version` is a different number: it has always been 1.
 *
 * The app bumps it only for a change a reader could misread (a field or file removed or renamed; a type, unit, scale or
 * meaning changed); additive changes keep it. It never blocks an import.
 */
export const KNOWN_EXPORT_SCHEMA_VERSION = 1

/**
 * The version if `raw` is one: a positive safe integer, exactly as the app writes it. Anything else (text, zero, negative,
 * fractional, beyond 2^53, null, …) states nothing, so it reads like a legacy export. No upper limit: a huge integer is
 * only a very new version.
 */
export function parseExportSchemaVersion(raw: unknown): number | undefined {
  return typeof raw === 'number' && Number.isSafeInteger(raw) && raw >= 1 ? raw : undefined
}

/** The session's note when its export is newer than this Sapphire knows; null otherwise (legacy, current, unstated). */
export function exportSchemaNote(version: number | undefined): string | null {
  return version !== undefined && version > KNOWN_EXPORT_SCHEMA_VERSION
    ? `Export schema ${version} is newer than this Sapphire knows (${KNOWN_EXPORT_SCHEMA_VERSION}): some values may be missing or misread`
    : null
}
