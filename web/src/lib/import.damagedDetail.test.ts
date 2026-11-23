import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { importLoadedZip, importZip } from './import'

// #266 (owner, 2026-11-23): a damaged diagnostic detail file in an import is skipped with a note; the drive still loads.
// Invented data only: every ZIP is built here, with invented times and coordinates.

const T0 = 4_000_000_000_000
const HEADER = '# openrs_csv_v2\ntimestamp_ms,lat,lng,speed_kph,rpm'
const csv = (n: number) => `${HEADER}\n${T0 + n * 1e6},${10 + n / 100},20,40,3000\n${T0 + n * 1e6 + 1000},${10 + n / 100 + 0.001},20,42,3100`
const DTC_TXT = '─── PCM ───────────────\nP0101  [STORED]  Invented fault'
const DETAIL = 'diagnostic_detail_invented.json'
const NOTE = `${DETAIL} could not be read (damaged or missing) and was skipped: this import has no session log.`

function zipOf(files: Record<string, string>): JSZip {
  const zip = new JSZip()
  for (const [name, text] of Object.entries(files)) zip.file(name, text)
  return zip
}

async function load(files: Record<string, string>) {
  const notes: string[] = []
  const sessions = await importLoadedZip(zipOf(files), 'invented.zip', notes)
  return { sessions, notes }
}

describe('#266: a damaged diagnostic detail file is skipped with a note, the drive still loads', () => {
  it('detail that is not JSON: the drive loads, no session log, one note', async () => {
    const { sessions, notes } = await load({ 'drive_invented.csv': csv(1), [DETAIL]: '{ "canFrameInventory": {' })
    expect(sessions).toHaveLength(1)
    expect(sessions[0].trip?.points).toHaveLength(2)
    expect(sessions[0].diagnostics).toBeNull()
    expect(notes).toEqual([NOTE])
  })

  it('detail that is JSON but not an object (null): the fault codes stay, marked as having no session log', async () => {
    const { sessions, notes } = await load({ 'drive_invented.csv': csv(1), 'dtc_scan_invented.txt': DTC_TXT, [DETAIL]: 'null' })
    expect(sessions[0].trip?.points).toHaveLength(2)
    const d = sessions[0].diagnostics!
    expect(d.sessionLog).toBe(false)
    expect(d.dtcResults.map((e) => e.code)).toEqual(['P0101'])
    expect(d.sessionEvents).toEqual([])
    expect(notes).toEqual([NOTE])
  })

  it('detail whose entries have a shape the parser cannot read: skipped as a whole, not half-read', async () => {
    const { sessions, notes } = await load({
      'drive_invented.csv': csv(1),
      [DETAIL]: JSON.stringify({ canFrameInventory: { '0x090': null }, sessionEvents: [{ relMs: 1, type: 'info', message: 'x' }] }),
    })
    expect(sessions[0].trip?.points).toHaveLength(2)
    expect(sessions[0].diagnostics).toBeNull()
    expect(notes).toEqual([NOTE])
  })

  it('a manifest names a detail file the ZIP does not hold: the drive loads, one note', async () => {
    const manifest = JSON.stringify({
      version: 1, app: 'openRS_', appVersion: '2.2.7', appBuild: 1, exportedAt: T0,
      files: [{ name: 'drive_invented.csv', type: 'drive_csv' }, { name: DETAIL, type: 'diagnostic_detail' }],
    })
    const { sessions, notes } = await load({ 'manifest.json': manifest, 'drive_invented.csv': csv(1) })
    expect(sessions[0].trip?.points).toHaveLength(2)
    expect(sessions[0].diagnostics).toBeNull()
    expect(notes).toEqual([NOTE])
  })

  it('a batch export with a damaged detail file: every drive loads, and the note is given once', async () => {
    const { sessions, notes } = await load({
      'drive_1_20960101_120000.csv': csv(1), 'drive_2_20960101_130000.csv': csv(2), [DETAIL]: 'not json',
    })
    expect(sessions).toHaveLength(2)
    for (const s of sessions) expect(s.trip?.points).toHaveLength(2)
    expect(notes).toEqual([NOTE])
  })

  it('importZip (no note list) also keeps the drive', async () => {
    const blob = await zipOf({ 'drive_invented.csv': csv(1), [DETAIL]: '{' }).generateAsync({ type: 'blob' })
    const s = await importZip(new File([blob], 'invented.zip'))
    expect(s.trip?.points).toHaveLength(2)
    expect(s.diagnostics).toBeNull()
  })

  it('a readable detail file gives no note and keeps its session log', async () => {
    const detail = JSON.stringify({ sessionEvents: [{ relMs: 500, type: 'connect', message: 'Connected' }] })
    const { sessions, notes } = await load({ 'drive_invented.csv': csv(1), [DETAIL]: detail })
    expect(sessions[0].diagnostics?.sessionEvents).toEqual([{ ts: 500, type: 'connect', message: 'Connected' }])
    expect(sessions[0].diagnostics?.sessionLog).toBeUndefined()
    expect(notes).toEqual([])
  })

  it('a skipped detail file feeds nothing: its export schema version and scan finish time are not taken either', async () => {
    // valid JSON with fields Sapphire reads elsewhere, and an inventory entry the parser cannot read (review of #545)
    const detail = JSON.stringify({ exportSchemaVersion: 3, dtcScan: { finishedAtMs: T0 + 5000 }, canFrameInventory: { '0x090': null } })
    const { sessions, notes } = await load({ 'drive_invented.csv': csv(1), [DETAIL]: detail })
    expect(sessions[0].trip?.points).toHaveLength(2)
    expect(sessions[0].diagnostics).toBeNull()
    expect(sessions[0].meta.exportSchemaVersion).toBeUndefined()
    expect('exportSchemaVersion' in sessions[0].meta).toBe(false)
    expect(sessions[0].dtcScanFinishedAtMs).toBeUndefined()
    expect(notes).toEqual([NOTE])
  })

  it('a readable detail file still gives its export schema version and scan finish time', async () => {
    const detail = JSON.stringify({ exportSchemaVersion: 3, dtcScan: { finishedAtMs: T0 + 5000 }, sessionEvents: [] })
    const { sessions, notes } = await load({ 'drive_invented.csv': csv(1), [DETAIL]: detail })
    expect(sessions[0].meta.exportSchemaVersion).toBe(3)
    expect(sessions[0].dtcScanFinishedAtMs).toBe(T0 + 5000)
    expect(notes).toEqual([])
  })
})
