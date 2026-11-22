import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import JSZip from 'jszip'
import { importZip } from './import'
import { computeDeltas } from './compare'
import { useStore } from '../store'
import type { Session } from '../types/session'
import { DashboardPanel } from '../components/panels/DashboardPanel'
import { storeWithSessions } from '../test/storeState'

// #443: the app stores no fuel-used figure for a drive whose level fell by fewer than four gauge steps (#336): the
// profile's drive.fuelUsedL is 0.0 and its text summary prints "—". Sapphire kept its own figure (first level minus
// last from the CSV, no step threshold) and an average economy from it. Synthetic drives, relative times, made-up
// coordinates.

vi.mock('../components/charts/GpsMap', () => ({ GpsMap: () => null }))   // Leaflet needs a real layout engine
vi.mock('./db', () => ({ deleteSession: vi.fn(), putSession: vi.fn() }))

const HEADER = 'timestamp_ms,lat,lng,speed_kph,rpm,fuel_pct'
const TS = '20200101_000000'
// #336's idle run: 100, 99.6, 99.2, 98.8, 98.4 % — 1.6 % of the 52.3 L tank, 0.8368 L by the CSV alone
const IDLE_LEVELS = [100, 99.6, 99.2, 98.8, 98.4]
const CSV_FIGURE_L = (1.6 / 100) * 52.3

const csv = (levels: number[], gps = true) => [HEADER, ...levels.map((fuel, i) =>
  `${1000 + i * 1000},${gps ? (10 + i * 0.001).toFixed(6) : ''},${gps ? '20.000000' : ''},40,3000,${fuel}`)].join('\n')

function profile(drive: Record<string, unknown>): string {
  return JSON.stringify({
    version: 2,
    drive: { startTime: 1000, endTime: 5000, distanceKm: 0.44, avgSpeedKph: 40, maxSpeedKph: 40, peakRpm: 3000,
      peakLateralG: 0, peakOilTempC: -99, peakCoolantTempC: -99, aggressionScore: 0, tags: '', ...drive },
    peaks: [], thermalProgression: {}, modeTimeline: [], bookmarks: [],
  })
}

async function imported(csvText: string, profileJson?: string): Promise<Session> {
  const zip = new JSZip()
  zip.file(`drive_${TS}.csv`, csvText)
  if (profileJson) zip.file(`drive_profile_${TS}.json`, profileJson)
  return importZip(new File([await zip.generateAsync({ type: 'blob' })], 'openrs_export.zip', { type: 'application/zip' }))
}

const dashboard = (s: Session) => {
  useStore.setState(storeWithSessions([s], s.id))
  return render(<DashboardPanel />).container.textContent ?? ''
}

afterEach(() => cleanup())

describe('#443 fuel used: the drive JSON\'s figure, when it states one', () => {
  it('fuelUsedL 0.0 is no figure and no economy, whatever the CSV holds', async () => {
    const s = (await imported(csv(IDLE_LEVELS), profile({ fuelUsedL: 0.0 }))).trip!.summary
    expect(s.fuelUsedL).toBe(0)
    expect(s.avgFuelEconomy).toBe(0)
  })

  it('fuelUsedL 0.0 on a drive without coordinates: no economy either', async () => {
    const s = (await imported(csv(IDLE_LEVELS, false), profile({ fuelUsedL: 0.0 }))).trip!.summary
    expect(s.fuelUsedL).toBe(0)
    expect(s.avgFuelEconomy).toBe(0)
  })

  it('Dashboard shows no Fuel Used or Economy card for it', async () => {
    const text = dashboard({ ...(await imported(csv(IDLE_LEVELS), profile({ fuelUsedL: 0.0 }))), id: 'idle' })
    expect(text).not.toContain('Fuel Used')
    expect(text).not.toContain('Economy')
  })

  it('Compare treats it as a missing reading: no difference for the pair', async () => {
    const none = (await imported(csv(IDLE_LEVELS), profile({ fuelUsedL: 0.0 }))).trip!.summary
    const some = (await imported(csv([80, 79, 78, 77, 76]), profile({ fuelUsedL: 2.1 }))).trip!.summary
    const fuel = computeDeltas(none, some).find((d) => d.label === 'Fuel Used')!
    expect(fuel.baseValue).toBeNull()
    expect(fuel.compValue).toBe(2.1)
    expect(fuel.diff).toBeNull()
    expect(fuel.pctChange).toBeNull()
    const econ = computeDeltas(none, some).find((d) => d.label === 'Fuel Economy')!
    expect(econ.baseValue).toBeNull()
    expect(econ.diff).toBeNull()
  })

  it('fuelUsedL 0.0 with no recorded distance, on a CSV with coordinates: no economy from the CSV figure', async () => {
    const s = (await imported(csv(IDLE_LEVELS), profile({ fuelUsedL: 0.0, distanceKm: undefined }))).trip!.summary
    expect(s.distanceSource).toBe('estimate')                     // the point sum, not a recorded distance
    expect(s.fuelUsedL).toBe(0)
    expect(s.avgFuelEconomy).toBe(0)
  })

  it('a figure above 0 is shown as today, with its economy over the recorded distance', async () => {
    const s = (await imported(csv(IDLE_LEVELS), profile({ fuelUsedL: 0.9 }))).trip!.summary
    expect(s.fuelUsedL).toBe(0.9)
    expect(s.avgFuelEconomy).toBeCloseTo((0.9 / 0.44) * 100, 6)
  })

  it('control: a CSV-only import keeps the CSV figure', async () => {
    const s = (await imported(csv(IDLE_LEVELS))).trip!.summary
    expect(s.fuelUsedL).toBeCloseTo(CSV_FIGURE_L, 6)
    expect(s.avgFuelEconomy).toBeGreaterThan(0)
  })

  it.each([
    ['no fuelUsedL field', {}],
    ['null', { fuelUsedL: null }],
    ['a string', { fuelUsedL: '0.0' }],
  ])('control: a profile with %s states no figure, so the CSV figure stays', async (_name, drive) => {
    const s = (await imported(csv(IDLE_LEVELS), profile(drive))).trip!.summary
    expect(s.fuelUsedL).toBeCloseTo(CSV_FIGURE_L, 6)
  })
})
