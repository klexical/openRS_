import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import JSZip from 'jszip'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from './import'
import { parseThermalRecord, thermalRows, type ThermalRecord } from './thermalRecord'
import { ThermalRecordCard } from '../components/ui/ThermalRecordCard'
import type { Session } from '../types/session'

// #315: the Android profile export's `thermalRecord` block (DriveExportBuilder.buildThermalRecordJson) shown on Trip —
// the limits each part of the drive was judged against, the per-sensor peaks, start / end where the export has them, and
// the TEMP flags. Synthetic exports written by the app's builders (__fixtures__/android-export-rc6/, synthetic times).

vi.mock('../components/charts/GpsMap', () => ({ GpsMap: () => null }))   // Leaflet needs a real layout engine
vi.mock('./db', () => ({ deleteSession: vi.fn(), putSession: vi.fn() }))

const { useStore } = await import('../store')
const { useSettings } = await import('../store/settings')
const { TripPanel } = await import('../components/panels/TripPanel')
const { storeWithSessions } = await import('../test/storeState')

const ROOT = join(process.cwd(), 'src/lib/__fixtures__/android-export-rc6/')   // vitest root = web/

async function exportZip(name: string, edit?: (profile: Record<string, unknown>) => void): Promise<File> {
  const zip = new JSZip()
  for (const f of readdirSync(ROOT + name)) {
    let text = readFileSync(ROOT + name + '/' + f, 'utf8')
    if (edit && f.startsWith('drive_profile_')) { const p = JSON.parse(text); edit(p); text = JSON.stringify(p) }
    zip.file(f, text)
  }
  return new File([await zip.generateAsync({ type: 'blob' })], `openrs_export_${name}.zip`, { type: 'application/zip' })
}

const imported = async (name: string, edit?: (p: Record<string, unknown>) => void): Promise<Session> =>
  ({ ...(await importZip(await exportZip(name, edit))), id: name })

const card = (s: Session) => {
  const { container } = render(<ThermalRecordCard record={s.trip!.profile!.thermalRecord!} progression={s.trip!.thermalProgression} />)
  return container.textContent ?? ''
}
/** The row of the card's first table whose first cell is [label], as its cells' text. */
const row = (label: string): string[] => {
  const tbody = document.querySelector('tbody')
  const tr = tbody ? [...tbody.querySelectorAll('tr')].find((r) => r.querySelector('td')?.textContent === label) : undefined
  return tr ? [...tr.querySelectorAll('td')].map((td) => td.textContent ?? '') : []
}

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
  useSettings.setState({ unitSystem: 'metric' })
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('#315 parse: the block as the export holds it, nothing invented', () => {
  it.each([
    ['missing', undefined],
    ['null', null],
    ['empty', {}],
    ['basis none, reason absent', { basis: 'none', reason: 'absent' }],
  ])('%s → no thermal record (absent)', (_n, raw) => {
    expect(parseThermalRecord(raw)).toEqual({ basis: 'none', reason: 'absent' })
  })

  it.each([
    ['a string', 'profile'],
    ['an array', []],
    ['basis none, reason unreadable', { basis: 'none', reason: 'unreadable' }],
    ['an unknown basis', { basis: 'future', segments: [] }],
    ['profile without segments', { basis: 'profile', sensors: { oil: { peakC: 100 } } }],
    ['profile with no segment', { basis: 'profile', segments: [] }],
    ['a segment without a profile', { basis: 'profile', segments: [{ startOffsetMs: 0, limits: {}, peaksC: {} }] }],
    ['a segment with a string offset', { basis: 'profile', segments: [{ profile: 'street', startOffsetMs: '0' }] }],
    ['legacy without one readable sensor', { basis: 'legacy', sensors: { oil: { driveEndC: 'hot' } } }],
  ])('%s → unreadable', (_n, raw) => {
    expect(parseThermalRecord(raw)).toEqual({ basis: 'none', reason: 'unreadable' })
  })

  it('a value that is not a finite number is left out, never read as 0', () => {
    const r = parseThermalRecord({
      basis: 'profile', flagged: true,
      segments: [{ profile: 'street', startOffsetMs: 0,
        limits: { oil: { warnC: 120, critC: null, flagThresholdC: 123.5 }, coolant: { warnC: 105, critC: 115, flagThresholdC: 109.25 } },
        peaksC: { oil: '131', coolant: 99 } }],
      sensors: { oil: { peakC: null, flagged: true }, coolant: { peakC: 99, peakLevel: 'HOT', flagged: true, flag: { readingC: 'x' } } },
    })
    expect(r.basis).toBe('profile')
    if (r.basis !== 'profile') return
    expect(r.segments[0].limits).toEqual({ coolant: { warnC: 105, critC: 115, flagThresholdC: 109.25 } })
    expect(r.segments[0].peaksC).toEqual({ coolant: 99 })
    expect(r.sensors).toEqual({ coolant: { peakC: 99, peakProfile: null, peakLevel: null, flag: null } })
  })

  it('an endpoint above the record\'s peak, or missing, is unknown; the peak is never moved to fit', () => {
    const record: ThermalRecord = { basis: 'profile', flagged: false,
      segments: [{ profile: 'street', startOffsetMs: 0, limits: {}, peaksC: {} }],
      sensors: { oil: { peakC: 100, peakProfile: 'street', peakLevel: 'NORMAL', flag: null },
        coolant: { peakC: 90, peakProfile: 'street', peakLevel: 'NORMAL', flag: null } } }
    const rows = thermalRows(record, { oil: { startC: 101, peakC: 100, endC: 104 }, coolant: { startC: -99, peakC: 90, endC: 88 } })
    expect(rows).toEqual([
      { sensor: 'oil', startC: null, peakC: 100, endC: null, level: 'NORMAL' },
      { sensor: 'coolant', startC: null, peakC: 90, endC: 88, level: 'NORMAL' },
    ])
  })
})

describe('#315 the seven Android export variants import and show their record', () => {
  it('1-track: TRACK limits, four peaks with start and end, the two TEMP flags', async () => {
    const text = card(await imported('1-track'))
    expect(text).toContain('Limits: TRACK profile, saved with the drive')
    expect(row('Oil')).toEqual(['Oil', '62°C', '131°C', '112°C', 'CRIT'])
    expect(row('Coolant')).toEqual(['Coolant', '58°C', '104°C', '96°C', 'WARN'])
    expect(row('PTU')).toEqual(['PTU', '60°C', '88°C', '80°C', 'WARN'])
    expect(row('RDU')).toEqual(['RDU', '50°C', '92°C', '85°C', 'WARN'])
    expect(text).toContain('TRACK from 0:00')
    expect(text).toContain('TEMP FLAG Oil 131°C · flag threshold 114.0°C · TRACK')
    expect(text).toContain('TEMP FLAG RDU 92°C · flag threshold 90.3°C · TRACK')
    expect(text).not.toMatch(/TEMP FLAG (Coolant|PTU)/)
  })

  it('3-street-to-race: both segments with their own limits; the RACE flag below the STREET peak stays distinct', async () => {
    const text = card(await imported('3-street-to-race'))
    expect(text).toContain('Limits: STREET → RACE, each part judged by the profile in effect then')
    expect(row('Oil')).toEqual(['Oil', '70°C', '122°C', '104°C', 'WARN'])
    expect(row('RDU')).toEqual([])                                    // no RDU peak in the record: no row
    expect(text).toContain('STREET from 0:00')
    expect(text).toContain('RACE from 10:00')
    expect(text).toContain('TEMP FLAG Oil 106°C · flag threshold 104.5°C · RACE')
  })

  it('4-legacy: drive-end readings against the old fixed limits, never shown as peaks', async () => {
    const text = card(await imported('4-legacy'))
    expect(text).toContain('Recorded before limits were saved with drives')
    expect(row('Oil')).toEqual(['Oil', '134°C', '140°C', '133.0°C'])
    expect(row('RDU')).toEqual(['RDU', '110°C', '120°C', '114.0°C'])
    expect(text).toContain('TEMP FLAG Oil 134°C at drive end · flag threshold 133.0°C · old fixed limit')
    expect(text).not.toMatch(/Peak|TEMP FLAG RDU/)
  })

  it.each([
    ['5-no-record', 'No thermal limits were saved with this drive'],
    ['5b-unreadable', 'could not be read'],
  ])('%s: "No thermal record", no zeros', async (name, line) => {
    const text = card(await imported(name))
    expect(text).toContain('No thermal record')
    expect(text).toContain(line)
    expect(text).not.toMatch(/\d°/)
  })

  it('6-endpoints-above-peak: the oil row keeps its 100 °C peak with start and end unknown', async () => {
    card(await imported('6-endpoints-above-peak'))
    expect(row('Oil')).toEqual(['Oil', '—', '100°C', '—', 'NORMAL'])
    expect(row('Coolant')).toEqual(['Coolant', '45°C', '90°C', '88°C', 'NORMAL'])
    expect(row('RDU')).toEqual(['RDU', '—', '70°C', '—', 'NORMAL'])
  })

  it('6b-no-endpoints: peaks with no start or end at all', async () => {
    const text = card(await imported('6b-no-endpoints'))
    expect(row('Oil')).toEqual(['Oil', '—', '100°C', '—', 'NORMAL'])
    expect(row('RDU')).toEqual(['RDU', '—', '70°C', '—', 'NORMAL'])
    expect(text).not.toContain('TEMP FLAG')
  })

  it('an export whose profile has no thermalRecord block, or an empty one, reads "No thermal record"', async () => {
    for (const edit of [(p: Record<string, unknown>) => { delete p.thermalRecord }, (p: Record<string, unknown>) => { p.thermalRecord = {} }]) {
      expect(card(await imported('1-track', edit))).toContain('No thermal record')
      cleanup()
    }
  })

  it('temperatures follow the unit setting; limits to a tenth, converted after the 95 % (never "95 % of" °F)', async () => {
    const s = await imported('1-track')
    useSettings.setState({ unitSystem: 'imperial' })
    const text = card(s)
    expect(row('Oil')).toEqual(['Oil', '144°F', '268°F', '234°F', 'CRIT'])
    expect(text).toContain('TEMP FLAG Oil 268°F · flag threshold 237.2°F · TRACK')   // 114 °C → 237.2 °F
    expect(text).not.toMatch(/°C/)
  })
})

describe('#315 on Trip', () => {
  const trip = (s: Session) => {
    useStore.setState(storeWithSessions([s], s.id))
    return render(<TripPanel />).container
  }

  it('Trip shows the record under its own heading', async () => {
    const c = trip(await imported('3-street-to-race'))
    expect(c.textContent).toContain('Thermal Record')
    expect(c.querySelector('[data-testid="thermal-record"]')).not.toBeNull()
  })

  it('a drive imported before this change (a profile without the record) still opens, with no record section', async () => {
    const s = await imported('1-track')
    delete s.trip!.profile!.thermalRecord
    const c = trip(s)
    expect(c.textContent).toContain('Thermal Progression')
    expect(c.textContent).not.toContain('Thermal Record')
    expect(c.textContent).not.toContain('No thermal record')
  })

  it('a CSV-only drive (no profile) has no record section', async () => {
    const zip = new JSZip()
    zip.file('drive_20261026_120000.csv', readFileSync(ROOT + '1-track/drive_20261026_120000.csv', 'utf8'))
    const s = { ...(await importZip(new File([await zip.generateAsync({ type: 'blob' })], 'csv_only.zip'))), id: 'csv' }
    expect(trip(s).textContent).not.toContain('Thermal Record')
  })
})
