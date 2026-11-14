import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import { openDB } from 'idb'
import JSZip from 'jszip'
import type { Session } from './types/session'

// The Drive library delivery, commit 1 (PLAN-drive-library.md, section 4): today's rules about the stored drives, the
// Current drive and the compared drives that no test pinned (found by breaking each one on a copy of main 41784f69 and
// running the whole suite: dev/unpinned-main*.py). Each test pins today's behaviour as it is, before any code moves; a
// "kept:" test pins a difference between writers, or a known defect, that this delivery keeps.
//
// The whole App over an in-memory IndexedDB (fake-indexeddb) and the real storage module, passed through a mock that
// can hold a read back (an IndexedDB request cannot be paused inside its transaction), fail one, or hold "clear all".
// Only the map is stubbed. Invented data only, dated 2096.

const gate = vi.hoisted(() => ({
  hold: new Map<string, Promise<void>>(),
  failLoad: new Set<string>(),
  listHold: null as Promise<void> | null,
  failList: false,
  clearHold: null as Promise<void> | null,
}))
vi.mock('./lib/storage', async (importOriginal) => {
  const real = await importOriginal<typeof import('./lib/storage')>()
  return {
    ...real,
    loadDrive: async (id: string) => {
      const held = gate.hold.get(id)
      if (held) await held
      if (gate.failLoad.has(id)) throw new DOMException('invented read failure', 'UnknownError')
      return real.loadDrive(id)
    },
    listDrives: async () => {
      if (gate.listHold) await gate.listHold
      if (gate.failList) throw new DOMException('invented list failure', 'UnknownError')
      return real.listDrives()
    },
    clearAllDrives: async () => {
      if (gate.clearHold) await gate.clearHold
      return real.clearAllDrives()
    },
  }
})
vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

/** A promise the test releases, and its release. */
function held(): [Promise<void>, () => void] {
  let release = () => {}
  const p = new Promise<void>((r) => { release = r })
  return [p, release]
}

const T0 = 4_000_000_000_000
const LOG = [{ ts: 0, type: 'SESSION', message: 'Started' }, { ts: 1000, type: 'STATE', message: 'Connected' }, { ts: 2000, type: 'STATE', message: 'Polling' }]
/** The points of invented drive [n]: whole values, so a CSV of them hashes the same (the same drive). */
const pointsOf = (n: number) => [0, 1, 2].map((i) => ({ ts: T0 + n * 1e7 + i * 1000, lat: 10 + n / 100, lng: 20 + i / 1000, speedKph: 40 + i, rpm: 3000 + i }))
function drive(id: string, n: number, extra: Partial<Session> = {}): Session {
  return {
    id, name: `Invented ${id}`, importedAt: T0 + n, tags: [],
    trip: { points: pointsOf(n) as never, summary: { distanceKm: 1, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 41, peakRpm: 3002,
      peakBoostPsi: null, peakLatG: 0, peakLongG: 0, peakSpeedKph: 42, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 3001,
      avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [] } },
    diagnostics: null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: new Date(T0 + n * 1e7).toISOString(), generatedAt: '' },
    ...extra,
  }
}
/** The same drive [n] as an export ZIP with its session log: importing it fills in a stored copy that has none. */
async function withLogZip(n: number, name = `fill-${n}.zip`): Promise<File> {
  const zip = new JSZip()
  zip.file(`drive_invented_${n}.csv`, ['# openrs_csv_v2', 'timestamp_ms,lat,lng,speed_kph,rpm',
    ...pointsOf(n).map((p) => `${p.ts},${p.lat},${p.lng},${p.speedKph},${p.rpm}`)].join('\n'))
  zip.file('diagnostic_detail_invented.json', JSON.stringify({ sessionEvents: LOG }))
  return new File([await zip.generateAsync({ type: 'blob' })], name, { type: 'application/zip' })
}
async function newDriveZip(n: number): Promise<File> {
  const zip = new JSZip()
  zip.file(`drive_invented_${n}.csv`, ['# openrs_csv_v2', 'timestamp_ms,lat,lng,speed_kph,rpm',
    ...pointsOf(n).map((p) => `${p.ts},${p.lat},${p.lng},${p.speedKph},${p.rpm}`)].join('\n'))
  return new File([await zip.generateAsync({ type: 'blob' })], `new-${n}.zip`, { type: 'application/zip' })
}

async function seed(sessions: Session[]) {
  vi.resetModules()
  const storage = await import('./lib/storage')
  storage.closeStorage()
  await storage.storeImported(sessions)
  storage.closeStorage()
}
async function boot(hash = '') {
  window.history.replaceState(null, '', `/${hash}`)
  vi.resetModules()
  const { useStore } = await import('./store')
  const storage = await import('./lib/storage')
  const { startImport } = await import('./components/importing/startImport')
  const { default: App } = await import('./App')
  render(<App />)
  return { useStore, storage, startImport }
}
/** A change behind the page's back (another tab, or the database itself): the store is not told. */
async function raw<T>(fn: (db: Awaited<ReturnType<typeof openDB>>) => Promise<T>): Promise<T> {
  const db = await openDB('sapphire')
  try { return await fn(db) } finally { db.close() }
}
const go = async (hash: string) => { await act(async () => { window.location.hash = hash }) }
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 30)) })

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
  vi.stubGlobal('localStorage', memoryStorage())
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
  gate.hold.clear(); gate.failLoad.clear(); gate.listHold = null; gate.failList = false; gate.clearHold = null
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

describe('the Current drive (pins, Drive library commit 1)', () => {
  it('U1: a late answer for an earlier drive never becomes the Current drive', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const [hold, release] = held()
    gate.hold.set('a', hold)
    const { useStore } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    await go('#/drive/a')                                           // a's read waits
    await go('#/drive/b')
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('b'))
    release()
    await settle()
    expect(useStore.getState().activeSession?.id).toBe('b')
  })

  it('U6: a new Current drive clears "read failed"', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    gate.failLoad.add('a')
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { useStore } = await boot('#/drive/a')
    await waitFor(() => expect(useStore.getState().openFailedId).toBe('a'))
    await go('#/drive/b')
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('b'))
    err.mockRestore()
    expect(useStore.getState().openFailedId).toBeNull()
  })

  it('U8: "read failed" is set only for the newest request', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const [hold, release] = held()
    gate.hold.set('a', hold)
    gate.failLoad.add('a')
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { useStore } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    await go('#/drive/a')
    await go('#/drive/b')
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('b'))
    release()
    await settle()
    err.mockRestore()
    expect(useStore.getState().openFailedId).toBeNull()
  })

  it('U9: a failed first read of the list leaves it "not loaded" (no empty state)', async () => {
    gate.failList = true
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { useStore } = await boot()
    await settle()
    err.mockRestore()
    expect(useStore.getState().drivesLoaded).toBe(false)
    expect(document.body.textContent).not.toMatch(/No drives stored/)
  })

  it('P7: the best-lap fill is skipped while the list is not loaded yet', async () => {
    const laps = drive('l', 1)
    laps.trip!.laps = [{ lapNumber: 1, lapTimeMs: 61_000 }, { lapNumber: 2, lapTimeMs: 59_500 }]
    await seed([laps])
    await raw(async (db) => { const r = await db.get('drives', 'l'); delete r.bestLapMs; await db.put('drives', r) })   // as the earlier build stored it
    const [hold, release] = held()
    gate.listHold = hold
    const { useStore } = await boot('#/drive/l')
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('l'))
    await settle()
    expect(await raw((db) => db.get('drives', 'l'))).not.toHaveProperty('bestLapMs')
    release()
    await waitFor(() => expect(useStore.getState().drivesLoaded).toBe(true))
  })
})

describe('the compared drives (pins, Drive library commit 1)', () => {
  async function compareOf(ids: string[], useStore: Awaited<ReturnType<typeof boot>>['useStore']) {
    await act(async () => { useStore.getState().setCompare(ids) })
  }

  it('U2: a late answer for an earlier Compare never replaces a newer one', async () => {
    await seed([drive('a', 1), drive('b', 2), drive('c', 3)])
    const { useStore } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(3))
    const [hold, release] = held()
    gate.hold.set('a', hold)
    await compareOf(['a', 'b'], useStore)                         // waits on a
    gate.hold.delete('a')
    await compareOf(['b', 'c'], useStore)
    await waitFor(() => expect(useStore.getState().compareSessions.map((s) => s.id)).toEqual(['b', 'c']))
    release()
    await settle()
    expect(useStore.getState().compareSessions.map((s) => s.id)).toEqual(['b', 'c'])
  })

  it('U3: Compare takes the Current drive already in memory, not a second read', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const { useStore } = await boot('#/drive/a')
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('a'))
    await compareOf(['a', 'b'], useStore)
    await waitFor(() => expect(useStore.getState().compareSessions).toHaveLength(2))
    expect(useStore.getState().compareSessions[0]).toBe(useStore.getState().activeSession)
  })

  it('U4: a compared drive that is no longer stored is left out', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const { useStore } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    await compareOf(['a', 'gone', 'b'], useStore)
    await waitFor(() => expect(useStore.getState().compareSessions.map((s) => s.id)).toEqual(['a', 'b']))
  })

  it('U5: no compared ids lets the compared copies go', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const { useStore } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    await compareOf(['a', 'b'], useStore)
    await waitFor(() => expect(useStore.getState().compareSessions).toHaveLength(2))
    await act(async () => { useStore.getState().clearCompare() })
    await waitFor(() => expect(useStore.getState().compareSessions).toEqual([]))
  })

  it('U16: Compare takes at most four drives (the first four ticked)', async () => {
    const { useStore } = await boot()
    await act(async () => { useStore.getState().setCompare(['a', 'b', 'c', 'd', 'e']) })
    expect(useStore.getState().compareSessionIds).toEqual(['a', 'b', 'c', 'd'])
  })

  it('P1 kept: opening Compare again with the same drives reads them again (a change made meanwhile shows)', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const { useStore, storage } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    await compareOf(['a', 'b'], useStore)
    await waitFor(() => expect(useStore.getState().compareSessions).toHaveLength(2))
    await storage.renameDrive('a', 'Renamed behind the page')        // the store is not told
    await compareOf(['a', 'b'], useStore)                          // the same ids, a new pick
    await waitFor(() => expect(useStore.getState().compareSessions[0]?.name).toBe('Renamed behind the page'))
  })

  it('P2 kept: a failed read of the compared drives keeps the copies already shown', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const { useStore } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    await compareOf(['a', 'b'], useStore)
    await waitFor(() => expect(useStore.getState().compareSessions).toHaveLength(2))
    gate.failLoad.add('b')
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    await compareOf(['a', 'b'], useStore)
    await settle()
    err.mockRestore()
    expect(useStore.getState().compareSessions.map((s) => s.id)).toEqual(['a', 'b'])
  })

  it('K11 kept: a session-only entry can be ticked for Compare, which then says it needs drives with trip data', async () => {
    await seed([drive('a', 1), { ...drive('s', 2), trip: null, diagnostics: { canInventory: [], fpsTimeline: [], decodeTrace: [],
      probeResults: [], dtcResults: [], sessionEvents: LOG } as never }])
    await boot()
    const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
    await waitFor(() => expect(within(root).getAllByRole('checkbox')).toHaveLength(2))
    for (const box of within(root).getAllByRole('checkbox')) fireEvent.click(box)
    fireEvent.click(within(root).getByRole('button', { name: 'Compare (2)' }))
    expect(await screen.findByText(/Need More Sessions/, {}, { timeout: 5000 })).toBeInTheDocument()
    expect(document.body.textContent).toMatch(/Import at least 2 sessions with trip data/)
  })
})

describe('Import and the library (pins, Drive library commit 1)', () => {
  it('U12: an Import adds the drives it stored to the list (the list is not replaced)', async () => {
    await seed([drive('a', 1)])
    const { useStore, startImport } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(1))
    await act(async () => { await startImport([await newDriveZip(5)]) })
    expect(useStore.getState().drives.map((d) => d.id)).toContain('a')
    expect(useStore.getState().drives).toHaveLength(2)
  })

  it('U11: an Import that fills in a stored drive re-reads the list', async () => {
    await seed([drive('x', 4)])
    const { useStore, startImport } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(1))
    expect(useStore.getState().drives[0].hasSession).toBe(false)
    await act(async () => { await startImport([await withLogZip(4)]) })
    expect(useStore.getState().drives[0].hasSession).toBe(true)
  })

  it('U10: an Import\'s re-read of the Current drive is dropped when another drive became current meanwhile', async () => {
    await seed([drive('x', 4), drive('y', 6)])
    const { useStore, startImport } = await boot('#/drive/x')
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('x'))
    const [hold, release] = held()
    gate.hold.set('x', hold)                                        // the Import's re-read of x waits
    let importing: Promise<void> = Promise.resolve()
    await act(async () => { importing = startImport([await withLogZip(4)]) })
    await waitFor(() => expect(useStore.getState().importJob?.running).toBe(true))
    await go('#/drive/y')
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe('y'))
    release()
    await act(async () => { await importing })
    await settle()
    expect(useStore.getState().activeSession?.id).toBe('y')
  })

  it('U21 kept, a known defect (L110, fixed separately): an Import that fills in a compared drive does not re-read Compare', async () => {
    await seed([drive('x', 4), drive('y', 6)])
    const { useStore, startImport } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    await act(async () => { useStore.getState().setCompare(['x', 'y']) })
    await waitFor(() => expect(useStore.getState().compareSessions).toHaveLength(2))
    await act(async () => { await startImport([await withLogZip(4)]) })
    await settle()
    expect(useStore.getState().drives.find((d) => d.id === 'x')?.hasSession).toBe(true)       // the list was re-read
    expect(useStore.getState().compareSessions[0].diagnostics).toBeNull()                       // Compare's copy was not
  })
})

describe('list writes and their failures (pins, Drive library commit 1)', () => {
  it('P4: "clear all" empties the list only after the database is emptied', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const [hold, release] = held()
    gate.clearHold = hold
    const { useStore } = await boot('#/settings')
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    fireEvent.click(await screen.findByText('Clear All Sessions', {}, { timeout: 5000 }))
    await act(async () => { fireEvent.click(screen.getByText('Confirm')) })
    await settle()
    expect(useStore.getState().drives).toHaveLength(2)                                          // the write has not finished
    release()
    await waitFor(() => expect(useStore.getState().drives).toEqual([]))
  })

  it('P8 kept: "clear all" leaves the compared drives as they were (delete takes them out; this does not)', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const { useStore } = await boot()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    await act(async () => { useStore.getState().setCompare(['a', 'b']) })
    await waitFor(() => expect(useStore.getState().compareSessions).toHaveLength(2))
    await go('#/settings')
    fireEvent.click(await screen.findByText('Clear All Sessions', {}, { timeout: 5000 }))
    await act(async () => { fireEvent.click(screen.getByText('Confirm')) })
    await waitFor(() => expect(useStore.getState().drives).toEqual([]))
    expect(useStore.getState().compareSessionIds).toEqual(['a', 'b'])
    expect(useStore.getState().compareSessions).toHaveLength(2)
  })

  it('P5: a restore that fails changes nothing in the list', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const { useStore } = await boot('#/settings')
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    const input = await screen.findByLabelText('Restore from a backup', {}, { timeout: 5000 }) as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [new File(['not a backup'], 'invented-not-a-backup.zip')] })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await act(async () => { fireEvent.change(input) })
    await waitFor(() => expect(document.body.textContent).toMatch(/Nothing was restored/))
    warn.mockRestore()
    expect(useStore.getState().drives).toHaveLength(2)
  })

  it('P6 kept: a rename that finds no stored record changes nothing on the page', async () => {
    await seed([drive('a', 1), drive('b', 2)])
    const { useStore, storage } = await boot()
    const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
    await waitFor(() => expect(within(root).getAllByRole('listitem')).toHaveLength(2))
    await storage.deleteDrives(['a'])                                                            // gone behind the page
    const row = within(root).getAllByRole('listitem').find((li) => li.textContent?.includes('Invented a'))!
    fireEvent.click(within(row).getByRole('button', { name: 'Actions for Invented a' }))
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: 'Rename…' }))
    const input = within(row).getByRole('textbox', { name: 'Name' })
    fireEvent.change(input, { target: { value: 'Never stored' } })
    await act(async () => { fireEvent.keyDown(input, { key: 'Enter' }) })
    await settle()
    expect(useStore.getState().drives.map((d) => d.name).sort()).toEqual(['Invented a', 'Invented b'])
    expect(within(root).queryByRole('alert')).toBeNull()                                         // and says nothing (REPORT-07)
  })
})
