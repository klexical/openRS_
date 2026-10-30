import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import { act, render, cleanup } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from './import'
import { tripChartData } from './readings'
import { pressureChartRows, pressurePeaks, pressureSeries, primaryBoostSource } from './boost'
import { useStore } from '../store'
import { useSettings } from '../store/settings'
import { DashboardPanel } from '../components/panels/DashboardPanel'
import { TripPanel } from '../components/panels/TripPanel'
import type { Session } from '../types/session'

vi.mock('../components/charts/GpsMap', () => ({ GpsMap: () => null }))

// Unmodified ZIP exported through the Android share sheet by the final minified rc.7 release APK (source 0c7431a,
// SHA-256 d88456d4…, 2026-10-29) on a disposable emulator with a synthetic adapter (fake_ecu_v4 --pressure-gaps) and a
// synthetic GPS track. One 1:21 drive, the connection, other CAN traffic and GPS alive throughout:
//   TIP 0x033E live (19 points) → TIP silent, CAN 0x0F8 byte 5 = 0 (12, genuine zeros) → TIP silent AND 0x0F8 stopped
//   (12 points, no pressure reading) → both back (29 TIP points).
// Synthetic data — no car. Export ZIP SHA-256 48ece455…5f8c. The ZIP also carries crash_telemetry_20261029_223940.json:
// the VerifyError crash of the 5854f08 release installed earlier on that emulator (#227), bundled from stored crash
// history — not a crash of 0c7431a; the importer doesn't read it.
const NAME = 'release-pressure-gaps-drive.zip'
const ZIP = join(process.cwd(), 'src/lib/__fixtures__/android-export-rc7/', NAME)
const load = async (): Promise<Session> => ({
  ...(await importZip(new File([readFileSync(ZIP)], NAME, { type: 'application/zip' }))), name: 'gaps-drive',
})

const PSI_TIP = 8.949
const KPA_PER_PSI = 6.894757

let session: Session
beforeAll(async () => { session = await load() })
afterEach(() => {
  cleanup()
  useSettings.setState({ boostUnit: 'PSI' })
  useStore.setState({ sessions: [], activeSessionId: null })
})

/** Runs of consecutive equal keys, e.g. ['TIP', 19], ['CAN', 12] … */
function runs<T>(xs: T[]): [T, number][] {
  const out: [T, number][] = []
  for (const x of xs) {
    if (out.length && out[out.length - 1][0] === x) out[out.length - 1][1]++
    else out.push([x, 1])
  }
  return out
}

describe('rc.7 release export with both pressure sources missing → Sapphire (#207)', () => {
  it('zero stays zero, unavailable stays unavailable, in recorded order', () => {
    const pts = session.trip!.points
    expect(pts).toHaveLength(72)
    expect(runs(pts.map((p) => p.boostSource ?? 'NONE'))).toEqual(
      [['TIP_033E', 19], ['CAN_0F8', 12], ['NONE', 12], ['TIP_033E', 29]])
    expect(pts.filter((p) => p.boostSource === 'CAN_0F8').every((p) => p.boostPsi === 0)).toBe(true)
    expect(pts.filter((p) => p.boostSource == null).every((p) => p.boostPsi == null)).toBe(true)   // not 0
    expect(pts.filter((p) => p.boostSource === 'TIP_033E').every((p) => Math.abs(p.boostPsi! - PSI_TIP) < 0.001))
      .toBe(true)
    // the rest of the drive carried on through the gap
    const none = pts.filter((p) => p.boostSource == null)
    expect(none.every((p) => p.rpm > 0 && p.lat !== 0)).toBe(true)
  })

  it('no peak is created from the missing stretch — one per measured source', () => {
    const sum = session.trip!.summary
    expect(sum.boostPolicy).toBe('rc7-boost-v3')
    expect(sum.peakBoostSource).toBe('TIP_033E')
    expect(sum.peakBoostPsi).toBeCloseTo(PSI_TIP, 2)
    const peaks = sum.peakEvents.filter((e) => e.type === 'boost')
    expect(peaks.map((e) => e.source).sort()).toEqual(['CAN_0F8', 'TIP_033E'])
    expect(peaks.find((e) => e.source === 'CAN_0F8')!.value).toBe(0)
  })

  it('TRIP chart rows (TripPanel pipeline): gaps where nothing was measured, CAN zeros plotted, values in kPa / bar', () => {
    const kpa = pressureChartRows(tripChartData(session.trip!.points), 'kPa')
    const key = (r: (typeof kpa)[number]) => (r.boostTip != null ? 'TIP' : r.boostCan != null ? 'CAN' : 'GAP')
    expect(runs(kpa.map(key))).toEqual([['TIP', 19], ['CAN', 12], ['GAP', 12], ['TIP', 29]])
    expect(kpa.filter((r) => r.boostCan != null).every((r) => r.boostCan === 0)).toBe(true)
    expect(kpa.find((r) => r.boostTip != null)!.boostTip).toBeCloseTo(PSI_TIP * KPA_PER_PSI, 1)       // 61.7 kPa
    const bar = pressureChartRows(tripChartData(session.trip!.points), 'bar')
    expect(bar.find((r) => r.boostTip != null)!.boostTip).toBeCloseTo(PSI_TIP * KPA_PER_PSI / 100, 3)  // 0.617 bar
    const peaks = pressurePeaks(session.trip!.summary.peakEvents, 'kPa').filter((e) => e.type === 'boost')
    expect(peaks.find((e) => e.source === 'TIP_033E')!.value).toBeCloseTo(PSI_TIP * KPA_PER_PSI, 1)
  })

  it('TripPanel names the sources and the selected unit', () => {
    useSettings.setState({ boostUnit: 'kPa' })
    useStore.setState({ sessions: [session], activeSessionId: session.id })
    const { container } = render(<TripPanel />)
    expect(container.textContent).toContain('Pressure (kPa)')              // two sources on one chart
    expect(container.textContent).not.toContain('(PSI)')
  })

  it('Dashboard trace series: TIP only, one value per sample, null where TIP was not measured', () => {
    expect(primaryBoostSource(session.trip!.points)).toBe('TIP_033E')
    const series = pressureSeries(session.trip!.points, 'TIP_033E', 'bar')
    expect(series).toHaveLength(72)
    expect(runs(series.map((v) => (v == null ? 'gap' : 'v')))).toEqual([['v', 19], ['gap', 24], ['v', 29]])
  })

  it('DashboardPanel trace: the gap breaks the line, samples keep their positions, unit converted', () => {
    act(() => {
      useSettings.setState({ boostUnit: 'bar' })
      useStore.setState({ sessions: [session], activeSessionId: session.id })
    })
    const { container } = render(<DashboardPanel />)
    expect(container.textContent).toContain('TIP Trace')
    expect(container.textContent).toMatch(/0[.,]62\s*bar/)                 // 8.95 psi peak shown in bar
    const card = [...container.querySelectorAll('span')].find((s) => s.textContent === 'TIP Trace')!.parentElement!
    const paths = [...card.querySelectorAll('path')].map((p) => p.getAttribute('d') ?? '')
    const line = paths.find((d) => d.startsWith('M') && !d.includes('Z'))!
    expect(line.match(/M/g)).toHaveLength(2)                              // [0..18] and [43..71], not joined
    expect(line.endsWith('120.0,' + line.split(',').pop())).toBe(true)    // last sample at the right edge
    expect(paths.some((d) => d.includes('Z') && d.length > 2)).toBe(false) // no fill drawn across the gap
  })
})
