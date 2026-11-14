import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, beforeEach } from 'vitest'
import { openDB } from 'idb'
import JSZip from 'jszip'
import { storeImported, listDrives, closeStorage, renameDrive, storageStatus, loadDrive } from './storage'
import { makeBackup, restoreBackup, BACKUP_FORMAT_VERSION } from './backup'
import type { Session } from '../types/session'

// #384 ticket 02: back up all drives, and restore. At the backup and storage modules' public functions, over an
// in-memory IndexedDB (fake-indexeddb). jsdom has no Worker, so these run the in-thread path; the worker path runs in a
// real browser (scripts/idb-check). Invented data only.

const T0 = 4_000_000_000_000   // an invented time (2096)

/** An invented drive with the values a JSON file loses unless they are kept on purpose: NaN, −0, undefined. */
function drive(id: string, lat: number): Session {
  return {
    id, name: `Invented ${id}`, importedAt: T0, tags: ['Street'],
    trip: {
      points: [0, 1, 2].map((i) => ({ ts: T0 + i * 1000, lat: lat + i / 1000, lng: 20, speedKph: i === 0 ? -0 : 50, rpm: 2000,
        boostPsi: i === 1 ? NaN : null, coolantC: 90 })) as never,
      summary: { distanceKm: 1, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 50, peakRpm: 2000, peakBoostPsi: null, peakLatG: 0,
        peakLongG: 0, peakSpeedKph: 50, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 2000, avgFuelEconomy: NaN, modeBreakdown: {},
        peakEvents: [], aggressionScore: undefined },
    },
    diagnostics: { canInventory: [], fpsTimeline: [], sessionEvents: [{ ts: 1000, type: 'SESSION', message: 'invented' }],
      decodeTrace: [], probeResults: [], dtcResults: [{ module: 'PCM', code: 'P0000', status: 'ACTIVE', description: 'invented', severity: undefined }] },
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' },
    dtcScan: true, dtcScanStatus: 'COMPLETE',
  }
}

/** Every record of the three stores, as IndexedDB holds them. */
async function rawStores(): Promise<Record<string, unknown[]>> {
  closeStorage()
  const db = await openDB('sapphire')
  const out: Record<string, unknown[]> = {}
  for (const s of ['drives', 'points', 'diagnostics'] as const) out[s] = await db.getAll(s)
  db.close()
  return out
}
/**
 * Byte for byte, per record: every value as exact bytes, in key order. A number is its 8 IEEE-754 bytes (so NaN, −0 and
 * ±Infinity are told apart), a key holding undefined differs from a missing key, and arrays and objects differ. Not V8's
 * own serializer: that also records whether V8 holds a number as a small integer or a double (50 vs 50.0), which no
 * script can see or set and which a JSON parse cannot keep.
 */
function exact(v: unknown): string {
  if (typeof v === 'number') return `n${Buffer.from(new Float64Array([v]).buffer).toString('hex')}`
  if (v === undefined) return 'u'
  if (v === null || typeof v !== 'object') return `${typeof v}:${JSON.stringify(v)}`
  if (Array.isArray(v)) return `[${Array.from({ length: v.length }, (_, i) => (i in v ? exact(v[i]) : 'hole')).join(',')}]`
  if (Object.prototype.toString.call(v) !== '[object Object]') throw new Error(`unexpected ${Object.prototype.toString.call(v)}`)
  return `{${Object.keys(v).map((k) => `${JSON.stringify(k)}:${exact((v as Record<string, unknown>)[k])}`).join(',')}}`
}
const bytes = (records: unknown[]) => records.map(exact)

const fresh = () => {
  closeStorage()
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
}
beforeEach(fresh)

describe('#384 back up all drives, and restore', () => {
  it('backup, then restore into an empty database, gives the same records byte for byte', async () => {
    await storeImported([drive('a', 10), drive('b', 11)])
    await renameDrive('a', 'Renamed after import')
    const before = await rawStores()
    const backup = await makeBackup()
    expect(backup.drives).toBe(2)
    expect(backup.name).toMatch(/^sapphire-backup-\d{4}-\d{2}-\d{2}\.zip$/)

    fresh()
    const outcome = await restoreBackup(backup.blob)
    expect(outcome).toEqual({ added: 2, skipped: 0, notRestorable: [] })
    const after = await rawStores()
    for (const s of ['drives', 'points', 'diagnostics']) expect(bytes(after[s])).toEqual(bytes(before[s]))
    expect((await listDrives()).map((d) => d.name).sort()).toEqual(['Invented b', 'Renamed after import'])
  })

  it('restore only adds: a drive already here is skipped and counted; newer drives are untouched', async () => {
    await storeImported([drive('old', 20)])
    const older = await makeBackup()
    await storeImported([drive('newer', 21)])
    await renameDrive('old', 'Renamed since the backup')
    const before = await rawStores()
    const outcome = await restoreBackup(older.blob)
    expect(outcome).toEqual({ added: 0, skipped: 1, notRestorable: [] })
    const after = await rawStores()
    for (const s of ['drives', 'points', 'diagnostics']) expect(bytes(after[s])).toEqual(bytes(before[s]))
  })

  it('a backup holding one drive is a valid file (the one-drive share file of slice 7 uses this format)', async () => {
    await storeImported([drive('only', 30)])
    const one = await makeBackup()
    fresh()
    expect(await restoreBackup(one.blob)).toEqual({ added: 1, skipped: 0, notRestorable: [] })
    expect((await listDrives()).map((d) => d.id)).toEqual(['only'])
  })

  it('a backup from a newer format version is refused with its reason, and nothing is restored', async () => {
    await storeImported([drive('x', 40)])
    const { blob } = await makeBackup()
    const zip = await JSZip.loadAsync(blob)
    const manifest = JSON.parse(await zip.file('manifest.json')!.async('string'))
    zip.file('manifest.json', JSON.stringify({ ...manifest, version: BACKUP_FORMAT_VERSION + 1 }))
    const newer = await zip.generateAsync({ type: 'blob' })
    fresh()
    await expect(restoreBackup(newer)).rejects.toThrow(/newer Sapphire.*format 2.*reads format 1.*Nothing was restored/s)
    expect(await listDrives()).toEqual([])
  })

  it('a file that is not a Sapphire backup is refused in plain words', async () => {
    const z = new JSZip(); z.file('readme.txt', 'invented')
    await expect(restoreBackup(await z.generateAsync({ type: 'blob' }))).rejects.toThrow(/not a Sapphire backup/)
  })

  it('after a failed upgrade, the backup is written from the version-1 data, and restores into a working Sapphire', async () => {
    const good = drive('good', 50)
    const bad = { ...drive('bad', 51), trip: { ...drive('bad', 51).trip!, points: 'not an array' } }
    const v1 = await openDB('sapphire', 1, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
    await v1.put('sessions', good); await v1.put('sessions', bad); v1.close()
    await expect(listDrives()).rejects.toThrow()
    expect(storageStatus().state).toBe('upgrade-failed')

    const backup = await makeBackup()
    expect(backup.drives).toBe(2)
    const zip = await JSZip.loadAsync(backup.blob)
    expect(JSON.parse(await zip.file('manifest.json')!.async('string'))).toMatchObject({ source: 'version-1' })

    fresh()
    const outcome = await restoreBackup(backup.blob)
    expect(outcome.added).toBe(1)
    expect(outcome.notRestorable).toEqual(['Invented bad'])
    expect((await listDrives()).map((d) => d.id)).toEqual(['good'])
  })

  it('progress is reported while the file is built (one step per drive)', async () => {
    await storeImported([drive('p', 60), drive('q', 61), drive('r', 62)])
    const steps: [number, number][] = []
    await makeBackup((done, total) => steps.push([done, total]))
    expect(steps.at(-1)).toEqual([3, 3])
    expect(steps.length).toBeGreaterThanOrEqual(3)
  })

  it('text and keys that look like the format\'s markers come back as they were, and so does Infinity', async () => {
    const s = drive('marks', 70)
    s.name = '{"$sapphire":"NaN"}'
    ;(s.diagnostics!.sessionEvents[0] as { message: string }).message = '$sapphire'
    ;(s.trip!.summary as unknown as Record<string, unknown>).peakLatG = Infinity
    ;(s.trip!.summary as unknown as Record<string, unknown>).peakLongG = -Infinity
    ;(s.trip!.summary as unknown as Record<string, unknown>).modeBreakdown = { $sapphire: 'NaN', other: { $sapphire: 'object', value: 1 } }
    await storeImported([s])
    const before = await rawStores()
    const backup = await makeBackup()
    fresh()
    await restoreBackup(backup.blob)
    const after = await rawStores()
    for (const st of ['drives', 'points', 'diagnostics']) expect(bytes(after[st])).toEqual(bytes(before[st]))
    expect((await loadDrive('marks'))!.name).toBe('{"$sapphire":"NaN"}')
  })

  it('a file that is not a ZIP, or whose manifest is broken, is refused in plain words', async () => {
    await expect(restoreBackup(new Blob(['invented, not a zip']))).rejects.toThrow(/not a Sapphire backup/)
    const z = new JSZip(); z.file('manifest.json', '{ broken')
    await expect(restoreBackup(await z.generateAsync({ type: 'blob' }))).rejects.toThrow(/not a Sapphire backup/)
  })

  it('a damaged backup (a drive whose points belong to another id) restores nothing', async () => {
    await storeImported([drive('d1', 80), drive('d2', 81)])
    const { blob } = await makeBackup()
    const zip = await JSZip.loadAsync(blob)
    const second = JSON.parse(await zip.file('drives/2.json')!.async('string'))
    second.points.id = 'someone-else'
    zip.file('drives/2.json', JSON.stringify(second))
    const bad = await zip.generateAsync({ type: 'blob' })
    fresh()
    await expect(restoreBackup(bad)).rejects.toThrow(/damaged.*Nothing was restored/s)
    expect(await listDrives()).toEqual([])

    // and a drive without its content hash (the index that finds "already imported")
    const z2 = await JSZip.loadAsync(blob)
    const first = JSON.parse(await z2.file('drives/1.json')!.async('string'))
    delete first.drive.contentHash
    z2.file('drives/1.json', JSON.stringify(first))
    await expect(restoreBackup(await z2.generateAsync({ type: 'blob' }))).rejects.toThrow(/damaged.*Nothing was restored/s)
    expect(await listDrives()).toEqual([])
  })

  it('a stored value the format cannot hold stops the backup instead of being lost', async () => {
    const s = drive('dated', 90)
    ;(s.diagnostics as unknown as Record<string, unknown>).when = new Date(T0)
    await storeImported([s])
    await expect(makeBackup()).rejects.toThrow(/cannot write to a backup/)
  })

  it('a browser with no Sapphire data gets an empty backup, and no database is created by it', async () => {
    const backup = await makeBackup()
    expect(backup.drives).toBe(0)
    const names = (await indexedDB.databases()).map((d) => d.name)
    expect(names).not.toContain('sapphire')
  })

  it('a version-1 session that fails to convert in any way is named, and the others still restore', async () => {
    const good = drive('good1', 100)
    const odd = { ...drive('odd', 101), diagnostics: { sessionEvents: 'not a list' } }
    const v1 = await openDB('sapphire', 1, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
    await v1.put('sessions', good); await v1.put('sessions', odd); v1.close()
    const backup = await makeBackup()
    fresh()
    const outcome = await restoreBackup(backup.blob)
    expect(outcome.added + outcome.notRestorable.length).toBe(2)
    expect((await listDrives()).map((d) => d.id)).toContain('good1')
  })

  it('whatever the upgrade accepted restores: a version-1 drive with no name, tags or import time, and a session-only entry', async () => {
    const bare = { id: 'bare', trip: drive('bare', 110).trip }                       // only what the upgrade needs
    const sessionOnly = { ...drive('s-only', 111), trip: null }
    const v1 = await openDB('sapphire', 1, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
    await v1.put('sessions', bare); await v1.put('sessions', sessionOnly); v1.close()
    expect((await listDrives()).map((d) => d.id).sort()).toEqual(['bare', 's-only'])  // the upgrade took both
    const before = await rawStores()
    const backup = await makeBackup()
    fresh()
    expect(await restoreBackup(backup.blob)).toEqual({ added: 2, skipped: 0, notRestorable: [] })
    const after = await rawStores()
    for (const st of ['drives', 'points', 'diagnostics']) expect(bytes(after[st])).toEqual(bytes(before[st]))
  })
})
