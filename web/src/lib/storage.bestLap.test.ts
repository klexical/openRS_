import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, beforeEach } from 'vitest'
import { openDB } from 'idb'
import JSZip from 'jszip'
import { storeImported, closeStorage, listDrives } from './storage'
import { makeBackup, restoreBackup, BACKUP_FORMAT_VERSION } from './backup'
import type { Session } from '../types/session'

// #384 ticket 04: the best lap in each drive's small record (the list reads no points), at the storage and backup
// modules' public functions over an in-memory IndexedDB. Invented data only.

const T0 = 4_000_000_000_000   // an invented time (2096)
function drive(id: string, laps?: number[]): Session {
  return {
    id, name: `Invented ${id}`, importedAt: T0, tags: [],
    trip: {
      points: [0, 1, 2].map((i) => ({ ts: T0 + i * 1000, lat: 10 + id.charCodeAt(0) / 1000, lng: 20, speedKph: 50, rpm: 2000 })) as never,
      summary: { distanceKm: 1, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 50, peakRpm: 2000, peakBoostPsi: null, peakLatG: 0,
        peakLongG: 0, peakSpeedKph: 50, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 2000, avgFuelEconomy: 0, modeBreakdown: {},
        peakEvents: [] },
      ...(laps ? { laps: laps.map((ms, i) => ({ lapNumber: i + 1, lapTimeMs: ms })) } : {}),
    },
    diagnostics: null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' },
  }
}

async function raw(id: string) {
  closeStorage()
  const db = await openDB('sapphire')
  const r = await db.get('drives', id)
  db.close()
  return r
}
/** Make a stored record look as live delivery A wrote it: no bestLapMs. */
async function asDeliveryA(id: string) {
  closeStorage()
  const db = await openDB('sapphire')
  const r = await db.get('drives', id)
  delete r.bestLapMs
  await db.put('drives', r)
  db.close()
}

const fresh = () => { closeStorage(); (globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory() }
beforeEach(fresh)

describe('#384 ticket 04: bestLapMs in the small record', () => {
  it('is the fastest valid lap; null when the laps hold no valid time, or there are none', async () => {
    await storeImported([drive('a', [118_000, 112_400, 0, -5, NaN, 115_900]), drive('b', [0]), drive('c')])
    const byId = Object.fromEntries((await listDrives()).map((d) => [d.id, d]))
    expect(byId.a.bestLapMs).toBe(112_400)
    expect(byId.a.hasLaps).toBe(true)
    expect(byId.b.bestLapMs).toBeNull()
    expect(byId.c.bestLapMs).toBeNull()
  })
})

describe('#384 ticket 04: backups across the change (format version unchanged)', () => {
  it('a backup made by delivery A (records without bestLapMs) restores, and the restored record has it', async () => {
    await storeImported([drive('a', [118_000, 112_400])])
    const asImported = await raw('a')
    await asDeliveryA('a')
    expect('bestLapMs' in await raw('a')).toBe(false)
    const backup = await makeBackup()
    const manifest = JSON.parse(await (await JSZip.loadAsync(backup.blob)).file('manifest.json')!.async('string'))
    expect(manifest.version).toBe(1)
    expect(BACKUP_FORMAT_VERSION).toBe(1)
    fresh()
    expect(await restoreBackup(backup.blob)).toEqual({ added: 1, skipped: 0, notRestorable: [] })
    expect(JSON.stringify(await raw('a'))).toBe(JSON.stringify(asImported))   // as an import stores it today
  })

  it('a backup made after the change restores the same record', async () => {
    await storeImported([drive('a', [118_000, 112_400]), drive('b')])
    const before = [await raw('a'), await raw('b')]
    const backup = await makeBackup()
    fresh()
    await restoreBackup(backup.blob)
    expect(JSON.stringify([await raw('a'), await raw('b')])).toBe(JSON.stringify(before))
  })
})
