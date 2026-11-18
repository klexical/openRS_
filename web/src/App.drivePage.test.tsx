import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import type { Session } from './types/session'

// #416 slice 3: a drive's page. Ticket 1: the page shows the drive its address names, read from the Drive library, from
// the list, from its address, after a reload, and from one drive's address straight to another's. The whole App over an
// in-memory IndexedDB; what is checked is what the page shows. Invented data only, dated 2096.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

const T0 = new Date(2096, 10, 12, 18, 0).getTime()
function drive(id: string, name: string, startOffsetMs = 0): Session {
  const t0 = T0 + startOffsetMs
  const points = Array.from({ length: 5 }, (_, i) => ({
    ts: t0 + i * 1000, lat: 10 + id.charCodeAt(0) / 1000, lng: 20 + i / 1000, speedKph: 40 + i, rpm: 3000 + 100 * i, boostPsi: null,
    coolantC: 90, oilTempC: 95, driveMode: 'Normal',
  }))
  return {
    id, name, importedAt: t0 + 3_600_000, tags: [],
    trip: { points: points as never,
      summary: { distanceKm: 1, distanceSource: 'recorded', durationMs: 4000, fuelUsedL: 0, avgSpeedKph: 42, peakRpm: 3000,
        peakBoostPsi: null, peakLatG: 0, peakLongG: 0, peakSpeedKph: 44, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 3000,
        avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [] } },
    diagnostics: null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: new Date(t0).toISOString(), generatedAt: '' },
  }
}

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}
async function seed(sessions: Session[]) {
  vi.resetModules()
  const storage = await import('./lib/storage')
  await storage.storeImported(sessions)
  storage.closeStorage()
}
/** Fresh modules and the App at [hash]: a reload. */
async function boot(hash = '') {
  cleanup()
  window.history.replaceState(null, '', `/${hash}`)
  vi.resetModules()
  const { default: App } = await import('./App')
  render(<App />)
}
async function list() {
  const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
  await waitFor(() => expect(root).toHaveAttribute('aria-busy', 'false'), { timeout: 3000 })
  return root
}
const pageName = () => screen.getByRole('heading', { level: 1 }).textContent
/** The name the open view shows: the Dashboard's heading, drawn from the drive the view was given. */
const viewName = () => screen.queryByRole('heading', { level: 2 })?.textContent ?? null
async function shows(name: string) {
  await waitFor(() => { expect(pageName()).toBe(name); expect(viewName()).toBe(name) }, { timeout: 3000 })
}

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  localStorage.setItem('sapphire_whats_new_seen', 'slice-2')     // no dialog over the page
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

describe('#416 ticket 1: the page shows the drive its address names, read from the Drive library', () => {
  it('from the list, from its address and after a reload: the same drive, in the page and in its view', async () => {
    await seed([drive('a', 'Invented loop'), drive('b', 'Invented commute', -86_400_000)])
    await boot()
    const link = within(await list()).getByRole('link', { name: 'Invented commute' })
    await act(async () => { fireEvent.click(link); window.location.hash = link.getAttribute('href')! })
    await shows('Invented commute')
    expect(window.location.hash).toBe('#/drive/b')

    await boot('#/drive/b')                                    // the address, typed or after a reload
    await shows('Invented commute')
  })

  it("from one drive's address straight to another's: the second drive, and the first one's view does not stay", async () => {
    await seed([drive('a', 'Invented loop'), drive('b', 'Invented commute', -86_400_000)])
    await boot('#/drive/a')
    await shows('Invented loop')
    const seen: string[] = []
    const watch = new MutationObserver(() => {
      const h1 = document.querySelector('h1')?.textContent
      const h2 = document.querySelector('h2')?.textContent
      if (h1 && h2 && h1 !== h2) seen.push(`${h1} / ${h2}`)
    })
    watch.observe(document.body, { childList: true, subtree: true, characterData: true })
    await act(async () => { window.location.hash = '#/drive/b' })
    await shows('Invented commute')
    watch.disconnect()
    expect(seen).toEqual([])                                   // never one drive's page over the other drive's view
    await act(async () => { window.history.back() })
    await shows('Invented loop')
  })

  it('an unknown drive address shows the list with a plain message', async () => {
    await seed([drive('a', 'Invented loop')])
    await boot('#/drive/not-stored-here')
    await list()
    await waitFor(() => expect(screen.getByText('That drive is not stored in this browser.')).toBeInTheDocument())
    expect(window.location.hash).toBe('#/')
  })

  it("today's views still open from the drive", async () => {
    await seed([drive('a', 'Invented loop')])
    await boot('#/drive/a')
    await shows('Invented loop')
    const views = screen.getByRole('navigation', { name: 'Views of this drive' })
    expect(within(views).getAllByRole('button').map((b) => b.textContent)).toEqual(['Dashboard', 'Trip', 'Diagnostics'])
    fireEvent.click(within(views).getByRole('button', { name: 'Trip' }))
    // the view is in the address (ticket 2): the switch lands with the hash change; the Dashboard has a Route heading too
    await waitFor(() => expect(within(views).getByRole('button', { name: 'Trip' })).toHaveAttribute('aria-pressed', 'true'))
    expect(await screen.findByRole('heading', { level: 3, name: 'Route' }, { timeout: 3000 })).toBeInTheDocument()
    expect(viewName()).toBe('Invented loop')
    fireEvent.click(within(views).getByRole('button', { name: 'Diagnostics' }))
    await waitFor(() => expect(within(views).getByRole('button', { name: 'Diagnostics' })).toHaveAttribute('aria-pressed', 'true'))
    // this invented drive has no diagnostic log: the view says so for this drive (not "No Session Selected")
    expect(await screen.findByRole('heading', { level: 3, name: 'No Diagnostic Data' }, { timeout: 3000 })).toBeInTheDocument()
  })
})

// Ticket 2: one selection holds the page's time range; the address carries the view and the range. Each invented drive
// has five points a second apart (0:00 to 0:04), RPM 3000, 3100, 3200, 3300, 3400, and a stored Avg RPM of 3000.
const card = (label: string) => screen.getByText(label, { selector: 'span' }).parentElement!.textContent
const readout = () => screen.getByRole('status', { name: 'Range' }).textContent
const WHOLE_LINE = 'Fuel, aggression, thermal progression and tyre pressures are for the whole drive.'

describe('#416 ticket 2: one selection, held in the address', () => {
  it('loading an address restores the view and the range (a reload keeps the place)', async () => {
    await seed([drive('a', 'Invented loop')])
    await boot('#/drive/a/trip?range=1000-3000')
    expect(await screen.findByRole('heading', { level: 3, name: 'Route' }, { timeout: 3000 })).toBeInTheDocument()
    expect(readout()).toBe('Range: 0:01 – 0:03 of 0:04')
    expect(screen.getByRole('button', { name: 'Whole drive' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/drive/a/trip?range=1000-3000')
  })

  it('the summary numbers follow the range; "Whole drive" resets it in one step, in place', async () => {
    await seed([drive('a', 'Invented loop')])
    await boot('#/drive/a?range=1000-2000')
    await waitFor(() => expect(card('Avg RPM')).toMatch(/^Avg RPM3150$/), { timeout: 3000 })   // (3100 + 3200) / 2
    expect(card('Duration')).toBe('Duration1s')
    expect(screen.getByText(WHOLE_LINE)).toBeInTheDocument()
    const steps = window.history.length
    fireEvent.click(screen.getByRole('button', { name: 'Whole drive' }))
    await waitFor(() => expect(card('Avg RPM')).toMatch(/^Avg RPM3000$/))                  // the stored summary, as before
    expect(readout()).toBe('Range: Whole drive')
    expect(screen.queryByRole('button', { name: 'Whole drive' })).toBeNull()
    expect(screen.queryByText(WHOLE_LINE)).toBeNull()
    expect(window.location.hash).toBe('#/drive/a')
    expect(window.history.length).toBe(steps)                                              // no Back step for a range
  })

  it('a change of view keeps the range and adds a Back step; Back restores the view and the range', async () => {
    await seed([drive('a', 'Invented loop')])
    await boot('#/drive/a?range=1000-2000')
    await waitFor(() => expect(card('Avg RPM')).toMatch(/3150$/), { timeout: 3000 })
    const views = screen.getByRole('navigation', { name: 'Views of this drive' })
    await act(async () => { fireEvent.click(within(views).getByRole('button', { name: 'Trip' })) })
    await waitFor(() => expect(window.location.hash).toBe('#/drive/a/trip?range=1000-2000'))
    expect(within(views).getByRole('button', { name: 'Trip' })).toHaveAttribute('aria-pressed', 'true')
    expect(readout()).toBe('Range: 0:01 – 0:02 of 0:04')
    await act(async () => { window.history.back() })
    await waitFor(() => expect(window.location.hash).toBe('#/drive/a?range=1000-2000'))
    await waitFor(() => expect(card('Avg RPM')).toMatch(/3150$/))
  })

  it('an unknown view or a garbled range: the first view on the whole drive, no message, the address put right in place', async () => {
    await seed([drive('a', 'Invented loop')])
    await boot('#/drive/a/engine?range=soon')
    const steps = window.history.length
    await shows('Invented loop')                                                           // the Dashboard: the first view
    await waitFor(() => expect(window.location.hash).toBe('#/drive/a'))
    expect(readout()).toBe('Range: Whole drive')
    expect(screen.queryAllByRole('alert')).toEqual([])
    expect(window.history.length).toBe(steps)
  })

  it('a range is clamped to the drive, in the address too; one that covers the drive is the whole drive', async () => {
    await seed([drive('a', 'Invented loop')])
    await boot('#/drive/a/trip?range=2000-90000')
    await waitFor(() => expect(window.location.hash).toBe('#/drive/a/trip?range=2000-4000'), { timeout: 3000 })
    expect(readout()).toBe('Range: 0:02 – 0:04 of 0:04')
    await boot('#/drive/a?range=0-4000')
    await waitFor(() => expect(window.location.hash).toBe('#/drive/a'), { timeout: 3000 })
    expect(readout()).toBe('Range: Whole drive')
  })

  it('another drive opens on its whole drive: the range was the first drive\'s', async () => {
    await seed([drive('a', 'Invented loop'), drive('b', 'Invented commute', -86_400_000)])
    await boot('#/drive/a?range=1000-2000')
    await waitFor(() => expect(card('Avg RPM')).toMatch(/3150$/), { timeout: 3000 })
    await act(async () => { window.location.hash = '#/drive/b' })
    await shows('Invented commute')
    expect(readout()).toBe('Range: Whole drive')
    await waitFor(() => expect(card('Avg RPM')).toMatch(/^Avg RPM3000$/))
  })
})

// The owner's Q152 with QA's C3: while a range is set, thermal progression (a start, peak and end of the whole drive) is
// left out on Trip as on the Dashboard. Trip says so in its own line (the owner's Q154): its tyre-pressure charts follow
// the range, so the Dashboard's line, which names tyre pressures, would be wrong there.
const TRIP_LINE = 'Thermal progression is for the whole drive.'
describe('#416 ticket 2: thermal progression is whole-drive on both views (Q152, QA C3)', () => {
  const thermal = { oil: { startC: 40, peakC: 101, endC: 96 }, coolant: { startC: 35, peakC: 92, endC: 90 } }
  const withThermal = () => { const d = drive('a', 'Invented loop'); d.trip!.thermalProgression = thermal; return d }
  const heading = () => screen.queryByRole('heading', { level: 3, name: 'Thermal Progression' })

  it('Trip, while a range is set: no thermal progression, and Trip\'s own line (Q154), not the Dashboard\'s', async () => {
    await seed([withThermal()])
    await boot('#/drive/a/trip?range=1000-2000')
    expect(await screen.findByRole('heading', { level: 3, name: 'Route' }, { timeout: 3000 })).toBeInTheDocument()
    expect(heading()).toBeNull()
    expect(screen.getByText(TRIP_LINE)).toBeInTheDocument()
    expect(screen.queryByText(WHOLE_LINE)).toBeNull()
  })

  it('Trip, on the whole drive: thermal progression as before, no line', async () => {
    await seed([withThermal()])
    await boot('#/drive/a/trip')
    expect(await screen.findByRole('heading', { level: 3, name: 'Thermal Progression' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByText(WHOLE_LINE)).toBeNull()
    expect(screen.queryByText(TRIP_LINE)).toBeNull()
  })
})
