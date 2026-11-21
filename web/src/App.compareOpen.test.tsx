import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, act, screen, waitFor, within } from '@testing-library/react'
import type { Session } from './types/session'

// #416 ticket 3: Compare compares the drives that did open, and keeps a card when one drive lacks a
// reading. The whole App over an in-memory IndexedDB and the real storage module, passed through a mock that can
// fail one drive's read; only the map is stubbed. Invented data only, dated 2096.

const gate = vi.hoisted(() => ({ failLoad: new Set<string>() }))
vi.mock('./lib/storage', async (importOriginal) => {
  const real = await importOriginal<typeof import('./lib/storage')>()
  return {
    ...real,
    loadDrive: async (id: string) => {
      if (gate.failLoad.has(id)) throw new DOMException('invented read failure', 'UnknownError')
      return real.loadDrive(id)
    },
  }
})
vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

const T0 = 4_000_000_000_000
/** Invented drive [n]; [coords] false: an export with no coordinates (every point at 0/0, as the import stores it). */
function drive(id: string, n: number, summary: Record<string, unknown> = {}, coords = true): Session {
  return {
    id, name: `Invented ${id}`, importedAt: T0 + n, tags: [],
    trip: { points: [0, 1, 2].map((i) => ({ ts: T0 + n * 1e7 + i * 1000, lat: coords ? 10 + n / 100 : 0, lng: coords ? 20 + i / 1000 : 0,
      speedKph: 40 + i, rpm: 3000 + i })) as never,
      summary: { distanceKm: 1, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 41, peakRpm: 3002 + n, peakBoostPsi: null, peakLatG: 0,
        peakLongG: 0, peakSpeedKph: 42, peakCoolantC: 90, peakOilTempC: 100, avgRpm: 3001, avgFuelEconomy: 0, modeBreakdown: {},
        peakEvents: [], ...summary } },
    diagnostics: null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: new Date(T0 + n * 1e7).toISOString(), generatedAt: '' },
  }
}
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}
beforeEach(async () => {
  // the mock's real storage module outlives vi.resetModules: let go of its connection to the previous test's database
  ;(await import('./lib/storage')).closeStorage()
  gate.failLoad.clear()
  vi.stubGlobal('localStorage', memoryStorage())
  localStorage.setItem('sapphire_whats_new_seen', 'slice-2')
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
  window.history.replaceState(null, '', '/')
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

/** The whole app with [drives] stored, Compare open on [ids] (in ticked order). */
async function compare(drives: Session[], ids: string[]) {
  vi.resetModules()
  const storage = await import('./lib/storage')
  await storage.storeImported(drives)
  const { useStore } = await import('./store')
  const { default: App } = await import('./App')
  render(<App />)
  await waitFor(() => expect(useStore.getState().drives).toHaveLength(drives.length), { timeout: 3000 })
  await act(async () => { useStore.getState().setCompare(ids); useStore.getState().setCompareOpen(true) })
}
const alerts = () => screen.queryAllByRole('alert').map((e) => e.textContent)
const quiet = () => vi.spyOn(console, 'error').mockImplementation(() => {})

describe('#416 ticket 3: Compare compares the drives that did open', () => {
  it('one of three cannot be read: the line names it, and the other two are compared', async () => {
    gate.failLoad.add('c')
    const err = quiet()
    await compare([drive('a', 1), drive('b', 2), drive('c', 3)], ['a', 'b', 'c'])
    expect(await screen.findByText('Invented b vs Invented a', {}, { timeout: 5000 })).toBeInTheDocument()
    err.mockRestore()
    expect(alerts()).toEqual(["Couldn't open Invented c."])
    expect(document.body.textContent).not.toMatch(/Invented c vs/)
    expect(screen.queryByText(/^Base drive:/)).toBeNull()                    // the base drive opened: no line about it
  })

  it('the first ticked drive cannot be read: the first that did open is the base, and one line says so', async () => {
    gate.failLoad.add('a')
    const err = quiet()
    await compare([drive('a', 1), drive('b', 2), drive('c', 3)], ['a', 'b', 'c'])
    expect(await screen.findByText('Invented c vs Invented b', {}, { timeout: 5000 })).toBeInTheDocument()
    err.mockRestore()
    expect(alerts()).toEqual(["Couldn't open Invented a."])
    expect(screen.getByText("Base drive: Invented b (Invented a couldn't be opened).")).toBeInTheDocument()
    // the picker agrees: b is the base and c second; a, ticked but not opened, has no place in the comparison
    const tag = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name}`) }).textContent!.replace(/^.*v2\.2\.7/, '')
    expect([tag('Invented a'), tag('Invented b'), tag('Invented c')]).toEqual(['', 'Base', '#2'])
  })

  it('fewer than two open: only the lines and one sentence, no figures', async () => {
    gate.failLoad.add('b')
    const err = quiet()
    await compare([drive('a', 1), drive('b', 2)], ['a', 'b'])
    await waitFor(() => expect(alerts()).toEqual(["Couldn't open Invented b."]), { timeout: 5000 })
    err.mockRestore()
    expect(screen.getByText('Compare needs at least two drives that open.')).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/ vs /)
    expect(screen.queryByText('Peak RPM')).toBeNull()
  })

  it('every ticked drive opens: no line, no sentence', async () => {
    await compare([drive('a', 1), drive('b', 2)], ['a', 'b'])
    expect(await screen.findByText('Invented b vs Invented a', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(alerts()).toEqual([])
    expect(screen.queryByText('Compare needs at least two drives that open.')).toBeNull()
  })
})

describe('#416 ticket 3: a card when one drive lacks a reading', () => {
  const cardOf = (label: string) => screen.getByRole('group', { name: label })

  it('the compared drive lacks it: its figure is "—", the base\'s figure stays, no difference or percentage', async () => {
    await compare([drive('a', 1, { aggressionScore: 64 }), drive('b', 2)], ['a', 'b'])
    await screen.findByText('Invented b vs Invented a', {}, { timeout: 5000 })
    const card = cardOf('Aggression')
    expect(within(card).getByTestId('figure').textContent).toBe('—')
    expect(within(card).getByTestId('base').textContent).toBe('base: 64.0')
    expect(card.textContent).not.toMatch(/[▲▼%]|\+/)
  })

  it('the base drive lacks it: the figures only', async () => {
    await compare([drive('a', 1), drive('b', 2, { aggressionScore: 64 })], ['a', 'b'])
    await screen.findByText('Invented b vs Invented a', {}, { timeout: 5000 })
    const card = cardOf('Aggression')
    expect(within(card).getByTestId('figure').textContent).toBe('64.0')
    expect(within(card).getByTestId('base').textContent).toBe('base: —')
    expect(card.textContent).not.toMatch(/[▲▼%]|\+/)
  })

  it('a reading never received is never a value: the −99 marker shows as "—", never as a figure', async () => {
    await compare([drive('a', 1), drive('b', 2, { peakCoolantC: -99 })], ['a', 'b'])
    await screen.findByText('Invented b vs Invented a', {}, { timeout: 5000 })
    expect(within(cardOf('Peak Coolant')).getByTestId('figure').textContent).toBe('—')
    expect(document.body.textContent).not.toMatch(/-99/)
  })

  it('both drives with the reading: the figures and their difference, as before', async () => {
    await compare([drive('a', 1, { aggressionScore: 40 }), drive('b', 2, { aggressionScore: 64 })], ['a', 'b'])
    await screen.findByText('Invented b vs Invented a', {}, { timeout: 5000 })
    const card = cardOf('Aggression')
    expect(within(card).getByTestId('figure').textContent).toBe('64.0')
    expect(card.textContent).toMatch(/▲ \+24\.0/)
    expect(card.textContent).toMatch(/\(\+60\.0%\)/)
  })
})

// #416 ticket 3: a drive whose export has no coordinates never received a distance (its stored 0 is the
// sum of 0/0 points): "—" in Compare, and no difference.
describe('#416 ticket 3: a drive without coordinates has no distance reading', () => {
  const cardOf = (label: string) => screen.getByRole('group', { name: label })
  it('the Distance card: "—" for the drive without coordinates, the other\'s figure, no difference', async () => {
    await compare([drive('a', 1, { distanceKm: 31.4 }), drive('b', 2, { distanceKm: 0 }, false)], ['a', 'b'])
    await screen.findByText('Invented b vs Invented a', {}, { timeout: 5000 })
    const card = cardOf('Distance')
    expect(within(card).getByTestId('figure').textContent).toBe('—')
    expect(within(card).getByTestId('base').textContent).toBe('base: 31.4')
    expect(card.textContent).not.toMatch(/[▲▼%]|\+/)
  })
})

// #416 ticket 3: a ticked drive with no stored record has no place in the picker: the first drive that opened is the base.
describe('#416 ticket 3: a ticked drive no longer stored takes no place', () => {
  it('the drives that opened keep their places from the first one', async () => {
    await compare([drive('a', 1), drive('b', 2)], ['gone', 'a', 'b'])
    expect(await screen.findByText('Invented b vs Invented a', {}, { timeout: 5000 })).toBeInTheDocument()
    const tag = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name}`) }).textContent!.replace(/^.*v2\.2\.7/, '')
    expect([tag('Invented a'), tag('Invented b')]).toEqual(['Base', '#2'])
  })
})
