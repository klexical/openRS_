import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip, isDriveCsvName } from './import'

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
