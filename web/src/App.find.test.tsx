import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import { openDB } from 'idb'
import type { DtcEntry, Session, SessionEvent } from './types/session'

// #384 ticket 04 at the page: find a drive (search, quick filters, markers, best lap) and the drive's "warnings not
// checked" line. The whole App over an in-memory IndexedDB (fake-indexeddb) and the real storage module; only the map is
// stubbed. Invented data only, dated 2096 with local-time constructors.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

interface Spec {
  id: string; name: string; start: Date; tags?: string[]
  /** Fault codes in the import's scan; `scan` its status (undefined: no scan in the import). */
  codes?: Pick<DtcEntry, 'code' | 'status'>[]; scan?: string | null
  /** The session log's events (undefined: no session log). */
  events?: Pick<SessionEvent, 'type' | 'message'>[]
  laps?: number[]
  sessionOnly?: boolean
}

function drive(s: Spec): Session {
  const t0 = s.start.getTime()
  const points = s.sessionOnly ? [] : [0, 1, 2].map((i) => ({
    ts: t0 + i * 1000, lat: 10 + s.id.length / 100 + s.id.charCodeAt(s.id.length - 1) / 10_000, lng: 20 + i / 1000,
    speedKph: 80, rpm: 2500, driveMode: 'Normal',
  }))
  const hasDiag = s.codes !== undefined || s.events !== undefined
  return {
    id: s.id, name: s.name, importedAt: t0 + 3_600_000, tags: s.tags ?? [],
    trip: s.sessionOnly ? null : {
      points: points as never,
      summary: { distanceKm: 12.3, distanceSource: 'recorded', durationMs: 1_200_000, fuelUsedL: 0, avgSpeedKph: 50, peakRpm: 5000,
        peakBoostPsi: 14.2, peakBoostSource: 'CAN_0F8', peakLatG: 0, peakLongG: 0, peakSpeedKph: 80, peakCoolantC: 90,
        peakOilTempC: 95, avgRpm: 2500, avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [] },
      ...(s.laps ? { laps: s.laps.map((ms, i) => ({ lapNumber: i + 1, lapTimeMs: ms })) } : {}),
    } as never,
    diagnostics: hasDiag ? {
      canInventory: [], fpsTimeline: [], decodeTrace: [], probeResults: [],
      sessionEvents: (s.events ?? []).map((e, i) => ({ ts: i * 1000, ...e })),
      dtcResults: (s.codes ?? []).map((c) => ({ module: 'PCM', description: 'invented', ...c })),
      ...(s.events === undefined ? { sessionLog: false as const } : {}),
    } : null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: s.start.toISOString(), generatedAt: '' },
    ...(s.scan !== undefined ? { dtcScan: true, dtcScanStatus: s.scan } : { dtcScan: false, dtcScanStatus: null }),
  }
}

const LOG = [{ type: 'SESSION', message: 'Started' }, { type: 'STATE', message: 'Connected' }]
const DRIVES: Spec[] = [
  { id: 'code', name: 'Active code drive', start: new Date(2096, 3, 8, 9), codes: [{ code: 'P0123', status: 'ACTIVE' }], scan: 'COMPLETE', events: LOG },
  { id: 'gap', name: 'Link lost drive', start: new Date(2096, 3, 6, 9), scan: 'COMPLETE', codes: [],
    events: [...LOG, { type: 'REC_GAP', message: 'DISCONNECTED gap 12.0 s (adapter)' }] },
  { id: 'paused', name: 'Paused drive', start: new Date(2096, 3, 4, 9), scan: 'COMPLETE', codes: [{ code: 'P0456', status: 'PERMANENT' }],
    events: [...LOG, { type: 'REC_GAP', message: 'PAUSED gap 30.0 s' }, { type: 'REC_GAP', message: 'NO_LOCATION gap 8.0 s' }] },
  { id: 'track', name: 'Circuit afternoon', start: new Date(2096, 2, 25, 13), tags: ['Track Day'], laps: [118_000, 112_400, 0, 115_900] },
  { id: 'scan', name: 'A scan at the kerb', start: new Date(2096, 2, 20, 8), sessionOnly: true, scan: 'COMPLETE', codes: [], events: LOG },
  { id: 'bare', name: 'Morning commute', start: new Date(2096, 2, 2, 7), tags: ['Commute'] },
]

async function boot(hash = '') {
  window.history.replaceState(null, '', `/${hash}`)
  vi.resetModules()
  const { useStore } = await import('./store')
  const storage = await import('./lib/storage')
  const { default: App } = await import('./App')
  return { useStore, storage, App }
}

async function seed(specs: Spec[]) {
  vi.resetModules()
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

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})

async function list() {
  const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
  await waitFor(() => expect(root).toHaveAttribute('aria-busy', 'false'), { timeout: 3000 })
  return root
}
const rowNames = (root: HTMLElement) => within(root).queryAllByRole('listitem').map((li) => within(li).getByRole('link').textContent)
const row = (root: HTMLElement, name: string) => within(root).getAllByRole('listitem').find((li) => li.textContent?.includes(name))!

async function openList(specs: Spec[] = DRIVES) {
  await seed(specs)
  const booted = await boot()
  render(<booted.App />)
  return { ...booted, root: await list() }
}

describe('#384 ticket 04: markers, each with a word', () => {
  it('"Warnings" on a drive with an active code or a lost link; none for a stored code, a pause or nothing checked', async () => {
    const { root } = await openList()
    expect(row(root, 'Active code drive').textContent).toMatch(/Warnings/)
    expect(row(root, 'Link lost drive').textContent).toMatch(/Warnings/)
    expect(row(root, 'Paused drive').textContent).not.toMatch(/Warnings/)          // permanent code; PAUSED / NO_LOCATION gaps
    expect(row(root, 'Morning commute').textContent).not.toMatch(/Warnings|not checked/i)   // nothing checked: no marker
  })

  it('"Laps" with the best valid lap time on a drive with laps', async () => {
    const { root } = await openList()
    expect(row(root, 'Circuit afternoon').textContent).toMatch(/Laps · best 1:52\.4/)
    expect(row(root, 'Morning commute').textContent).not.toMatch(/Laps/)
  })

  it('"Session only" on an entry with a session and no drive, in the same list', async () => {
    const { root } = await openList()
    expect(row(root, 'A scan at the kerb').textContent).toMatch(/Session only/)
    expect(row(root, 'Morning commute').textContent).not.toMatch(/Session only/)
  })

  it('each marker carries a shape as well as its word (never colour alone)', async () => {
    const { root } = await openList()
    for (const [name, word] of [['Active code drive', 'Warnings'], ['Circuit afternoon', 'Laps'], ['A scan at the kerb', 'Session only']]) {
      const marker = within(row(root, name)).getByText(new RegExp(word), { selector: '.a-marker' })
      expect(marker.querySelector('[aria-hidden="true"]')?.textContent?.trim()).toMatch(/^[▲◷◇]$/)
    }
  })
})

describe('#384 ticket 04: quick filters', () => {
  it('"With warnings" is labelled "fault codes and gaps" until all four causes are checked, and shows only drives with warnings', async () => {
    const { root } = await openList()
    const button = within(root).getByRole('button', { name: /With warnings/ })
    expect(button.textContent).toMatch(/fault codes and gaps/)
    expect(button).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-pressed', 'true')
    expect(rowNames(root)).toEqual(['Active code drive', 'Link lost drive'])
    fireEvent.click(button)
    expect(rowNames(root)).toHaveLength(DRIVES.length)
  })

  it('"With laps" shows only drives with laps; both filters together can match nothing, which says so plainly', async () => {
    const { root } = await openList()
    fireEvent.click(within(root).getByRole('button', { name: /With laps/ }))
    expect(rowNames(root)).toEqual(['Circuit afternoon'])
    fireEvent.click(within(root).getByRole('button', { name: /With warnings/ }))
    expect(rowNames(root)).toEqual([])
    expect(root.textContent).toMatch(/No drives match/)
    expect(root.textContent).not.toMatch(/The ZIP comes from/)        // not the empty state
    fireEvent.click(within(root).getByRole('button', { name: /Clear the search and filters/ }))
    expect(rowNames(root)).toHaveLength(DRIVES.length)
  })
})

describe('#384 ticket 04: the search box', () => {
  const search = (root: HTMLElement, text: string) => fireEvent.change(within(root).getByRole('searchbox', { name: 'Search drives' }), { target: { value: text } })

  it('finds by name (any case), by tag, and by date as the list writes it', async () => {
    const { root } = await openList()
    search(root, 'circuit')
    expect(rowNames(root)).toEqual(['Circuit afternoon'])
    search(root, 'commute')
    expect(rowNames(root)).toEqual(['Morning commute'])                                   // name and tag both say it
    search(root, 'track day')
    expect(rowNames(root)).toEqual(['Circuit afternoon'])                                  // a tag
    search(root, new Date(2096, 3, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' }))   // the month heading
    expect(rowNames(root)).toEqual(['Active code drive', 'Link lost drive', 'Paused drive'])
    search(root, '2096-03-25')                                                             // a date written in full
    expect(rowNames(root)).toEqual(['Circuit afternoon'])
    search(root, new Date(2096, 2, 2).toLocaleDateString(undefined, { day: 'numeric', month: 'short' }))   // as the row writes it
    expect(rowNames(root)).toContain('Morning commute')
    search(root, 'nothing like this')
    expect(rowNames(root)).toEqual([])
    expect(root.textContent).toMatch(/No drives match/)
  })

  it('every word must match, with the quick filters', async () => {
    const { root } = await openList()
    search(root, 'drive link')
    expect(rowNames(root)).toEqual(['Link lost drive'])
    search(root, 'drive')
    fireEvent.click(within(root).getByRole('button', { name: /With warnings/ }))
    expect(rowNames(root)).toEqual(['Active code drive', 'Link lost drive'])
    expect(root.textContent).toMatch(/2 of 6/)
  })

  it('the search and filters are still there after opening a drive and coming back', async () => {
    const { root } = await openList()
    search(root, 'drive')
    fireEvent.click(within(root).getByRole('button', { name: /With warnings/ }))
    await act(async () => { window.location.hash = '#/drive/gap' })
    await screen.findByRole('heading', { level: 1, name: 'Link lost drive' })
    await act(async () => { window.location.hash = '#/' })
    const back = await list()
    expect(within(back).getByRole('searchbox', { name: 'Search drives' })).toHaveValue('drive')
    expect(within(back).getByRole('button', { name: /With warnings/ })).toHaveAttribute('aria-pressed', 'true')
    expect(rowNames(back)).toEqual(['Active code drive', 'Link lost drive'])
  })
})

describe("#384 ticket 04: a drive's own page names a warning cause it could not check", () => {
  const page = async (spec: Spec) => {
    await seed([spec])
    const { App } = await boot(`#/drive/${spec.id}`)
    render(<App />)
    await screen.findByRole('heading', { level: 1, name: spec.name }, { timeout: 3000 })
    return document.querySelector('main')!
  }

  it('no scan and no session log: both named', async () => {
    const main = await page({ id: 'n', name: 'Nothing checked', start: new Date(2096, 1, 1) })
    expect(main.textContent).toContain('Warnings not checked: this import has no fault-code scan or session log.')
  })
  it('a complete scan with no session log: only the log is named', async () => {
    const main = await page({ id: 'l', name: 'Scan only', start: new Date(2096, 1, 1), scan: 'COMPLETE', codes: [] })
    expect(main.textContent).toContain('Warnings not checked: this import has no session log.')
  })
  it('a session log and no scan: only the scan is named', async () => {
    const main = await page({ id: 's', name: 'Log only', start: new Date(2096, 1, 1), events: LOG })
    expect(main.textContent).toContain('Warnings not checked: this import has no fault-code scan.')
  })
  it('a scan not marked complete is named as such, not as missing', async () => {
    const main = await page({ id: 'p', name: 'Partial scan', start: new Date(2096, 1, 1), scan: 'PARTIAL', codes: [], events: LOG })
    expect(main.textContent).toContain("Warnings not checked: this import's fault-code scan is not marked complete.")
  })
  it('both causes checked, none found: no line; a warning found: no "not checked" line', async () => {
    let main = await page({ id: 'c', name: 'All clear', start: new Date(2096, 1, 1), scan: 'COMPLETE', codes: [], events: LOG })
    expect(main.textContent).not.toMatch(/not checked/i)
    cleanup()
    main = await page({ id: 'w', name: 'Code found, no log', start: new Date(2096, 1, 1), scan: 'PARTIAL', codes: [{ code: 'P0123', status: 'PENDING' }] })
    expect(main.textContent).not.toMatch(/not checked/i)
  })
})

describe('#384 ticket 04: the best lap of a drive stored before the record held it', () => {
  /** A drive record as live delivery A stores it: no bestLapMs. */
  async function asDeliveryA(id: string) {
    const db = await openDB('sapphire')
    const rec = await db.get('drives', id)
    delete rec.bestLapMs
    await db.put('drives', rec)
    db.close()
    return rec
  }

  it('reads "Laps" without a time until the drive is opened; opening it fills the record in as an import would', async () => {
    const track = DRIVES.find((d) => d.id === 'track')!
    await seed([track])
    const fresh = await (async () => { const db = await openDB('sapphire'); const r = await db.get('drives', 'track'); db.close(); return r })()
    await asDeliveryA('track')
    const { App, useStore } = await boot()
    render(<App />)
    let root = await list()
    expect(row(root, 'Circuit afternoon').textContent).toMatch(/Laps/)
    expect(row(root, 'Circuit afternoon').textContent).not.toMatch(/best/)
    await act(async () => { window.location.hash = '#/drive/track' })
    await screen.findByRole('heading', { level: 1, name: 'Circuit afternoon' })
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('track'))   // its points are read
    await waitFor(() => expect(useStore.getState().drives[0].bestLapMs).toBe(112_400))   // and the record filled in
    await act(async () => { window.location.hash = '#/' })
    root = await list()
    await waitFor(() => expect(row(root, 'Circuit afternoon').textContent).toMatch(/Laps · best 1:52\.4/))
    const db = await openDB('sapphire')
    const filled = await db.get('drives', 'track')
    db.close()
    expect(JSON.stringify(filled)).toBe(JSON.stringify(fresh))          // same fields, values and key order as an import
  })

  it('a fill-in that fails leaves the record as it was, and the drive still opens', async () => {
    const track = DRIVES.find((d) => d.id === 'track')!
    await seed([track])
    const before = await asDeliveryA('track')
    const proto = (globalThis as unknown as { IDBObjectStore: { prototype: Record<string, (...a: unknown[]) => unknown> } }).IDBObjectStore.prototype
    const put = proto.put
    proto.put = function (this: { name: string }, ...a: unknown[]) {
      if (this.name === 'drives') throw new DOMException('invented failure', 'QuotaExceededError')
      return put.apply(this, a)
    }
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      const { App, useStore } = await boot('#/drive/track')
      render(<App />)
      await screen.findByRole('heading', { level: 1, name: 'Circuit afternoon' }, { timeout: 3000 })
      await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('track'))
      await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    } finally { proto.put = put; warn.mockRestore() }
    const db = await openDB('sapphire')
    const after = await db.get('drives', 'track')
    db.close()
    expect(JSON.stringify(after)).toBe(JSON.stringify(before))
  })
})
