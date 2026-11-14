import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { openDB } from 'idb'
import { storeImported, closeStorage, renameDrive, setDriveNote, setDriveTags, split } from './storage'
import type { DiagnosticData, Session } from '../types/session'
import { exact } from '../test/exact'

// #384 ticket 06: the same drive imported again fills in every part the stored drive lacks (owner, 2026-11-14, Q6) and
// never replaces a part it has: the fault-code scan, the session log and the answers they give, and the profile (its
// summary figures, laps, performance runs). At the storage module's public functions over an in-memory IndexedDB.
// Invented data only.

const T0 = 4_000_000_000_000   // an invented time (2096)
const POINTS = [0, 1, 2, 3].map((i) => ({ ts: T0 + i * 1000, lat: 10 + i / 1000, lng: 20, speedKph: 50 + i, rpm: 2000 + i }))
const LOG = [{ ts: 0, type: 'SESSION', message: 'Started' }, { ts: 1000, type: 'STATE', message: 'Connected' }, { ts: 2000, type: 'STATE', message: 'Polling' }]
const GAP = { ts: 3000, type: 'REC_GAP', message: 'DISCONNECTED gap 12.0 s (adapter)' }

interface Parts { scan?: 'COMPLETE' | 'PARTIAL'; codes?: { code: string; status: string }[]; log?: typeof LOG; profile?: boolean; points?: typeof POINTS }
/** One drive as an export with some parts: the same points every time (the same drive), a new id each import. */
function exportOf(p: Parts): Session {
  const points = p.points ?? POINTS
  const diag: DiagnosticData | null = p.scan || p.log ? {
    canInventory: p.log ? [{ id: '0F8', count: 10 }] as never : [], fpsTimeline: [], decodeTrace: [], probeResults: [],
    sessionEvents: p.log ?? [], dtcResults: (p.codes ?? []).map((c) => ({ module: 'PCM', description: 'invented', ...c })),
    ...(p.log ? { sessionDurationMs: 60_000 } : { sessionLog: false as const }),
  } : null
  return {
    id: crypto.randomUUID(), name: 'Trip — 2096', importedAt: T0 + 9_000_000, tags: p.profile ? ['Track'] : [],
    trip: {
      points: points as never,
      summary: { distanceKm: p.profile ? 0.3 : 0.333, ...(p.profile ? { distanceSource: 'recorded' as const } : {}), durationMs: 3000,
        fuelUsedL: 0, avgSpeedKph: 51, peakRpm: p.profile ? 2100 : 2003, peakBoostPsi: null, peakLatG: 0, peakLongG: 0,
        peakSpeedKph: 53, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 2001, avgFuelEconomy: NaN, modeBreakdown: {}, peakEvents: [] },
      ...(p.profile ? {
        profile: { drive: { tags: 'Track' } } as never, aggressionScore: 42,
        laps: [{ lapNumber: 1, lapTimeMs: 61_000 }, { lapNumber: 2, lapTimeMs: 59_500 }],
        perfRuns: [{ type: '0-100', timeMs: 5400 }],
      } : {}),
    },
    diagnostics: diag,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' },
    dtcScan: p.scan !== undefined, dtcScanStatus: p.scan ?? null,
  }
}
const FULL: Parts = { scan: 'COMPLETE', codes: [], log: LOG, profile: true }

async function raw(id: string) {
  closeStorage()
  const db = await openDB('sapphire')
  const out = { drive: await db.get('drives', id), points: await db.get('points', id), diagnostics: await db.get('diagnostics', id) }
  db.close()
  return out
}
async function all() {
  closeStorage()
  const db = await openDB('sapphire')
  const ids = await db.getAllKeys('drives')
  db.close()
  return ids
}
/** What the same drive imported in full would store, with the stored drive's own fields (id, name, times, tags, note). */
function oracle(full: Session, stored: { id: string; name: string; importedAt: number; tags: string[]; note?: string }) {
  return split({ ...full, id: stored.id, name: stored.name, importedAt: stored.importedAt, tags: stored.tags,
    ...(stored.note !== undefined ? { note: stored.note } : {}) })
}

// Writes per store, counted by an observer
const g = globalThis as unknown as Record<string, { prototype: Record<string, (...a: unknown[]) => unknown> }>
let writes: string[] = []
let put: (...a: unknown[]) => unknown
beforeEach(() => {
  closeStorage()
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
  writes = []
  put = g.IDBObjectStore.prototype.put
  g.IDBObjectStore.prototype.put = function (this: { name: string }, ...a: unknown[]) { writes.push(this.name); return put.apply(this, a) }
})
afterEach(() => { g.IDBObjectStore.prototype.put = put })

describe('#384 ticket 06: a later import fills in what the stored drive lacks', () => {
  it('without its scan and log, then with both: one drive, filled in exactly as a full import stores it; no points write', async () => {
    const first = await storeImported([exportOf({ profile: true })])
    const id = first.stored[0].id
    await renameDrive(id, 'Renamed by the owner'); await setDriveNote(id, 'a note'); await setDriveTags(id, ['Street'])
    const before = await raw(id)
    writes = []
    const full = exportOf(FULL)
    const again = await storeImported([full])
    expect(again.stored).toEqual([])
    expect(again.alreadyImported).toEqual([{ name: 'Trip — 2096', existingId: id, filled: ['fault-code scan', 'session log'] }])
    expect(await all()).toEqual([id])
    const after = await raw(id)
    const want = oracle(full, before.drive)
    expect(exact(after.drive)).toBe(exact(want.drive))
    expect(exact(after.diagnostics)).toBe(exact(want.diagnostics))
    expect(exact(after.points)).toBe(exact(before.points))
    expect(writes.sort()).toEqual(['diagnostics', 'drives'])                       // the points are not rewritten
    expect(after.drive.contentHash).toBe(before.drive.contentHash)
    expect([after.drive.name, after.drive.note, after.drive.tags]).toEqual(['Renamed by the owner', 'a note', ['Street']])
    expect(after.drive.warnings).toMatchObject({ faultCodes: 'none', recordingGaps: 'none' })
  })

  it('the reverse order (full, then without scan and log) changes nothing and writes nothing', async () => {
    const first = await storeImported([exportOf(FULL)])
    const id = first.stored[0].id
    const before = await raw(id)
    writes = []
    const again = await storeImported([exportOf({ profile: true })])
    expect(again.alreadyImported).toEqual([{ name: 'Trip — 2096', existingId: id, filled: [] }])
    expect(writes).toEqual([])
    expect(exact(await raw(id))).toBe(exact(before))
  })

  it('the scan, then the log, in two later imports, ends as the full import (and the log first, then the scan, too)', async () => {
    for (const order of [[{ scan: 'COMPLETE', codes: [] }, { log: LOG }], [{ log: LOG }, { scan: 'COMPLETE', codes: [] }]] as Parts[][]) {
      closeStorage(); (globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
      const id = (await storeImported([exportOf({ profile: true })])).stored[0].id
      const stored = (await raw(id)).drive
      for (const p of order) await storeImported([exportOf({ ...p, profile: true })])
      const after = await raw(id)
      const want = oracle(exportOf(FULL), stored)
      expect(exact(after.drive)).toBe(exact(want.drive))
      expect(exact(after.diagnostics)).toBe(exact(want.diagnostics))
    }
  })

  it('a scan that answers fills in "not checked"; the flag is recomputed from every cause (a lost link keeps it raised)', async () => {
    const id = (await storeImported([exportOf({ log: [...LOG, GAP] })])).stored[0].id
    expect((await raw(id)).drive).toMatchObject({ hasWarnings: true, warnings: { faultCodes: 'not-checked', recordingGaps: 'found' } })
    const out = await storeImported([exportOf({ scan: 'COMPLETE', codes: [], log: [...LOG, GAP] })])
    expect(out.alreadyImported[0].filled).toEqual(['fault-code scan'])
    expect((await raw(id)).drive).toMatchObject({ hasWarnings: true, warnings: { faultCodes: 'none', recordingGaps: 'found' } })
  })

  it('an incomplete scan replaces none and is replaced by an answer; an answer is never turned back into "not checked"', async () => {
    const id = (await storeImported([exportOf({ scan: 'PARTIAL', codes: [] })])).stored[0].id
    expect((await raw(id)).drive.warnings.faultCodes).toBe('not-checked')
    // a complete scan with an active code answers it
    let out = await storeImported([exportOf({ scan: 'COMPLETE', codes: [{ code: 'P0123', status: 'ACTIVE' }] })])
    expect(out.alreadyImported[0].filled).toEqual(['fault-code scan'])
    const answered = await raw(id)
    expect(answered.drive).toMatchObject({ hasWarnings: true, warnings: { faultCodes: 'found' } })
    // a partial scan again, or a complete one that disagrees: nothing changes, nothing written
    for (const p of [{ scan: 'PARTIAL', codes: [] }, { scan: 'COMPLETE', codes: [] }] as Parts[]) {
      writes = []
      out = await storeImported([exportOf(p)])
      expect(out.alreadyImported[0].filled).toEqual([])
      expect(writes).toEqual([])
      expect(exact(await raw(id))).toBe(exact(answered))
    }
  })

  it('a stored scan with codes but no flag (an older record) counts as a scan', async () => {
    const s = exportOf({ scan: 'PARTIAL', codes: [{ code: 'P0456', status: 'PERMANENT' }] })
    delete (s as Partial<Session>).dtcScan; delete (s as Partial<Session>).dtcScanStatus
    const id = (await storeImported([s])).stored[0].id
    const before = await raw(id)
    // another not-checked scan does not replace it (it is not missing); only an answer would
    const out = await storeImported([exportOf({ scan: 'PARTIAL', codes: [] })])
    expect(out.alreadyImported[0].filled).toEqual([])
    expect(exact(await raw(id))).toBe(exact(before))
  })

  it('a missing profile is filled in: recorded summary figures, laps (with the best lap), performance runs; the points kept', async () => {
    const id = (await storeImported([exportOf({ scan: 'COMPLETE', codes: [], log: LOG })])).stored[0].id
    const before = await raw(id)
    expect(before.drive).toMatchObject({ hasLaps: false, bestLapMs: null, summary: { distanceKm: 0.333 } })
    const full = exportOf(FULL)
    const out = await storeImported([full])
    expect(out.alreadyImported[0].filled).toEqual(['profile'])
    const after = await raw(id)
    const want = oracle(full, before.drive)
    expect(exact(after.drive)).toBe(exact(want.drive))
    expect(after.drive).toMatchObject({ hasLaps: true, bestLapMs: 59_500, summary: { distanceKm: 0.3, distanceSource: 'recorded' } })
    expect(after.points.trip.perfRuns).toEqual([{ type: '0-100', timeMs: 5400 }])
    expect(exact(after.points.trip.points)).toBe(exact(before.points.trip.points))
    expect(after.drive.tags).toEqual([])                                           // the owner's tags stay: the profile's do not come in
  })

  it('a snapshot taken while recording (fewer points) is a different drive, not merged', async () => {
    await storeImported([exportOf(FULL)])
    const out = await storeImported([exportOf({ points: POINTS.slice(0, 2) })])
    expect(out.stored).toHaveLength(1)
    expect(await all()).toHaveLength(2)
  })

  it('one transaction: a fill-in whose second write fails leaves all three records exactly as they were', async () => {
    const id = (await storeImported([exportOf({ profile: true })])).stored[0].id
    const before = await raw(id)
    const inner = g.IDBObjectStore.prototype.put
    g.IDBObjectStore.prototype.put = function (this: { name: string }, ...a: unknown[]) {
      if (this.name === 'drives') throw new DOMException('invented failure', 'QuotaExceededError')
      return inner.apply(this, a)
    }
    try {
      await expect(storeImported([exportOf(FULL)])).rejects.toThrow('invented failure')
    } finally { g.IDBObjectStore.prototype.put = inner }
    expect(exact(await raw(id))).toBe(exact(before))
  })

  it('a session-only entry matched by its proof gets its missing log', async () => {
    const scanOnly = (log?: typeof LOG): Session => ({ ...exportOf({ scan: 'COMPLETE', codes: [], ...(log ? { log } : {}) }), trip: null, dtcScanFinishedAtMs: T0 + 5 })
    const id = (await storeImported([scanOnly()])).stored[0].id
    const out = await storeImported([scanOnly(LOG)])
    expect(out.alreadyImported).toEqual([{ name: 'Trip — 2096', existingId: id, filled: ['session log'] }])
    expect((await raw(id)).drive.warnings.recordingGaps).toBe('none')
  })
})
