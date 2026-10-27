import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip, importBatchZip, isDriveCsvName, ImportError } from './import'

// Unmodified ZIPs exported by openRS_ v2.2.7-rc.6 through the Android share sheet (2026-10-26). Drive CSVs
// start with a `# openrs_csv_v2` format line; the no-GPS export carries the knock CSV as its only drive_* CSV.
const FIXTURES = join(process.cwd(), 'src/lib/__fixtures__/android-exports/')   // vitest root = web/
const fixture = (name: string) => new File([readFileSync(FIXTURES + name)], name, { type: 'application/zip' })

async function zipOf(files: Record<string, string>): Promise<File> {
  const zip = new JSZip()
  for (const [f, text] of Object.entries(files)) zip.file(f, text)
  return new File([await zip.generateAsync({ type: 'blob' })], 'test.zip', { type: 'application/zip' })
}

const HEADER = 'timestamp_ms,lat,lng,speed_kph,rpm'
const ROWS = ['1000,51.5,-0.12,40,3000', '2000,51.6,-0.13,60,4200']

describe('openRS_ app exports (rc.4+ CSV format)', () => {
  it('imports a full drive export — drive CSV chosen over the RaceChrono / TrackAddict CSVs', async () => {
    const s = await importZip(fixture('drive-gps-companions.zip'))
    expect(s.trip!.points).toHaveLength(5)
    expect(s.trip!.points[0].ts).toBe(1760000000000)
    expect(s.trip!.points[0].rpm).toBe(3000)
    // This branch merges the profile JSON: its drive.peakRpm (6700) overrides the CSV-derived 4600
    expect(s.trip!.summary.peakRpm).toBe(6700)
    expect(s.diagnostics).toBeTruthy()
  })

  it('a no-GPS export has no drive CSV — the knock table is not imported as a trip', async () => {
    const s = await importZip(fixture('drive-nogps-knock.zip'))
    expect(s.trip).toBeNull()
    expect(s.diagnostics).toBeTruthy()
  })

  it('imports diagnostic snapshots from debug and release builds', async () => {
    for (const name of ['diag-snapshot-debug.zip', 'diag-snapshot-release.zip']) {
      const s = await importZip(fixture(name))
      expect(s.trip).toBeNull()
      expect(s.diagnostics).toBeTruthy()
    }
  })

  it('skips the # openrs_csv_v2 format line, also after a UTF-8 BOM', async () => {
    for (const csv of [`# openrs_csv_v2\n${HEADER}\n${ROWS.join('\n')}`, `\uFEFF# openrs_csv_v2\n${HEADER}\n${ROWS.join('\n')}`]) {
      const s = await importZip(await zipOf({ 'drive_20261026_120000.csv': csv }))
      expect(s.trip!.points).toHaveLength(2)
      expect(s.trip!.summary.peakRpm).toBe(4200)
    }
  })

  it('still imports CSVs without the format line', async () => {
    const s = await importZip(await zipOf({ 'drive_20260101_120000.csv': `${HEADER}\n${ROWS.join('\n')}` }))
    expect(s.trip!.points).toHaveLength(2)
  })

  it('isDriveCsvName keeps drive / trip CSVs and skips companion CSVs', () => {
    expect(isDriveCsvName('drive_20261026_223304.csv')).toBe(true)
    expect(isDriveCsvName('trip_20250101_120000.csv')).toBe(true)
    expect(isDriveCsvName('drive_racechrono_20261026_223304.csv')).toBe(false)
    expect(isDriveCsvName('drive_trackaddict_20261026_223304.csv')).toBe(false)
    expect(isDriveCsvName('drive_knock_20261026_223621.csv')).toBe(false)
    expect(isDriveCsvName('drive_knock_session_20261026_223621.csv')).toBe(false)
    expect(isDriveCsvName('drive_7_20261026_knock.csv')).toBe(false)
    expect(isDriveCsvName('drive_summary_20261026_223304.txt')).toBe(false)
  })

  it('picks the drive CSV even when a companion CSV comes first in the ZIP', async () => {
    const s = await importZip(await zipOf({
      'drive_racechrono_20261026_120000.csv': 'Time (s),Speed (m/s)\n0,10\n1,12',
      'drive_20261026_120000.csv': `# openrs_csv_v2\n${HEADER}\n${ROWS.join('\n')}`,
    }))
    expect(s.trip!.points).toHaveLength(2)
  })
})

describe('required drive CSV columns (timestamp + rpm)', () => {
  const MARKER = '# openrs_csv_v2'

  it('rejects a drive CSV without timestamp / rpm columns with an ImportError naming them', async () => {
    const file = await zipOf({ 'drive_20261026_120000.csv': `${MARKER}\nlat,lng,speed_kph\n51.5,-0.12,40\n51.6,-0.13,60` })
    const err = await importZip(file).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ImportError)
    expect((err as Error).message).toMatch(/drive_20261026_120000\.csv is missing required columns/)
    expect((err as Error).message).toMatch(/timestamp \(one of: timestamp_ms, timestamp, ts\); rpm/)
  })

  it('names only the missing group', async () => {
    const file = await zipOf({ 'drive_20261026_120000.csv': 'timestamp_ms,lat,lng,speed_kph\n1000,51.5,-0.12,40\n2000,51.6,-0.13,60' })
    await expect(importZip(file)).rejects.toThrow(/missing required columns: rpm \(one of: rpm\)$/)
  })

  it('accepts the BOM + format line + header of an app export', async () => {
    const s = await importZip(await zipOf({ 'drive_20261026_120000.csv': `\uFEFF${MARKER}\n${HEADER}\n${ROWS.join('\n')}` }))
    expect(s.trip!.points).toHaveLength(2)
  })

  it('never validates companion CSVs, wherever they sit in the ZIP', async () => {
    // RaceChrono first (no manifest): its header has neither column
    const s1 = await importZip(await zipOf({
      'drive_racechrono_20261026_120000.csv': 'Time (s),Speed (m/s)\n0,10\n1,12',
      'drive_trackaddict_20261026_120000.csv': 'Time,Lap\n0,1\n1,1',
      'drive_20261026_120000.csv': `${MARKER}\n${HEADER}\n${ROWS.join('\n')}`,
    }))
    expect(s1.trip!.points).toHaveLength(2)
    // rc.4 manifest that tags the knock and RaceChrono CSVs as drive_csv, listed before the drive CSV
    const manifest = JSON.stringify({ version: 1, app: 'openRS_', appVersion: '2.2.7-rc.4', exportedAt: 0, files: [
      { name: 'drive_racechrono_20261026_120000.csv', type: 'drive_csv' },
      { name: 'drive_knock_20261026_120000.csv', type: 'drive_csv' },
      { name: 'drive_20261026_120000.csv', type: 'drive_csv' },
    ] })
    const s2 = await importZip(await zipOf({
      'manifest.json': manifest,
      'drive_racechrono_20261026_120000.csv': 'Time (s),Speed (m/s)\n0,10\n1,12',
      'drive_knock_20261026_120000.csv': '# openrs_knock_csv_v2\nkr_deg,cylinder\n1.5,1\n2.0,2',
      'drive_20261026_120000.csv': `${MARKER}\n${HEADER}\n${ROWS.join('\n')}`,
    }))
    expect(s2.trip!.points).toHaveLength(2)
  })

  it('real exports still import: no-GPS (knock CSV only), diagnostic snapshots, full drive', async () => {
    for (const name of ['drive-nogps-knock.zip', 'diag-snapshot-debug.zip', 'diag-snapshot-release.zip']) {
      const s = await importZip(fixture(name))
      expect(s.trip).toBeNull()
      expect(s.diagnostics).toBeTruthy()
    }
    expect((await importZip(fixture('drive-gps-companions.zip'))).trip!.summary.peakRpm).toBe(6700)
  })

  it('a header-only drive CSV is still no trip, not an error', async () => {
    const s = await importZip(await zipOf({ 'drive_20261026_120000.csv': `${MARKER}\nlat,lng` }))
    expect(s.trip).toBeNull()
  })

  it('a malformed drive CSV in a batch export fails the whole batch, before or after a valid one', async () => {
    const valid = `${MARKER}\n${HEADER}\n${ROWS.join('\n')}`
    const bad = `${MARKER}\nlat,lng\n51.5,-0.12\n51.6,-0.13`
    for (const files of [
      { 'drive_1_20261026_120000.csv': valid, 'drive_2_20261026_130000.csv': bad },
      { 'drive_2_20261026_130000.csv': bad, 'drive_1_20261026_120000.csv': valid },
    ]) {
      const file = await zipOf(files)
      await expect(importBatchZip(file)).rejects.toThrow(/drive_2_20261026_130000\.csv is missing required columns/)
      await expect(importZip(file)).rejects.toThrow(/drive_2_20261026_130000\.csv is missing required columns/)
    }
  })
})
