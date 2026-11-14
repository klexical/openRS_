import { describe, it, expect, vi, afterEach } from 'vitest'
import { cleanup, render } from '@testing-library/react'
import JSZip from 'jszip'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importBatchZip, importZip } from './import'
import { KNOWN_EXPORT_SCHEMA_VERSION, exportSchemaNote } from './exportSchema'
import { useStore } from '../store'
import { DashboardPanel } from '../components/panels/DashboardPanel'
import type { Session } from '../types/session'
import { storeWithSessions } from '../test/storeState'

// Leaflet needs a real layout engine; the map is not what these tests are about.
vi.mock('../components/charts/GpsMap', () => ({ GpsMap: () => null }))

// #253: the export's `exportSchemaVersion` is read at import and never blocks it. Real pipeline (importZip / importBatchZip)
// on ZIPs built here from the checked-in exports: rc.6 / rc.7-era fixtures are the legacy ones, `android-export-rc8/` is what
// the app's exporter writes today (ExportSchemaVersionTest), and everything else is one of those with its version edited.
// Synthetic data only.
const FIXTURES = join(process.cwd(), 'src/lib/__fixtures__/')   // vitest root = web/
const FUTURE = KNOWN_EXPORT_SCHEMA_VERSION + 1

type Files = Record<string, string>

function dirFiles(rel: string): Files {
  const root = FIXTURES + rel
  return Object.fromEntries(readdirSync(root).filter((f) => f !== 'README.md').map((f) => [f, readFileSync(join(root, f), 'utf8')]))
}

async function zipFiles(rel: string): Promise<Files> {
  const zip = await JSZip.loadAsync(readFileSync(FIXTURES + rel))
  const out: Files = {}
  for (const name of Object.keys(zip.files)) out[name] = await zip.files[name].async('string')
  return out
}

async function file(files: Files, name = 'openrs_export_test.zip'): Promise<File> {
  const zip = new JSZip()
  for (const [f, text] of Object.entries(files)) zip.file(f, text)
  return new File([await zip.generateAsync({ type: 'blob' })], name, { type: 'application/zip' })
}

/**
 * The same ZIP with the manifest's version field set to this JSON text, verbatim (so `1e400` stays what a parser would see).
 * A repeated key: the last one wins in JSON.parse.
 */
function withManifestVersion(files: Files, jsonText: string): Files {
  const manifest = files['manifest.json'].trim()
  expect(manifest.endsWith('}')).toBe(true)
  return { ...files, 'manifest.json': `${manifest.slice(0, -1)}, "exportSchemaVersion": ${jsonText} }` }
}

function withoutManifest(files: Files): Files {
  return Object.fromEntries(Object.entries(files).filter(([f]) => f !== 'manifest.json'))
}

/** A session with what differs per import blanked, to compare two imports of one drive. */
function comparable(s: Session): unknown {
  return { ...s, id: '', importedAt: 0 }
}

const LEGACY_DRIVE = 'android-export-rc6/1-track'
const CURRENT_DIAG = 'android-export-rc8/diag-schema-current'

afterEach(cleanup)

describe('legacy export (no field)', () => {
  it('rc.6 drive: nothing recorded, session as before', async () => {
    const s = await importZip(await file(dirFiles(LEGACY_DRIVE)))
    expect('exportSchemaVersion' in s.meta).toBe(false)
    expect(s.meta).toEqual({
      appVersion: 'unknown', firmwareVersion: 'unknown', sessionStart: '',
      generatedAt: new Date(1700000000000).toISOString(), appBuild: undefined, exportedAt: 1700000000000,
    })
    expect(s.trip!.summary.peakOilTempC).toBe(131)
    expect(exportSchemaNote(s.meta.exportSchemaVersion)).toBeNull()
  })

  it('rc.6 diagnostic ZIP with a detail JSON: nothing recorded, diagnostics parsed', async () => {
    const s = await importZip(await file(await zipFiles('android-exports/diag-snapshot-release.zip')))
    expect('exportSchemaVersion' in s.meta).toBe(false)
    expect(s.meta.appVersion).toBe('2.2.7')
    expect(s.diagnostics).toBeTruthy()
  })

  it('a ZIP with no manifest at all: nothing recorded', async () => {
    const files = withoutManifest(dirFiles(LEGACY_DRIVE))
    const s = await importZip(await file(files))
    expect('exportSchemaVersion' in s.meta).toBe(false)
    expect(s.trip).toBeTruthy()
  })
})

describe('current export', () => {
  it('as the app writes it: recorded from the manifest, no note, diagnostics parsed', async () => {
    const files = dirFiles(CURRENT_DIAG)
    expect(JSON.parse(files['manifest.json']).exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
    const s = await importZip(await file(files))
    expect(s.meta.exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
    expect(s.diagnostics).toBeTruthy()
    expect(exportSchemaNote(s.meta.exportSchemaVersion)).toBeNull()
  })

  it('the field changes nothing else in the session', async () => {
    const files = dirFiles(LEGACY_DRIVE)
    const without = await importZip(await file(files))
    const withField = await importZip(await file(withManifestVersion(files, String(KNOWN_EXPORT_SCHEMA_VERSION))))
    expect(withField.meta.exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
    const meta = { ...withField.meta }
    delete meta.exportSchemaVersion
    expect(comparable({ ...withField, meta })).toEqual(comparable(without))
  })

  it('no manifest: read from the diagnostic detail JSON', async () => {
    const files = withoutManifest(dirFiles(CURRENT_DIAG))
    const s = await importZip(await file(files))
    expect(s.meta.exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
  })

  it('a batch: every session records it', async () => {
    const files = withManifestVersion(await zipFiles('android-exports/batch-typed-manifest.zip'), String(KNOWN_EXPORT_SCHEMA_VERSION))
    const sessions = await importBatchZip(await file(files))
    expect(sessions.length).toBeGreaterThan(1)
    for (const s of sessions) expect(s.meta.exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
  })
})

describe('checked-in sample', () => {
  it('holds no real run time: the only time values are the fixed ones', () => {
    const files = dirFiles(CURRENT_DIAG)
    const text = Object.entries(files).map(([name, body]) => `${name}\n${body}`).join('\n')
    // Any 13-digit number could be an epoch time, so a new time field can't bring a real run time back on regeneration
    expect([...new Set(text.match(/\d{13}/g) ?? [])]).toEqual(['1767225600000'])
    expect([...new Set(text.match(/\d{8}_\d{6}/g) ?? [])]).toEqual(['20260101_000000'])
  })
})

describe('manifest that lists no drive CSV', () => {
  it.each([
    ['an empty list', { files: [] }],
    ['entries of a type this Sapphire does not know', { files: [{ name: 'x.bin', type: 'future_type' }] }],
  ])('%s: the drive in the ZIP is found by name', async (_label, edit) => {
    const f = dirFiles(LEGACY_DRIVE)
    const named = await importZip(await file(f))
    const s = await importZip(await file({ ...f, 'manifest.json': JSON.stringify({ ...JSON.parse(f['manifest.json']), ...edit }) }))
    expect(s.trip?.points.length).toBeGreaterThan(0)
    expect(s.trip?.points.length).toBe(named.trip?.points.length)
  })

  it('an empty list: the diagnostic detail in the ZIP is still read', async () => {
    const f = dirFiles(CURRENT_DIAG)
    const s = await importZip(await file({ ...f, 'manifest.json': JSON.stringify({ ...JSON.parse(f['manifest.json']), files: [] }) }))
    expect(s.diagnostics).toBeTruthy()
    expect(s.meta.exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
  })

  it('a diagnostic-only export stays without a trip', async () => {
    const s = await importZip(await file(dirFiles(CURRENT_DIAG)))
    expect(s.trip).toBeFalsy()
  })
})

describe('manifest identity fields of the wrong type', () => {
  it.each([
    ['appVersion object', { appVersion: { a: 1 } }],
    ['appVersion number', { appVersion: 7 }],
    ['appBuild object', { appBuild: { a: 1 } }],
    ['appBuild string', { appBuild: '39' }],
  ])('%s: imports with printable identity fields', async (_label, edit) => {
    const f = dirFiles(LEGACY_DRIVE)
    const s = await importZip(await file({ ...f, 'manifest.json': JSON.stringify({ ...JSON.parse(f['manifest.json']), ...edit }) }))
    expect(typeof s.meta.appVersion).toBe('string')
    expect(s.meta.appBuild === undefined || typeof s.meta.appBuild === 'number').toBe(true)
  })
})

describe('future version', () => {
  it('imports the same, records the number and says so', async () => {
    const files = dirFiles(LEGACY_DRIVE)
    const without = await importZip(await file(files))
    const s = await importZip(await file(withManifestVersion(files, String(FUTURE))))
    expect(s.meta.exportSchemaVersion).toBe(FUTURE)
    expect(s.trip).toEqual(without.trip)
    expect(exportSchemaNote(s.meta.exportSchemaVersion)).toContain(`schema ${FUTURE}`)
  })

  it('the newest number the JS side can hold is still a version', async () => {
    const s = await importZip(await file(withManifestVersion(dirFiles(LEGACY_DRIVE), String(Number.MAX_SAFE_INTEGER))))
    expect(s.meta.exportSchemaVersion).toBe(Number.MAX_SAFE_INTEGER)
    expect(s.trip).toBeTruthy()
  })

  // What a bump is for: the manifest itself may change. The drive must survive that; filenames still identify it.
  it.each([
    ['no list', undefined],
    ['text', 'drive.csv'],
    ['an object', { drive_csv: 'drive_20261026_120000.csv' }],
    ['plain names', ['drive_20261026_120000.csv']],
    ['a null entry', [null]],
  ])('a manifest whose files are %s: the drive still imports by filename, the version is kept', async (_case, listing) => {
    const files = dirFiles(LEGACY_DRIVE)
    const m = { ...JSON.parse(files['manifest.json']), exportSchemaVersion: FUTURE, files: listing }
    const s = await importZip(await file({ ...files, 'manifest.json': JSON.stringify(m) }))
    expect(s.meta.exportSchemaVersion).toBe(FUTURE)
    expect(s.trip!.points.length).toBeGreaterThan(0)
  })

  it.each([
    ['absent', undefined],
    ['text', 'yesterday'],
    ['beyond any date', 1e300],
  ])('a manifest whose exportedAt is %s: still imports, the version is kept', async (_case, exportedAt) => {
    const files = dirFiles(LEGACY_DRIVE)
    const m = { ...JSON.parse(files['manifest.json']), exportSchemaVersion: FUTURE, exportedAt }
    const s = await importZip(await file({ ...files, 'manifest.json': JSON.stringify(m) }))
    expect(s.meta.exportSchemaVersion).toBe(FUTURE)
    expect(s.trip!.points.length).toBeGreaterThan(0)
    expect(Number.isNaN(Date.parse(s.meta.generatedAt))).toBe(false)
  })

  it('the detail JSON can state it when the manifest does not', async () => {
    const files = withoutManifest(dirFiles(CURRENT_DIAG))
    const name = Object.keys(files).find((f) => f.startsWith('diagnostic_detail_'))!
    const s = await importZip(await file({ ...files, [name]: JSON.stringify({ ...JSON.parse(files[name]), exportSchemaVersion: FUTURE }) }))
    expect(s.meta.exportSchemaVersion).toBe(FUTURE)
  })
})

describe('malformed version', () => {
  const MALFORMED: [string, string][] = [
    ['a numeric string', '"2"'], ['text', '"new"'], ['an empty string', '""'],
    ['negative', '-1'], ['zero', '0'], ['fractional', '1.5'],
    ['beyond 2^53', '9007199254740992'], ['huge', '1e300'], ['too big for a double', '1e400'],
    ['null', 'null'], ['true', 'true'], ['an array', '[2]'], ['an object', '{"major":2}'],
  ]

  it.each(MALFORMED)('%s in the manifest: imports like a legacy export, states nothing', async (_case, json) => {
    const files = dirFiles(LEGACY_DRIVE)
    const legacy = await importZip(await file(files))
    const s = await importZip(await file(withManifestVersion(files, json)))
    expect('exportSchemaVersion' in s.meta).toBe(false)
    expect(comparable(s)).toEqual(comparable(legacy))
    expect(exportSchemaNote(s.meta.exportSchemaVersion)).toBeNull()
  })

  it.each(MALFORMED)('%s in the detail JSON (no manifest): imports, states nothing', async (_case, json) => {
    const files = withoutManifest(dirFiles(CURRENT_DIAG))
    const name = Object.keys(files).find((f) => f.startsWith('diagnostic_detail_'))!
    // A repeated key: the last one wins in JSON.parse, so this replaces the fixture's own value
    const edited = `${files[name].trim().slice(0, -1)}, "exportSchemaVersion": ${json} }`
    const s = await importZip(await file({ ...files, [name]: edited }))
    expect('exportSchemaVersion' in s.meta).toBe(false)
    expect(s.diagnostics).toBeTruthy()
  })

  it('a malformed manifest value does not hide a valid one in the detail JSON', async () => {
    const s = await importZip(await file(withManifestVersion(dirFiles(CURRENT_DIAG), '"2"')))
    expect(s.meta.exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
  })

  it('an unreadable or non-object manifest: imports by filename, as an unreadable one always did', async () => {
    for (const text of ['{ not json', 'null', '[]', '7', '"text"']) {
      const s = await importZip(await file({ ...dirFiles(LEGACY_DRIVE), 'manifest.json': text }))
      expect(s.trip!.points.length).toBeGreaterThan(0)
      expect('exportSchemaVersion' in s.meta).toBe(false)
    }
  })
})

describe('note on the dashboard (never in the way)', () => {
  async function shown(files: Files): Promise<string> {
    const s = await importZip(await file(files))
    useStore.setState(storeWithSessions([s], s.id))
    const { container } = render(<DashboardPanel />)
    expect(container.textContent).toContain(s.name)   // the session's page rendered, not an empty state
    return container.textContent ?? ''
  }

  it('a newer export shows it beside the app version', async () => {
    const text = await shown(withManifestVersion(dirFiles(LEGACY_DRIVE), String(FUTURE)))
    expect(text).toContain(`Export schema ${FUTURE} is newer than this Sapphire knows`)
  })

  it.each([
    ['legacy', () => dirFiles(LEGACY_DRIVE)],
    ['current', () => withManifestVersion(dirFiles(LEGACY_DRIVE), String(KNOWN_EXPORT_SCHEMA_VERSION))],
    ['malformed', () => withManifestVersion(dirFiles(LEGACY_DRIVE), '"2"')],
  ])('%s shows no note', async (_case, files) => {
    expect(await shown(files())).not.toContain('Export schema')
  })
})
