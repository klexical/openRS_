import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { Session } from '../types/session'
import type { FileResult } from './importFiles'
import { exact } from '../test/exact'

// The Drive library at its own interface (spec, Testing Decisions, seam a): against the database stand-in the tests
// already use (fake-indexeddb), without drawing the app. Each test asserts only what a page could observe through the
// library: the store's fields, and what the database then holds. "kept:" names a difference between writers that this
// delivery keeps (PLAN-drive-library.md, section 3). Invented data only, dated 2096.

const T0 = 4_000_000_000_000
function drive(id: string, n: number): Session {
  return {
    id, name: `Invented ${id}`, importedAt: T0 + n, tags: [],
    trip: { points: [0, 1, 2].map((i) => ({ ts: T0 + n * 1e7 + i * 1000, lat: 10 + n / 100, lng: 20 + i / 1000, speedKph: 40 + i, rpm: 3000 + i })) as never,
      summary: { distanceKm: 1, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 41, peakRpm: 3002, peakBoostPsi: null, peakLatG: 0,
        peakLongG: 0, peakSpeedKph: 42, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 3001, avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [] } },
    diagnostics: null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' },
  }
}
const LOG = [{ ts: 0, type: 'SESSION', message: 'Started' }, { ts: 1000, type: 'STATE', message: 'Connected' }, { ts: 2000, type: 'STATE', message: 'Polling' }]
const withLog = (s: Session): Session => ({ ...s, id: `${s.id}-again`, diagnostics: { canInventory: [], fpsTimeline: [], decodeTrace: [],
  probeResults: [], dtcResults: [], sessionEvents: LOG } as never })

/** Fresh modules over a fresh database: the store, the storage module and the library, as a page sees them. */
async function fresh(seed: Session[] = []) {
  vi.resetModules()
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
  const storage = await import('./storage')
  const { useStore } = await import('../store')
  const library = await import('./driveLibrary')
  if (seed.length) await storage.storeImported(seed)
  return { storage, useStore, library, st: () => useStore.getState() }
}
type Env = Awaited<ReturnType<typeof fresh>>
/** The list in the store agrees with the database, record for record. */
async function agrees({ storage, st }: Env) {
  const byId = (a: { id: string }, b: { id: string }) => a.id.localeCompare(b.id)
  expect(exact([...st().drives].sort(byId))).toBe(exact((await storage.listDrives()).sort(byId)))
}
const proto = (globalThis as unknown as { IDBObjectStore: { prototype: Record<string, (...a: unknown[]) => unknown> } }).IDBObjectStore.prototype
const originals: [string, (...a: unknown[]) => unknown][] = []
function failOn(method: string, store: string) {
  const orig = proto[method]
  originals.push([method, orig])
  proto[method] = function (this: { name: string }, ...a: unknown[]) {
    if (this.name === store) throw new DOMException('invented failure', 'UnknownError')
    return orig.apply(this, a)
  }
}
beforeEach(() => { vi.spyOn(console, 'warn').mockImplementation(() => {}); vi.spyOn(console, 'error').mockImplementation(() => {}) })
afterEach(() => { for (const [m, orig] of originals.splice(0).reverse()) proto[m] = orig; vi.restoreAllMocks() })

describe('Drive library: the list', () => {
  it('loadList reads the list once and marks it loaded', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    await agrees(env)
    expect(env.st().drivesLoaded).toBe(true)
  })

  it('loadList never rejects: a failed read leaves the list "not loaded"', async () => {
    const env = await fresh([drive('a', 1)])
    failOn('getAll', 'drives')
    await expect(env.library.loadList()).resolves.toBeUndefined()
    expect(env.st().drivesLoaded).toBe(false)
    expect(env.st().drives).toEqual([])
  })

  it('rename, note and tags write, then the list, the Current drive and Compare\'s copies agree (the owner\'s fields)', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    const a = (await env.storage.loadDrive('a'))!
    env.useStore.setState({ activeSessionId: 'a', activeSession: a, compareSessionIds: ['a', 'b'], compareSessions: [a, (await env.storage.loadDrive('b'))!] })
    expect((await env.library.rename('a', 'Renamed'))?.name).toBe('Renamed')
    await env.library.setNote('a', 'A note')
    await env.library.setTags('a', ['Street'])
    await agrees(env)
    for (const copy of [env.st().activeSession!, env.st().compareSessions[0]]) {
      expect([copy.name, copy.note, copy.tags]).toEqual(['Renamed', 'A note', ['Street']])
      expect(copy.trip?.points).toHaveLength(3)                                  // the points are not touched
    }
  })

  it('kept: a rename of a drive no longer stored resolves null and mirrors nothing', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    await env.storage.deleteDrives(['a'])                                       // gone behind the page
    expect(await env.library.rename('a', 'Never')).toBeNull()
    expect(env.st().drives.map((d) => d.name)).toEqual(['Invented a'])
  })

  it('a rename whose write fails rejects and mirrors nothing', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    failOn('put', 'drives')
    await expect(env.library.rename('a', 'Never')).rejects.toThrow()
    expect(env.st().drives.map((d) => d.name)).toEqual(['Invented a'])
  })

  it('remove deletes, then the drives leave the list, stop being the Current drive and leave Compare', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2), drive('c', 3)])
    await env.library.loadList()
    const [a, b] = [(await env.storage.loadDrive('a'))!, (await env.storage.loadDrive('b'))!]
    env.useStore.setState({ activeSessionId: 'a', activeSession: a, compareSessionIds: ['a', 'b'], compareSessions: [a, b] })
    await env.library.remove(['a'])
    await agrees(env)
    expect([env.st().activeSessionId, env.st().activeSession]).toEqual([null, null])
    expect(env.st().compareSessionIds).toEqual(['b'])
    expect(env.st().compareSessions.map((s) => s.id)).toEqual(['b'])
  })

  it('remove is all or none: a failure rejects, deletes nothing and mirrors nothing', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    let calls = 0
    const del = proto.delete
    originals.push(['delete', del])
    proto.delete = function (this: unknown, ...a: unknown[]) { if (++calls === 4) throw new DOMException('invented', 'UnknownError'); return del.apply(this, a) }
    await expect(env.library.remove(['a', 'b'])).rejects.toThrow()
    await agrees(env)
    expect(env.st().drives).toHaveLength(2)
  })

  it('kept: clearAll empties the database and the list, and leaves the Current drive and Compare as they were (unlike remove)', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    const a = (await env.storage.loadDrive('a'))!
    env.useStore.setState({ activeSessionId: 'a', activeSession: a, compareSessionIds: ['a', 'b'], compareSessions: [a] })
    await env.library.clearAll()
    await agrees(env)
    expect(env.st().drives).toEqual([])
    expect(env.st().activeSessionId).toBe('a')
    expect(env.st().activeSession).toBe(a)
    expect(env.st().compareSessionIds).toEqual(['a', 'b'])
    expect(env.st().compareSessions).toEqual([a])
  })

  it('clearAll that fails rejects and mirrors nothing', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    failOn('clear', 'points')
    await expect(env.library.clearAll()).rejects.toThrow()
    await agrees(env)
    expect(env.st().drives).toHaveLength(1)
  })

  it('restore writes, then reads the whole list again; kept: the Current drive and Compare are not touched', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    const { makeBackup } = await import('./backup')
    const backup = (await makeBackup()).blob
    await env.storage.clearAllDrives()
    await env.library.loadList()
    env.useStore.setState({ activeSessionId: 'x', compareSessionIds: ['y', 'z'] })
    const out = await env.library.restore(backup)
    expect(out.added).toBe(2)
    await agrees(env)
    expect(env.st().activeSessionId).toBe('x')
    expect(env.st().compareSessionIds).toEqual(['y', 'z'])
  })

  it('a restore that fails rejects and mirrors nothing', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    await expect(env.library.restore(new Blob(['not a backup']))).rejects.toThrow()
    expect(env.st().drives).toHaveLength(1)
  })
})

describe('Drive library: an Import', () => {
  const result = (file: string, outcome: Awaited<ReturnType<import('./driveLibrary').StoringStep>>): FileResult =>
    ({ file, stored: outcome.stored, already: outcome.alreadyImported })

  it('kept: the drives an Import stored are added to the list once, after the last file (K4), not by reading the list again', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    const results = await env.library.importDrives(async (step) => {
      const one = result('one.zip', await step([drive('b', 2)]))
      expect(env.st().drives.map((d) => d.id)).toEqual(['a'])                  // between files: not yet
      const two = result('two.zip', await step([drive('c', 3)]))
      return [one, two]
    })
    expect(results.map((r) => r.stored.length)).toEqual([1, 1])
    expect(env.st().drives.map((d) => d.id)).toEqual(['a', 'b', 'c'])           // appended, in this order
    await agrees(env)
  })

  it('a fill-in reads the list again, and the Current drive when it was filled in and is still current', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    env.useStore.setState({ activeSessionId: 'a', activeSession: (await env.storage.loadDrive('a'))! })
    await env.library.importDrives(async (step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    await agrees(env)
    expect(env.st().drives[0].hasSession).toBe(true)
    expect(env.st().activeSession?.diagnostics?.sessionEvents).toHaveLength(3)
  })

  it('kept, a known defect (L110): a fill-in does not read Compare\'s copies again', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    const a = (await env.storage.loadDrive('a'))!
    env.useStore.setState({ compareSessionIds: ['a'], compareSessions: [a] })
    await env.library.importDrives(async (step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    expect(env.st().compareSessions[0]).toBe(a)
  })

  it('the storing step refuses once its Import has ended, and stores nothing (I3)', async () => {
    const env = await fresh()
    let kept: import('./driveLibrary').StoringStep | null = null
    await env.library.importDrives(async (step) => { kept = step; return [] })
    await expect(kept!([drive('late', 9)])).rejects.toThrow(/This import has ended/)
    expect(await env.storage.listDrives()).toEqual([])
  })

  it('a reading pipeline that throws rejects the Import and mirrors nothing', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    await expect(env.library.importDrives(async () => { throw new Error('invented') })).rejects.toThrow('invented')
    expect(env.st().drives.map((d) => d.id)).toEqual(['a'])
  })
})
