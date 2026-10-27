import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import JSZip from 'jszip'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip, importBatchZip } from './import'
import { GpsMap } from '../components/charts/GpsMap'

// ZIPs shared by openRS_ rc.6 (feature/design-pass, drive CSV for no-GPS telemetry) from TRIP on a disposable
// emulator, 2026-10-26. Seeded drives: 1 = GPS, 5 points · 2 = no GPS, no points · 3 = no GPS, 3 points + knock.
//   drive-nogps-telemetry.zip — drive 3 alone, every component ticked (incl. GPX / RaceChrono / TrackAddict)
//   batch-gps-nogps-empty.zip — drives 1, 2 and 3 from TRIP's multi-select EXPORT
const FIXTURES = join(process.cwd(), 'src/lib/__fixtures__/android-exports/')   // vitest root = web/
const bytes = (name: string) => readFileSync(FIXTURES + name)
const fixture = (name: string) => new File([bytes(name)], name, { type: 'application/zip' })
const entries = async (name: string) => Object.keys((await JSZip.loadAsync(bytes(name))).files)

describe('no-GPS drive exports from the app', () => {
  it('a no-GPS drive alone: telemetry imports, no coordinates, no route files', async () => {
    const files = await entries('drive-nogps-telemetry.zip')
    expect(files.filter((f) => /\.gpx$|racechrono|trackaddict/.test(f))).toEqual([])
    expect(files).toContain('drive_20261026_232312.csv')

    const s = await importZip(fixture('drive-nogps-telemetry.zip'))
    const pts = s.trip!.points
    expect(pts).toHaveLength(3)                                  // the three recorded samples, nothing added
    expect(pts.map((p) => p.ts)).toEqual([1760172800000, 1760172801000, 1760172802000])
    expect(pts.every((p) => p.rpm === 5000)).toBe(true)
    expect(pts.every((p) => p.lat === 0 && p.lng === 0)).toBe(true)
    expect(s.diagnostics).toBeTruthy()

    const { container } = render(<GpsMap points={pts} peakEvents={s.trip!.summary.peakEvents} />)
    expect(container.textContent).toContain('No GPS data available')
    expect(container.querySelector('.leaflet-container')).toBeNull()
  })

  it('a batch of GPS, empty and no-GPS drives: two trips, only the GPS drive has a route', async () => {
    const files = await entries('batch-gps-nogps-empty.zip')
    expect(files.filter((f) => f.startsWith('drive_2_'))).toEqual(['drive_2_20251010_025320_summary.txt'])
    expect(files.filter((f) => f.endsWith('.gpx'))).toEqual(['drive_1_20251009_025320.gpx'])

    const sessions = await importBatchZip(fixture('batch-gps-nogps-empty.zip'))
    expect(sessions).toHaveLength(2)                             // the empty drive has no CSV → no trip
    const byFirstTs = Object.fromEntries(sessions.map((s) => [s.trip!.points[0].ts, s]))
    const gps = byFirstTs[1760000000000]
    const noGps = byFirstTs[1760172800000]

    expect(gps.trip!.points).toHaveLength(5)
    expect(gps.trip!.points.every((p) => p.lat !== 0 && p.lng !== 0)).toBe(true)
    expect(gps.trip!.summary.distanceKm).toBeGreaterThan(0)

    expect(noGps.trip!.points).toHaveLength(3)
    expect(noGps.trip!.points.every((p) => p.lat === 0 && p.lng === 0 && p.rpm === 5000)).toBe(true)
    expect(noGps.trip!.summary.distanceKm).toBe(0)
    const { container } = render(<GpsMap points={noGps.trip!.points} peakEvents={[]} />)
    expect(container.textContent).toContain('No GPS data available')
  })

  it('the older no-GPS export with only a knock CSV still has no trip', async () => {
    const s = await importZip(fixture('drive-nogps-knock.zip'))
    expect(s.trip).toBeNull()
  })
})
