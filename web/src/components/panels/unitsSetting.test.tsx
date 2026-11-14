import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { render, cleanup, act } from '@testing-library/react'
import { Children, isValidElement, type ReactElement, type ReactNode } from 'react'
import JSZip from 'jszip'
import type { Session } from '../../types/session'

// #381: every chart, gauge and table value follows the units setting, not only its heading. Invented drives only,
// imported through the production ZIP path. Seams: what each Trip chart hands Recharts to plot (read at LineChart,
// a third-party boundary, because jsdom has no layout), the gauge's SVG text and the notes table's cell text.
// First written by QA as the reproduction for #381.

// Recharts boundary: each LineChart reports the values its <Line> series would plot and its peak lines' y.
vi.mock('recharts', async (importOriginal) => {
  const orig = await importOriginal<typeof import('recharts')>()
  const walk = (children: ReactNode, out: { lines: string[]; peaks: number[] }) => {
    Children.forEach(children, (c) => {
      if (!isValidElement(c)) return
      const el = c as ReactElement<{ dataKey?: unknown; y?: unknown; children?: ReactNode }>
      if (el.type === orig.Line && typeof el.props.dataKey === 'string') out.lines.push(el.props.dataKey)
      else if (el.type === orig.ReferenceLine && typeof el.props.y === 'number') out.peaks.push(el.props.y)
      else if (el.props.children) walk(el.props.children, out)
    })
    return out
  }
  return {
    ...orig,
    ResponsiveContainer: ({ children }: { children: ReactNode }) => <>{children}</>,
    LineChart: ({ data, children }: { data: Record<string, number | null>[]; children: ReactNode }) => {
      const { lines, peaks } = walk(children, { lines: [], peaks: [] })
      const series = Object.fromEntries(lines.map((k) => [k, data.map((r) => r[k] ?? null)]))
      return <div data-testid="line-chart" data-series={JSON.stringify(series)} data-peaks={JSON.stringify(peaks)} />
    },
  }
})
vi.mock('../charts/GpsMap', () => ({ GpsMap: () => null }))
vi.mock('../../lib/db', () => ({ deleteSession: vi.fn(), putSession: vi.fn() }))

const { importZip } = await import('../../lib/import')
const { useStore } = await import('../../store')
const { useSettings } = await import('../../store/settings')
const { TripPanel } = await import('./TripPanel')
const { DashboardPanel } = await import('./DashboardPanel')

const HDR = 'timestamp_ms,lat,lng,speed_kph,rpm,gear,boost_psi,boost_source,coolant_c,oil_c,ambient_c,rdu_c,ptu_c,fuel_pct,' +
  'tire_press_lf_psi,tire_press_rf_psi,tire_press_lr_psi,tire_press_rr_psi,tire_temp_lf_c,tire_temp_rf_c,tire_temp_lr_c,' +
  'tire_temp_rr_c,wheel_fl_kph,wheel_fr_kph,wheel_rl_kph,wheel_rr_kph,lateral_g,longitudinal_g,brake_pressure,' +
  'steering_angle,throttle_pct,drive_mode,race_ready'

interface Row { kph: number; coolant: number; oil: number; press: number; tireTemp: number }
function csv(rows: Row[]): string {
  const out = ['# openrs_csv_v2', HDR]
  rows.forEach((r, i) => {
    const sp = r.kph.toFixed(2), b = (r.kph / 10).toFixed(3)
    out.push(`${1700000000000 + i * 1000},,,${sp},3000,,${b},CAN_0F8,${r.coolant},${r.oil},20.0,50.0,60.0,50.0,` +
      `${r.press},${r.press},${r.press},${r.press},${r.tireTemp},${r.tireTemp},${r.tireTemp},${r.tireTemp},` +
      `${sp},${sp},${sp},${sp},0.1,0.05,0.0,0.0,40.0,Normal,false`)
  })
  return out.join('\n') + '\n'
}
async function importDrive(rows: Row[], name: string): Promise<Session> {
  const zip = new JSZip()
  zip.file('drive_20990101_000000.csv', csv(rows))
  zip.file('drive_profile_20990101_000000.json', JSON.stringify({
    version: 1, bookmarks: [{ timestamp: 1700000100000, relMs: 100000, label: 'invented note', speedKph: 100, rpm: 3000 }],
  }))
  return importZip(new File([await zip.generateAsync({ type: 'blob' })], name))
}

// Drive A: 201 points at 1 Hz, speed and wheel speeds 0 → 200 km/h, coolant 90 °C, oil 100 °C, tyres 36 psi and
// 30 °C, boost 0 → 20 psi from CAN 0x0F8; one observer's note at 100 km/h.
// Drive B (gaps): the first 3 samples carry the sentinels (temperatures −99, tyre pressure −1), then genuine zeros
// (0 km/h, 0 °C, 0 psi), then readings.
let drive: Session
let gaps: Session
beforeAll(async () => {
  drive = await importDrive(Array.from({ length: 201 }, (_, i) => ({ kph: i, coolant: 90, oil: 100, press: 36, tireTemp: 30 })),
    'invented-drive.zip')
  gaps = await importDrive([
    ...Array.from({ length: 3 }, () => ({ kph: 0, coolant: -99, oil: -99, press: -1, tireTemp: -99 })),
    { kph: 0, coolant: 0, oil: 0, press: 0, tireTemp: 0 },
    { kph: 100, coolant: 100, oil: 100, press: 36, tireTemp: 30 },
  ], 'invented-gaps.zip')
  expect(drive.trip!.points).toHaveLength(201)
  expect(drive.trip!.bookmarks).toHaveLength(1)
})
const show = (s: Session) => useStore.setState({ sessions: [s], activeSessionId: s.id, compareSessionIds: [] })
beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
  show(drive)
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

const units = (s: Partial<ReturnType<typeof useSettings.getState>>) => act(() => { useSettings.setState(s) })
const IMPERIAL_BAR = { unitSystem: 'imperial', boostUnit: 'PSI', tirePressUnit: 'bar' } as const

/** The first chart after the section heading whose text starts with `heading`. */
function chartAfter(container: HTMLElement, heading: string): Element {
  const h = [...container.querySelectorAll('*')].find((e) => e.children.length === 0 && e.textContent!.trim().startsWith(heading))
  if (!h) throw new Error(`no heading starting "${heading}"`)
  const chart = [...container.querySelectorAll('[data-testid="line-chart"]')]
    .find((c) => h.compareDocumentPosition(c) & Node.DOCUMENT_POSITION_FOLLOWING)
  if (!chart) throw new Error(`no chart after "${heading}"`)
  return chart
}
const seriesAfter = (c: HTMLElement, heading: string): Record<string, (number | null)[]> =>
  JSON.parse(chartAfter(c, heading).getAttribute('data-series')!)
const peaksAfter = (c: HTMLElement, heading: string): number[] => JSON.parse(chartAfter(c, heading).getAttribute('data-peaks')!)
const maxAfter = (c: HTMLElement, heading: string): number =>
  Math.max(...Object.values(seriesAfter(c, heading)).flat().filter((v): v is number => v != null))

describe('#381 Trip charts plot in the selected units', () => {
  it('control: the boost chart converts (20 psi plotted as about 137.9 kPa)', () => {
    units({ ...IMPERIAL_BAR, boostUnit: 'kPa' })
    const { container } = render(<TripPanel />)
    expect(maxAfter(container, 'Boost 0x0F8 (kPa)')).toBeCloseTo(137.9, 0)
  })

  it('metric stays as recorded: Speed (KPH) peaks at 200, temperatures at 100 °C, tyres at 36 psi', () => {
    units({ unitSystem: 'metric', boostUnit: 'PSI', tirePressUnit: 'PSI' })
    const { container } = render(<TripPanel />)
    expect(maxAfter(container, 'Speed (KPH)')).toBeCloseTo(200, 5)
    expect(maxAfter(container, 'Temperatures (°C)')).toBeCloseTo(100, 5)
    expect(maxAfter(container, 'Tire Pressure (PSI)')).toBeCloseTo(36, 5)
  })

  it('"Speed (MPH)" plots mph: 200 km/h → about 124.3, and its peak line too', () => {
    units(IMPERIAL_BAR)
    const { container } = render(<TripPanel />)
    expect(maxAfter(container, 'Speed (MPH)')).toBeCloseTo(124.3, 0)
    const peaks = peaksAfter(container, 'Speed (MPH)')
    expect(peaks).toHaveLength(1)
    expect(peaks[0]).toBeCloseTo(124.3, 0)
  })

  it('"Temperatures (°F)" plots °F: oil 100 °C → 212', () => {
    units(IMPERIAL_BAR)
    const { container } = render(<TripPanel />)
    expect(maxAfter(container, 'Temperatures (°F)')).toBeCloseTo(212, 0)
  })

  it('"Tire Pressure (bar)" plots bar: 36 psi → about 2.48', () => {
    units(IMPERIAL_BAR)
    const { container } = render(<TripPanel />)
    expect(maxAfter(container, 'Tire Pressure (bar)')).toBeCloseTo(2.48, 1)
  })

  it('"Tire Temperature (°F)" plots °F: 30 °C → 86', () => {
    units(IMPERIAL_BAR)
    const { container } = render(<TripPanel />)
    expect(maxAfter(container, 'Tire Temperature (°F)')).toBeCloseTo(86, 0)
  })

  it('"Wheel Speeds" plots mph under imperial: 200 km/h → about 124.3', () => {
    units(IMPERIAL_BAR)
    const { container } = render(<TripPanel />)
    expect(maxAfter(container, 'Wheel Speeds')).toBeCloseTo(124.3, 0)
  })

  it('a missing reading stays a gap after conversion; a genuine zero converts (0 °C → 32 °F, 0 km/h and 0 psi stay 0)', () => {
    units(IMPERIAL_BAR)
    show(gaps)
    const { container } = render(<TripPanel />)
    const temps = seriesAfter(container, 'Temperatures (°F)')
    expect(temps.coolantC.slice(0, 4)).toEqual([null, null, null, 32])
    expect(temps.oilTempC[4]).toBeCloseTo(212, 5)
    const press = seriesAfter(container, 'Tire Pressure (bar)')
    expect(press.tirePressLF.slice(0, 4)).toEqual([null, null, null, 0])
    expect(press.tirePressLF[4]).toBeCloseTo(2.48, 2)
    const tireTemps = seriesAfter(container, 'Tire Temperature (°F)')
    expect(tireTemps.tireTempRR.slice(0, 4)).toEqual([null, null, null, 32])
    const speed = seriesAfter(container, 'Speed (MPH)')
    expect(speed.speedKph[0]).toBe(0)
    expect(speed.speedKph[4]).toBeCloseTo(62.1, 1)
  })
})

describe('#381 Dashboard values follow the units setting', () => {
  it('the "Peak Speed" gauge shows 124 MPH for a 200 km/h peak', () => {
    units(IMPERIAL_BAR)
    const { container } = render(<DashboardPanel />)
    const label = [...container.querySelectorAll('*')].find((e) => e.children.length === 0 && e.textContent!.trim() === 'Peak Speed')!
    let box: Element | null = label.parentElement
    while (box && !box.querySelector('svg')) box = box.parentElement
    const texts = [...box!.querySelector('svg')!.querySelectorAll('text')].map((t) => t.textContent)
    expect(texts).toEqual(['124', 'MPH'])
  })

  it('the observer\'s-notes table shows the note\'s speed in mph: 100 km/h → 62 MPH', () => {
    units(IMPERIAL_BAR)
    const { container } = render(<DashboardPanel />)
    const row = [...container.querySelectorAll('tr')].find((r) => r.textContent!.includes('invented note'))!
    expect(row.textContent).toContain('62 MPH')
    expect(row.textContent).not.toMatch(/kph/i)
  })
})
