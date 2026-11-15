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
    const results = await env.library.importDrives([], () => {}, async (_f, _p, step) => {
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
    await env.library.importDrives([], () => {}, async (_f, _p, step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    await agrees(env)
    expect(env.st().drives[0].hasSession).toBe(true)
    expect(env.st().activeSession?.diagnostics?.sessionEvents).toHaveLength(3)
  })

  it('#406: a fill-in of a compared drive reads Compare\'s copies again before the Import resolves', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    const [a, b] = [(await env.storage.loadDrive('a'))!, (await env.storage.loadDrive('b'))!]
    env.useStore.setState({ compareSessionIds: ['a', 'b'], compareSessions: [a, b] })
    await env.library.importDrives([], () => {}, async (_f, _p, step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    expect(env.st().compareSessions.map((s) => s.id)).toEqual(['a', 'b'])
    expect(env.st().compareSessions[0].diagnostics?.sessionEvents).toHaveLength(3)
  })

  it('#406: a fill-in of a drive that is not compared reads no compared drive', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    const b = (await env.storage.loadDrive('b'))!
    env.useStore.setState({ compareSessionIds: ['b'], compareSessions: [b] })
    const load = vi.spyOn(env.storage, 'loadDrive')
    await env.library.importDrives([], () => {}, async (_f, _p, step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    expect(load).not.toHaveBeenCalled()
    expect(env.st().compareSessions[0]).toBe(b)
  })

  it('#406: Compare is read again even when the list\'s re-read fails, and a filled-in Current drive is read from storage', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    const [a, b] = [(await env.storage.loadDrive('a'))!, (await env.storage.loadDrive('b'))!]
    env.useStore.setState({ activeSessionId: 'a', activeSession: a, compareSessionIds: ['a', 'b'], compareSessions: [a, b] })
    vi.spyOn(env.storage, 'listDrives').mockRejectedValue(new DOMException('invented list failure', 'UnknownError'))
    await env.library.importDrives([], () => {}, async (_f, _p, step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    expect(console.warn).toHaveBeenCalledWith('[Sapphire] Could not read the filled-in drives again:', expect.anything())
    expect(env.st().activeSession).toBe(a)                                         // its re-read was skipped: the old copy
    expect(env.st().compareSessions[0].diagnostics?.sessionEvents).toHaveLength(3)  // Compare did not reuse it
  })

  // Q14 (owner, 2026-11-15): after the Current drive's own re-read fails, its copy in memory is stale: Compare reads that
  // drive from storage, like any other compared drive, until the drive is read again; the normal path is unchanged
  it('Q14: after the Current drive\'s own re-read fails, a later Compare pick reads it from storage until it is read again', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    const [a, b] = [(await env.storage.loadDrive('a'))!, (await env.storage.loadDrive('b'))!]
    env.useStore.setState({ activeSessionId: 'a', activeSession: a, compareSessionIds: ['b'], compareSessions: [b] })
    const real = env.storage.loadDrive
    let failA = true
    const load = vi.spyOn(env.storage, 'loadDrive').mockImplementation((id) =>
      (id === 'a' && failA ? Promise.reject(new DOMException('invented read failure', 'UnknownError')) : real(id)))
    await env.library.importDrives([], () => {}, async (_f, _p, step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    expect(console.warn).toHaveBeenCalledWith('[Sapphire] Could not read the filled-in drives again:', expect.anything())
    expect(env.st().activeSession).toBe(a)                                       // the re-read failed: the old copy
    failA = false
    env.library.compareDrives(['a', 'b'])                                        // a later pick, not the fill-in's own
    await vi.waitFor(() => expect(env.st().compareSessions.map((s) => s.id)).toEqual(['a', 'b']))
    expect(env.st().compareSessions[0]).not.toBe(a)
    expect(env.st().compareSessions[0].diagnostics?.sessionEvents).toHaveLength(3)  // read from storage
    env.library.showDrive(null); env.library.showDrive('a')                       // the drive is read again
    await vi.waitFor(() => expect(env.st().activeSession?.diagnostics?.sessionEvents).toHaveLength(3))
    const reread = env.st().activeSession
    load.mockClear()
    env.library.compareDrives(['a', 'b'])
    await vi.waitFor(() => expect(env.st().compareSessions[0]).toBe(reread))      // the normal path: the copy in memory
    expect(load.mock.calls.map((c) => c[0])).toEqual(['b'])
  })

  it('Q14: when the list\'s re-read fails before the Current drive\'s, a later Compare pick reads the Current drive from storage', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    const [a, b] = [(await env.storage.loadDrive('a'))!, (await env.storage.loadDrive('b'))!]
    env.useStore.setState({ activeSessionId: 'a', activeSession: a, compareSessionIds: ['b'], compareSessions: [b] })
    vi.spyOn(env.storage, 'listDrives').mockRejectedValue(new DOMException('invented list failure', 'UnknownError'))
    await env.library.importDrives([], () => {}, async (_f, _p, step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    expect(env.st().activeSession).toBe(a)                                       // its re-read never ran: the old copy
    env.library.compareDrives(['a', 'b'])
    await vi.waitFor(() => expect(env.st().compareSessions.map((s) => s.id)).toEqual(['a', 'b']))
    expect(env.st().compareSessions[0].diagnostics?.sessionEvents).toHaveLength(3)  // read from storage
  })

  it('Q14, the normal path: after the Current drive\'s re-read succeeds, a later Compare pick uses the copy in memory', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    await env.library.loadList()
    const [a, b] = [(await env.storage.loadDrive('a'))!, (await env.storage.loadDrive('b'))!]
    env.useStore.setState({ activeSessionId: 'a', activeSession: a, compareSessionIds: ['b'], compareSessions: [b] })
    await env.library.importDrives([], () => {}, async (_f, _p, step) => [result('again.zip', await step([withLog(drive('a', 1))]))])
    const reread = env.st().activeSession
    expect(reread?.diagnostics?.sessionEvents).toHaveLength(3)
    const load = vi.spyOn(env.storage, 'loadDrive')
    env.library.compareDrives(['a', 'b'])
    await vi.waitFor(() => expect(env.st().compareSessions.map((s) => s.id)).toEqual(['a', 'b']))
    expect(env.st().compareSessions[0]).toBe(reread)
    expect(load.mock.calls.map((c) => c[0])).toEqual(['b'])
  })

  it('kept (K8): a fill-in whose re-read of the list fails still ends the Import: it resolves, warns, and lists what it stored', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    vi.spyOn(env.storage, 'listDrives').mockRejectedValue(new DOMException('invented list failure', 'UnknownError'))
    const results = await env.library.importDrives([], () => {}, async (_f, _p, step) => [
      result('again.zip', await step([withLog(drive('a', 1))])),                 // fills a in: the list is read again
      result('new.zip', await step([drive('c', 3)])),
    ])
    expect(results.map((r) => r.stored.length)).toEqual([0, 1])
    expect(console.warn).toHaveBeenCalledWith('[Sapphire] Could not read the filled-in drives again:', expect.anything())
    expect(env.st().drives.map((d) => d.id)).toEqual(['a', 'c'])
  })

  it('the storing step refuses once its Import has ended, and stores nothing (I3)', async () => {
    const env = await fresh()
    let kept: import('./driveLibrary').StoringStep | null = null
    await env.library.importDrives([], () => {}, async (_f, _p, step) => { kept = step; return [] })
    await expect(kept!([drive('late', 9)])).rejects.toThrow(/This import has ended/)
    expect(await env.storage.listDrives()).toEqual([])
  })

  it('a reading pipeline that throws rejects the Import and mirrors nothing', async () => {
    const env = await fresh([drive('a', 1)])
    await env.library.loadList()
    await expect(env.library.importDrives([], () => {}, async () => { throw new Error('invented') })).rejects.toThrow('invented')
    expect(env.st().drives.map((d) => d.id)).toEqual(['a'])
  })
})

describe('Drive library: the Current drive', () => {
  /** Let every read and its answer land. */
  const settle = () => new Promise((r) => setTimeout(r, 30))

  it('reaches each state: none, being read, shown, not stored here (once), read failed', async () => {
    const env = await fresh([drive('a', 1)])
    const { library, st } = env
    const states = () => library.currentDriveState(st())
    let notStored = 0
    const detach = library.attach({ onNotStored: () => { notStored++ } })
    expect(states()).toBe('none')
    library.showDrive('a')
    expect(states()).toBe('being read')
    await vi.waitFor(() => expect(states()).toBe('shown'))
    library.showDrive('gone')
    await vi.waitFor(() => expect(notStored).toBe(1))                           // the event; the app shell then goes to the list
    await settle()
    expect(notStored).toBe(1)                                                    // once
    failOn('get', 'points')
    library.showDrive('a')
    await vi.waitFor(() => expect(states()).toBe('read failed'))
    library.showDrive(null)
    expect(states()).toBe('none')
    detach()
  })

  it('a late answer for an earlier drive never becomes the Current drive (one guard)', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    // a's read is held until b is shown, so a's answer is the late one (QA's FINAL: without the hold this passed with
    // the guard off)
    const real = env.storage.loadDrive
    let release = () => {}
    const hold = new Promise<void>((r) => { release = r })
    let aAnswered = false
    vi.spyOn(env.storage, 'loadDrive').mockImplementation((id: string) =>
      id === 'a' ? hold.then(() => real(id)).finally(() => setTimeout(() => { aAnswered = true }, 0)) : real(id))
    const detach = env.library.attach()
    env.library.showDrive('a')
    env.library.showDrive('b')                                                   // a's read is still on its way
    await vi.waitFor(() => expect(env.st().activeSession?.id).toBe('b'))
    release()
    await vi.waitFor(() => expect(aAnswered).toBe(true))
    await settle()
    expect(env.st().activeSession?.id).toBe('b')
    expect(env.library.currentDriveState(env.st())).toBe('shown')
    detach()
  })

  it('without attach, showDrive reads the drive itself: never "being read" for ever (I1)', async () => {
    const env = await fresh([drive('a', 1)])
    env.library.showDrive('a')
    await vi.waitFor(() => expect(env.library.currentDriveState(env.st())).toBe('shown'))
    env.library.showDrive('gone')                                                // not stored, and no app shell to tell
    await vi.waitFor(() => expect(env.library.currentDriveState(env.st())).toBe('none'))
  })

  it('attaching twice adds no second read and no second "not stored" (I2); reads stop when both detach', async () => {
    const env = await fresh([drive('a', 1)])
    const calls: string[] = []
    const storage = await import('./storage')
    const load = vi.spyOn(storage, 'loadDrive')
    let notStored = 0
    const one = env.library.attach({ onNotStored: () => { notStored++ } })
    const two = env.library.attach({ onNotStored: () => { notStored++ } })
    load.mockClear()
    env.library.showDrive('a')
    await vi.waitFor(() => expect(env.st().activeSession?.id).toBe('a'))
    await settle()                                                               // and no second read after it
    calls.push(...load.mock.calls.map((c) => String(c[0])))
    expect(calls).toEqual(['a'])
    env.library.showDrive('gone')
    await vi.waitFor(() => expect(notStored).toBeGreaterThan(0))
    await settle()
    expect(notStored).toBe(1)
    one(); one()                                                                  // a detach called twice counts once
    load.mockClear()
    env.useStore.getState().setActiveSessionId('a')                              // still attached (two)
    await vi.waitFor(() => expect(load.mock.calls.map((c) => String(c[0]))).toEqual(['a']))
    two()
    load.mockClear()
    env.useStore.getState().setActiveSessionId(null)
    env.useStore.getState().setActiveSessionId('a')                              // detached: nothing follows the store
    await settle()
    expect(load).not.toHaveBeenCalled()
  })
})

describe('Drive library: Compare', () => {
  const settle = () => new Promise((r) => setTimeout(r, 30))

  it('compareDrives reads the drives in order, at most four; toggleCompared and clearCompared keep them in step', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2), drive('c', 3), drive('d', 4), drive('e', 5)])
    const detach = env.library.attach()
    env.library.compareDrives(['b', 'a', 'c', 'd', 'e'])
    await vi.waitFor(() => expect(env.st().compareSessions.map((s) => s.id)).toEqual(['b', 'a', 'c', 'd']))
    env.library.toggleCompared('a')
    await vi.waitFor(() => expect(env.st().compareSessions.map((s) => s.id)).toEqual(['b', 'c', 'd']))
    env.library.clearCompared()
    await vi.waitFor(() => expect(env.st().compareSessions).toEqual([]))
    detach()
  })

  it('kept (K10): the same drives picked again are read again; a late answer never replaces a newer pick', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2), drive('c', 3)])
    const detach = env.library.attach()
    env.library.compareDrives(['a', 'b'])
    env.library.compareDrives(['b', 'c'])                                        // the first pick's reads are still on their way
    await vi.waitFor(() => expect(env.st().compareSessions.map((s) => s.id)).toEqual(['b', 'c']))
    await settle()                                                               // and no late answer replaces it
    expect(env.st().compareSessions.map((s) => s.id)).toEqual(['b', 'c'])
    await env.storage.renameDrive('b', 'Renamed behind the page')
    env.library.compareDrives(['b', 'c'])
    await vi.waitFor(() => expect(env.st().compareSessions[0].name).toBe('Renamed behind the page'))
    detach()
  })

  it('Q13, with #407: a failed read clears the old copies and names the drive that failed', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    const detach = env.library.attach()
    env.library.compareDrives(['a'])
    await vi.waitFor(() => expect(env.st().compareSessions.map((s) => s.id)).toEqual(['a']))
    failOn('get', 'points')
    env.library.compareDrives(['b'])
    await vi.waitFor(() => expect(env.st().compareFailedIds).toEqual(['b']))
    expect(env.st().compareSessionIds).toEqual(['b'])
    expect(env.st().compareSessions).toEqual([])                                 // no copy it did not just read (owner, Q13)
    expect(env.st().compareFailedIds).toEqual(['b'])
    env.library.compareDrives(['a'])                                             // a new read clears them
    expect(env.st().compareFailedIds).toEqual([])
    detach()
  })

  it('#407: a failed read named in Compare goes when that drive is removed, and on detach', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2), drive('c', 3)])
    failOn('get', 'points')
    env.library.compareDrives(['b', 'c'])                                        // not attached: the ask reads them
    await vi.waitFor(() => expect(env.st().compareFailedIds).toEqual(['b', 'c']))
    env.useStore.getState().removeDrives(['b'])                                  // not attached: no read again
    expect(env.st().compareFailedIds).toEqual(['c'])
    const detach = env.library.attach()                                          // reads c again, which fails again
    await vi.waitFor(() => expect(env.st().compareFailedIds).toEqual(['c']))
    detach()
    expect(env.st().compareFailedIds).toEqual([])
  })

  it('without attach, the Compare asks read the drives themselves', async () => {
    const env = await fresh([drive('a', 1), drive('b', 2)])
    env.library.compareDrives(['a', 'b'])
    await vi.waitFor(() => expect(env.st().compareSessions.map((s) => s.id)).toEqual(['a', 'b']))
  })
})
