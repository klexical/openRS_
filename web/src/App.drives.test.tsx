import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import { openDB } from 'idb'
import type { Session } from './types/session'

// #384 ticket 03 at the page: home is the list of drives, with addresses. The whole App over an in-memory IndexedDB
// (fake-indexeddb) and the real storage module; only the map is stubbed (Leaflet needs a layout engine). Invented data
// only: every drive is dated in 2096, built with local-time constructors so the month grouping is the browser's.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

interface Spec {
  id: string; name: string; start: Date
  minutes?: number; km?: number; topKph?: number; boostPsi?: number | null
  /** Every point at time 0 (the first Sapphire builds; spec draft 10, known limit). */
  timeZero?: boolean
  /** A session with no drive. */
  sessionOnly?: boolean
  importedAt?: number
}

function drive(s: Spec): Session {
  const t0 = s.start.getTime()
  const n = 3
  const points = s.sessionOnly ? [] : Array.from({ length: n }, (_, i) => ({
    ts: s.timeZero ? 0 : t0 + i * 1000,
    // content differs per drive: two drives with equal points are one drive ("already imported")
    lat: 10 + s.id.length / 100 + s.id.charCodeAt(s.id.length - 1) / 10_000, lng: 20 + i / 1000,
    speedKph: s.topKph ?? 80, rpm: 2500, driveMode: 'Normal',
  }))
  return {
    id: s.id, name: s.name, importedAt: s.importedAt ?? t0 + 3_600_000, tags: [],
    trip: s.sessionOnly ? null : {
      points: points as never,
      summary: {
        distanceKm: s.km ?? 12.3, distanceSource: 'recorded', durationMs: (s.minutes ?? 20) * 60_000, fuelUsedL: 0,
        avgSpeedKph: 50, peakRpm: 5000, peakBoostPsi: s.boostPsi === undefined ? 14.2 : s.boostPsi, peakBoostSource: 'CAN_0F8',
        peakLatG: 0, peakLongG: 0, peakSpeedKph: s.topKph ?? 80, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 2500,
        avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [],
      },
    } as never,
    diagnostics: s.sessionOnly ? {
      sessionEvents: [{ ts: 0, type: 'STATE', message: 'Connected' }], sessionDurationMs: 360_000,
    } as never : null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: s.start.toISOString(), generatedAt: '' },
  }
}

/** Fresh store, storage and App modules: a reload. */
async function boot(hash = '') {
  window.history.replaceState(null, '', `/${hash}`)
  vi.resetModules()
  const { useStore } = await import('./store')
  const storage = await import('./lib/storage')
  const { default: App } = await import('./App')
  return { useStore, storage, App }
}

async function seed(specs: Spec[]) {
  vi.resetModules()   // a storage module from an earlier test would still hold the earlier test's database
  const storage = await import('./lib/storage')
  await storage.storeImported(specs.map(drive))
  storage.closeStorage()
}

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}

// Reads of the points store, counted by an observer on the object store (as the browser harness does)
const g = globalThis as unknown as Record<string, { prototype: Record<string, unknown> }>
const originals: [Record<string, unknown>, string, unknown][] = []
const pointReads: string[] = []
function observePoints() {
  for (const [proto, of] of [
    [g.IDBObjectStore.prototype, (s: unknown) => (s as { name: string }).name],
    [g.IDBIndex.prototype, (i: unknown) => (i as { objectStore: { name: string } }).objectStore.name],
  ] as const) {
    for (const m of ['get', 'getAll', 'getAllKeys', 'openCursor', 'count', 'getKey']) {
      const orig = proto[m] as (...a: unknown[]) => unknown
      originals.push([proto, m, orig])
      proto[m] = function (this: unknown, ...a: unknown[]) { if (of(this) === 'points') pointReads.push(m); return orig.apply(this, a) }
    }
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
  pointReads.length = 0
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  for (const [proto, name, orig] of originals.splice(0).reverse()) proto[name] = orig
  window.history.replaceState(null, '', '/')
})

/** The list, once its records were read (the region is busy until then). */
async function list() {
  const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
  await waitFor(() => expect(root).toHaveAttribute('aria-busy', 'false'), { timeout: 3000 })
  return root
}
/** Month headings and row dates as the browser's locale writes them (the page uses the browser's locale). */
const month = (y: number, m: number) => new Date(y, m, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
const monthHeadings = (root: HTMLElement) => within(root).getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
const rowNames = (root: HTMLElement) => within(root).getAllByRole('listitem').map((li) => within(li).getByRole('link').textContent)

describe('#384 ticket 03: home is the list of drives', () => {
  it('opens on the list: rows newest first by drive start, grouped by month in local time, as real lists', async () => {
    await seed([
      { id: 'sep', name: 'September errand', start: new Date(2096, 8, 14, 9, 0) },
      { id: 'oct-last', name: 'Last night of October', start: new Date(2096, 9, 31, 23, 30) },
      { id: 'nov-first', name: 'First minutes of November', start: new Date(2096, 10, 1, 0, 30) },
      // imported long before the others: the order is the drive's start, not the import time
      { id: 'nov-late', name: 'Late November run', start: new Date(2096, 10, 20, 15, 0), importedAt: new Date(2096, 0, 1).getTime() },
    ])
    const { App } = await boot()
    render(<App />)
    const root = await list()
    expect(monthHeadings(root)).toEqual([month(2096, 10), month(2096, 9), month(2096, 8)])
    expect(rowNames(root)).toEqual(['Late November run', 'First minutes of November', 'Last night of October', 'September errand'])
    // each month is a list of its rows, to a screen reader
    const lists = within(root).getAllByRole('list')
    expect(lists.map((l) => within(l).getAllByRole('listitem').length)).toEqual([2, 1, 1])
  })

  it('a row shows date, name, duration, distance, top speed and peak boost named by its measurement, in the units chosen', async () => {
    await seed([{ id: 'a', name: 'Hill road', start: new Date(2096, 9, 18, 10, 12), minutes: 44, km: 51.3, topKph: 160, boostPsi: 18.5 }])
    const { App } = await boot()
    render(<App />)
    const row = within(await list()).getByRole('listitem')
    expect(row.textContent).toContain('Hill road')
    expect(row.textContent).toContain(new Date(2096, 9, 18).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' }))
    expect(row.textContent).toContain('44m 0s')
    expect(row.textContent).toContain('51.3 km')
    expect(row.textContent).toContain('160 KPH')
    expect(row.textContent).toMatch(/Peak boost/i)
    expect(row.textContent).toContain('18.5 PSI')

    cleanup()
    localStorage.setItem('sapphire_settings', JSON.stringify({ unitSystem: 'imperial', boostUnit: 'bar' }))
    const again = await boot()
    render(<again.App />)
    const row2 = within(await list()).getByRole('listitem')
    expect(row2.textContent).toContain('31.9 mi')
    expect(row2.textContent).toContain('99 MPH')
    expect(row2.textContent).toContain('1.28 bar')
  })

  it('a session-only entry is in the same list, with a dash where a drive has numbers', async () => {
    await seed([
      { id: 'd', name: 'A drive', start: new Date(2096, 9, 2, 8, 0) },
      { id: 's', name: 'A scan at the kerb', start: new Date(2096, 9, 3, 8, 0), sessionOnly: true },
    ])
    const { App } = await boot()
    render(<App />)
    const root = await list()
    expect(rowNames(root)).toEqual(['A scan at the kerb', 'A drive'])
    const scan = within(root).getAllByRole('listitem')[0]
    expect(scan.textContent).toContain('—')
    expect(scan.textContent).not.toMatch(/KPH|km\b/)
  })

  it('a drive whose points are all at time 0 does not sort into January 1970: it takes the stated session start', async () => {
    await seed([
      { id: 'zero', name: 'Early-build drive', start: new Date(2096, 3, 2, 12, 0), timeZero: true },
      { id: 'later', name: 'Later drive', start: new Date(2096, 5, 1, 12, 0) },
    ])
    const { App } = await boot()
    render(<App />)
    const root = await list()
    expect(monthHeadings(root)).toEqual([month(2096, 5), month(2096, 3)])
    expect(root.textContent).not.toMatch(/1970/)
  })

  it('with 50 drives stored, the list reads no points', async () => {
    await seed(Array.from({ length: 50 }, (_, i) => ({ id: `d${String(i).padStart(2, '0')}`, name: `Drive ${i}`, start: new Date(2096, 0, 1 + i, 9, 0) })))
    observePoints()
    const { App, useStore } = await boot()
    render(<App />)
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(50), { timeout: 3000 })
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })   // anything that would open a drive by itself
    expect(pointReads).toEqual([])
    const root = await list()
    expect(within(root).getAllByRole('listitem')).toHaveLength(50)
  })

  it('with no drives stored: a plain empty state with the Import button and where the ZIP comes from', async () => {
    const { App } = await boot()
    render(<App />)
    const empty = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
    await waitFor(() => expect(empty.textContent).toMatch(/No drives stored/i))
    expect(within(empty).getByRole('button', { name: 'Import' })).toBeInTheDocument()
    expect(empty.textContent).toMatch(/openRS_ app/)
    expect(empty.textContent).toMatch(/ZIP/)
  })

  it('the empty state is not shown while the storage update waits on another tab', async () => {
    const old = await openDB('sapphire', 1, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
    await old.put('sessions', drive({ id: 'held', name: 'Held drive', start: new Date(2096, 1, 1) }))
    const { App } = await boot()
    render(<App />)
    await waitFor(() => expect(document.body.textContent).toContain("Close Sapphire's other tabs to finish the update"))
    expect(document.body.textContent).not.toMatch(/No drives stored/i)
    old.close()
    const root = await list()
    await waitFor(() => expect(rowNames(root)).toEqual(['Held drive']))
  })
})

describe('#384 ticket 03: places and addresses', () => {
  it('two places, Drives and Settings, as links with the current one marked', async () => {
    const { App } = await boot()
    render(<App />)
    const nav = await screen.findByRole('navigation', { name: 'Places' })
    const links = within(nav).getAllByRole('link')
    expect(links.map((a) => a.textContent?.trim())).toEqual(['Drives', 'Settings'])
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['#/', '#/settings'])
    expect(links[0]).toHaveAttribute('aria-current', 'page')
    await act(async () => { window.location.hash = '#/settings' })
    await waitFor(() => expect(within(nav).getByRole('link', { name: 'Settings' })).toHaveAttribute('aria-current', 'page'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
  })

  it("opening a row goes to the drive's address and shows it, with its Dashboard, Trip and Diagnostics reached from the drive", async () => {
    await seed([{ id: 'a', name: 'Evening loop', start: new Date(2096, 10, 12, 18, 42) }])
    const { App, useStore } = await boot()
    render(<App />)
    const link = within(await list()).getByRole('link', { name: 'Evening loop' })
    expect(link).toHaveAttribute('href', '#/drive/a')
    await act(async () => { window.location.hash = link.getAttribute('href')! })
    expect(await screen.findByRole('heading', { level: 1, name: 'Evening loop' })).toBeInTheDocument()
    const views = screen.getByRole('navigation', { name: 'Views of this drive' })
    expect(within(views).getAllByRole('button').map((b) => b.textContent)).toEqual(['Dashboard', 'Trip', 'Diagnostics'])
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('a'))
    // back to the list: the drive's points are let go
    await act(async () => { window.location.hash = '#/' })
    await list()
    expect(useStore.getState().activeSession).toBeNull()
  })

  it("a reload on a drive's address shows that drive", async () => {
    await seed([
      { id: 'a', name: 'Evening loop', start: new Date(2096, 10, 12, 18, 42) },
      { id: 'b', name: 'Morning commute', start: new Date(2096, 10, 2, 7, 51) },
    ])
    const { App, useStore } = await boot('#/drive/b')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Morning commute' }, { timeout: 3000 })).toBeInTheDocument()
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('b'))
  })

  it('an unknown id shows the list with a plain message, and the address becomes the list', async () => {
    await seed([{ id: 'a', name: 'Evening loop', start: new Date(2096, 10, 12, 18, 42) }])
    const { App } = await boot('#/drive/not-stored-here')
    render(<App />)
    const root = await list()
    await waitFor(() => expect(document.body.textContent).toContain('That drive is not stored in this browser.'))
    expect(rowNames(root)).toEqual(['Evening loop'])
    expect(window.location.hash).toBe('#/')
  })

  it('a malformed address does not stop Sapphire: it shows the list', async () => {
    await seed([{ id: 'a', name: 'Evening loop', start: new Date(2096, 10, 12, 18, 42) }])
    const { App } = await boot('#/drive/%E0%A4%A')
    render(<App />)
    const root = await list()
    expect(rowNames(root)).toEqual(['Evening loop'])
  })

  it('the remembered-view key is retired: an old key is ignored, Sapphire opens on the list and opens no drive', async () => {
    await seed([{ id: 'a', name: 'Evening loop', start: new Date(2096, 10, 12, 18, 42) }])
    localStorage.setItem('sapphire_view', JSON.stringify({ activePanel: 'trip', activeSessionId: 'a' }))
    observePoints()
    const { App, useStore } = await boot()
    render(<App />)
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(1), { timeout: 3000 })
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    expect(useStore.getState().activeSessionId).toBeNull()
    expect(pointReads).toEqual([])
    expect(window.location.hash).toBe('')
    await list()
  })

  it('back and forward follow the address (hash changes)', async () => {
    await seed([{ id: 'a', name: 'Evening loop', start: new Date(2096, 10, 12, 18, 42) }])
    const { App } = await boot()
    render(<App />)
    await list()
    await act(async () => { window.location.hash = '#/drive/a' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Evening loop' })).toBeInTheDocument()
    await act(async () => { window.location.hash = '#/settings' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
    await act(async () => { window.history.back() })
    expect(await screen.findByRole('heading', { level: 1, name: 'Evening loop' }, { timeout: 3000 })).toBeInTheDocument()
    await act(async () => { window.history.forward() })
    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' }, { timeout: 3000 })).toBeInTheDocument()
  })

  it('the Import button opens the browser\'s file chooser, without a place of its own (ticket 06)', async () => {
    const { App } = await boot()
    render(<App />)
    const empty = await list()
    const button = await within(empty).findByRole('button', { name: 'Import' })
    const before = window.location.hash
    const clicked: string[] = []
    const click = vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(function (this: HTMLInputElement) { clicked.push(`${this.id} ${this.type} ${this.multiple}`) })
    try { fireEvent.click(button) } finally { click.mockRestore() }
    expect(clicked).toEqual(['file-input file true'])
    expect(window.location.hash).toBe(before)
  })
})
