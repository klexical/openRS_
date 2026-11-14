import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import JSZip from 'jszip'
import { importZip, importBatchZip } from './import'
import { useStore } from '../store'
import type { Session, TripSummary } from '../types/session'
import { DashboardPanel } from '../components/panels/DashboardPanel'
import { SessionsPanel } from '../components/panels/SessionsPanel'
import { ComparePanel } from '../components/panels/ComparePanel'
import { storeWithSessions } from '../test/storeState'

// #284: the app does not count distance across a link loss or a pause (DriveRecorder drops its previous point), and
// exports its figure as the profile's drive.distanceKm. Sapphire summed the straight line between every pair of
// points, so a gap added the distance the car covered unseen. Synthetic drives, relative times, made-up coordinates.

vi.mock('../components/charts/GpsMap', () => ({ GpsMap: () => null }))   // Leaflet needs a real layout engine
vi.mock('./db', () => ({ deleteSession: vi.fn(), putSession: vi.fn() }))

const HEADER = 'timestamp_ms,lat,lng,speed_kph,rpm,fuel_pct'
const LAT0 = 10
const LNG0 = 20
const STEP = 0.001                       // degrees of latitude per fix ≈ 0.111 km

interface Fix { t: number; steps: number; fuel?: number }
const row = ({ t, steps, fuel = 50 }: Fix) => `${t},${(LAT0 + steps * STEP).toFixed(6)},${LNG0.toFixed(6)},40,3000,${fuel}`
const csv = (fixes: Fix[]) => [HEADER, ...fixes.map(row)].join('\n')

/** Great-circle km between consecutive fixes — the test's own arithmetic, not the importer's. */
function pointSumKm(fixes: Fix[]): number {
  const R = 6371
  let km = 0
  for (let i = 1; i < fixes.length; i++) km += R * ((fixes[i].steps - fixes[i - 1].steps) * STEP * Math.PI / 180)
  return Math.abs(km)
}

// Five fixes 1 s apart, a gap in which the car covers 20 steps unseen, five more fixes. Recorded = the eight segments
// the app saw (0.89 km); the point sum adds the 2.2 km straight line across the gap.
const gapDrive = (gapMs: number): Fix[] => [
  ...[0, 1, 2, 3, 4].map((i) => ({ t: 1000 + i * 1000, steps: i, fuel: 50 })),
  ...[0, 1, 2, 3, 4].map((i) => ({ t: 5000 + gapMs + i * 1000, steps: 24 + i, fuel: 48 })),
]
const SEEN_KM = 8 * 6371 * (STEP * Math.PI / 180)          // 0.8896 km

function profile(drive: Record<string, unknown>): string {
  return JSON.stringify({
    version: 2,
    drive: { startTime: 1000, endTime: 70000, avgSpeedKph: 40, maxSpeedKph: 40, peakRpm: 3000, peakLateralG: 0,
      peakOilTempC: -99, peakCoolantTempC: -99, fuelUsedL: 0, aggressionScore: 0, tags: '', ...drive },
    peaks: [], thermalProgression: {}, modeTimeline: [], bookmarks: [],
  })
}

async function zipOf(files: [string, string][], name = 'openrs_export.zip'): Promise<File> {
  const zip = new JSZip()
  for (const [f, text] of files) zip.file(f, text)
  return new File([await zip.generateAsync({ type: 'blob' })], name, { type: 'application/zip' })
}

const TS = '20200101_000000'
const single = (fixes: Fix[], profileJson?: string) =>
  zipOf([[`drive_${TS}.csv`, csv(fixes)], ...(profileJson ? [[`drive_profile_${TS}.json`, profileJson] as [string, string]] : [])])
    .then(importZip)

const source = (s: TripSummary) => (s as { distanceSource?: string }).distanceSource

afterEach(() => cleanup())

describe('#284 distance: the recorded figure, not a line across a gap', () => {
  it('a drive with a link gap shows the app\'s recorded distance', async () => {
    const fixes = gapDrive(60_000)
    expect(pointSumKm(fixes)).toBeCloseTo(3.113, 2)               // what a point-to-point sum gives
    const s = (await single(fixes, profile({ distanceKm: 0.89 }))).trip!.summary
    expect(s.distanceKm).toBe(0.89)
    expect(source(s)).toBe('recorded')
  })

  it('a drive with a five-minute pause shows the app\'s recorded distance', async () => {
    const s = (await single(gapDrive(300_000), profile({ distanceKm: SEEN_KM }))).trip!.summary
    expect(s.distanceKm).toBeCloseTo(SEEN_KM, 9)
    expect(source(s)).toBe('recorded')
  })

  it('a recorded zero is a distance: GPS jitter at a standstill adds nothing', async () => {
    const jitter: Fix[] = [0, 1, 0, 1, 0].map((steps, i) => ({ t: 1000 + i * 1000, steps, fuel: 50 - i }))
    const s = (await single(jitter, profile({ distanceKm: 0 }))).trip!.summary
    expect(s.distanceKm).toBe(0)
    expect(source(s)).toBe('recorded')
    expect(s.avgFuelEconomy).toBe(0)                              // no economy over no distance
  })

  it('fuel economy uses the recorded distance', async () => {
    const s = (await single(gapDrive(60_000), profile({ distanceKm: 0.89, fuelUsedL: 0.5 }))).trip!.summary
    expect(s.fuelUsedL).toBe(0.5)
    expect(s.avgFuelEconomy).toBeCloseTo((0.5 / 0.89) * 100, 6)
  })

  it('a batch gives each drive its own recorded distance', async () => {
    const a = gapDrive(60_000)
    const b = gapDrive(120_000).map((f) => ({ ...f, t: f.t + 1_000_000 }))
    const file = await zipOf([
      ['drive_1_20200101_000000.csv', csv(a)], ['drive_1_20200101_000000_profile.json', profile({ distanceKm: 0.89 })],
      ['drive_2_20200102_000000.csv', csv(b)], ['drive_2_20200102_000000_profile.json', profile({ distanceKm: 0.75 })],
    ], 'openrs_batch.zip')
    const byFirstTs = Object.fromEntries((await importBatchZip(file)).map((s) => [s.trip!.points[0].ts, s.trip!.summary]))
    expect(byFirstTs[1000].distanceKm).toBe(0.89)
    expect(byFirstTs[1_001_000].distanceKm).toBe(0.75)
    expect(source(byFirstTs[1000])).toBe('recorded')
    expect(source(byFirstTs[1_001_000])).toBe('recorded')
  })
})

describe('#284 fallback: exports without a usable recorded distance keep the point sum, labelled', () => {
  it('an export with no profile is an estimate', async () => {
    const fixes = gapDrive(60_000)
    const s = (await single(fixes)).trip!.summary
    expect(s.distanceKm).toBeCloseTo(pointSumKm(fixes), 6)
    expect(source(s)).toBe('estimate')
  })

  it.each([
    ['no distanceKm field', {}],
    ['a string', { distanceKm: '0.89' }],
    ['null', { distanceKm: null }],
    ['a negative number', { distanceKm: -1 }],
    ['a boolean', { distanceKm: true }],
  ])('a profile whose distance is %s is not a recorded distance', async (_name, drive) => {
    const fixes = gapDrive(60_000)
    const s = (await single(fixes, profile(drive))).trip!.summary
    expect(s.distanceKm).toBeCloseTo(pointSumKm(fixes), 6)
    expect(source(s)).toBe('estimate')
  })

  it('control: sparse valid GPS (a fix every 20–40 s, uneven) keeps its whole distance', async () => {
    const times = [0, 20, 60, 85, 120, 150]
    const fixes: Fix[] = times.map((sec, i) => ({ t: 1000 + sec * 1000, steps: i * 5 }))
    const noProfile = (await single(fixes)).trip!.summary
    expect(noProfile.distanceKm).toBeCloseTo(pointSumKm(fixes), 6)      // nothing cut out of a sparse track
    expect(noProfile.distanceKm).toBeCloseTo(2.78, 2)
    const recorded = (await single(fixes, profile({ distanceKm: 2.78 }))).trip!.summary
    expect(recorded.distanceKm).toBe(2.78)
  })

  it('control: a drive without coordinates has no distance, whatever its profile says', async () => {
    const rows = [HEADER, '1000,,,40,3000,50', '2000,,,40,3000,50', '3000,,,40,3000,49'].join('\n')
    const s = (await zipOf([[`drive_${TS}.csv`, rows], [`drive_profile_${TS}.json`, profile({ distanceKm: 40 })]])
      .then(importZip)).trip!.summary
    expect(s.distanceKm).toBe(0)                                  // as the app: DIST only with GPS
  })
})

describe('#284 display: an estimate says so, a recorded distance does not', () => {
  let recorded: Session, estimate: Session, legacy: Session

  beforeEach(async () => {
    recorded = { ...(await single(gapDrive(60_000), profile({ distanceKm: 0.89 }))), id: 'rec', name: 'Recorded drive' }
    estimate = { ...(await single(gapDrive(60_000))), id: 'est', name: 'Estimate drive' }
    // Imported before the fix: a point sum with no flag
    const old = await single(gapDrive(60_000))
    const summary = { ...old.trip!.summary }
    delete (summary as { distanceSource?: string }).distanceSource
    legacy = { ...old, id: 'old', name: 'Older import', trip: { ...old.trip!, summary } }
  })

  const dashboard = (s: Session) => {
    useStore.setState(storeWithSessions([s], s.id))
    return render(<DashboardPanel />).container.textContent ?? ''
  }

  it('Dashboard: the Distance card names an estimate', () => {
    expect(dashboard(estimate)).toMatch(/Distance3\.1 kmEstimate from GPS points/)
  })

  it('Dashboard: a recorded distance has no note', () => {
    const text = dashboard(recorded)
    expect(text).toMatch(/Distance0\.9 kmDuration/)
    expect(text).not.toMatch(/Estimate from GPS points/)
  })

  it('Dashboard: a session imported before the fix reads as an estimate', () => {
    expect(dashboard(legacy)).toMatch(/Distance3\.1 kmEstimate from GPS points/)
  })

  it('Sessions and Compare rows mark only the estimates', () => {
    useStore.setState(storeWithSessions([recorded, estimate, legacy], 'rec'))
    for (const Panel of [SessionsPanel, ComparePanel]) {
      const text = render(<Panel />).container.textContent ?? ''
      expect(text).toMatch(/0\.9 km(?! est\.)/)
      expect(text.match(/3\.1 km est\./g)).toHaveLength(2)
      expect(text).not.toMatch(/0\.9 km est\./)
      cleanup()
    }
  })
})
