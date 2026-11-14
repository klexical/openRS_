// Exposes Sapphire's production modules unchanged; check.mjs drives them in a real browser.
import * as storage from '../../src/lib/storage'
import { importBatchZip } from '../../src/lib/import'
import { makeBackup, restoreBackup } from '../../src/lib/backup'
import { readEverything } from '../../src/lib/storageBackup'
import { buildBackup } from '../../src/lib/backupFormat'

/** A version-1 database as Sapphire stored it before #384: one `sessions` store holding whole sessions. */
function writeV1(sessions: unknown[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('sapphire', 1)
    req.onupgradeneeded = () => { req.result.createObjectStore('sessions', { keyPath: 'id' }) }
    req.onerror = () => reject(req.error)
    req.onsuccess = () => {
      const db = req.result
      const tx = db.transaction('sessions', 'readwrite')
      for (const s of sessions) tx.objectStore('sessions').put(s)
      tx.oncomplete = () => { db.close(); resolve() }
      tx.onerror = () => reject(tx.error)
    }
  })
}

/** Open the database at a version (none = current) without Sapphire: its version, stores and every record. */
function readRaw(version?: number): Promise<{ version: number; stores: string[]; records: Record<string, unknown[]> }> {
  return new Promise((resolve, reject) => {
    const req = version ? indexedDB.open('sapphire', version) : indexedDB.open('sapphire')
    req.onupgradeneeded = () => {}
    req.onerror = () => reject(req.error)
    req.onsuccess = () => {
      const db = req.result
      const stores = [...db.objectStoreNames]
      const out: Record<string, unknown[]> = {}
      if (stores.length === 0) { db.close(); resolve({ version: db.version, stores, records: out }); return }
      const tx = db.transaction(stores, 'readonly')
      for (const s of stores) { const r = tx.objectStore(s).getAll(); r.onsuccess = () => { out[s] = r.result } }
      tx.oncomplete = () => { db.close(); resolve({ version: db.version, stores, records: out }) }
    }
  })
}

/** Hold a connection open and never close it on request: an old tab running Sapphire from before #384. */
let held: IDBDatabase | null = null
function holdOpen(version: number): Promise<number> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('sapphire', version)
    req.onupgradeneeded = () => { if (req.result.objectStoreNames.length === 0) req.result.createObjectStore('other') }
    req.onerror = () => reject(req.error)
    req.onsuccess = () => { held = req.result; resolve(held.version) }
  })
}
const release = () => { held?.close(); held = null }

/** An invented version-1 session with [n] points of the export's full shape (1 Hz; about 0.6 kB a point). */
function inventedSession(id: string, n: number, seed: number) {
  const T0 = 4_000_000_000_000 + seed * 10_000_000   // invented times (2096)
  const points = Array.from({ length: n }, (_, i) => ({
    ts: T0 + i * 1000, lat: 10 + seed / 100 + i / 1e5, lng: 20 + i / 1e5, speedKph: 50 + (i % 40), rpm: 2000 + (i % 3000), gear: '3',
    boostPsi: (i % 20) / 2, boostSource: 'CAN_0F8', coolantC: 90, oilTempC: 95, ambientC: 15, rduTempC: 40, ptuTempC: 50, fuelPct: 50,
    tirePressLF: 36, tirePressRF: 36, tirePressLR: 36, tirePressRR: 36, tireTempLF: 30, tireTempRF: 30, tireTempLR: 30,
    tireTempRR: 30, wheelSpeedFL: 50, wheelSpeedFR: 50, wheelSpeedRL: 50, wheelSpeedRR: 50, latG: 0.1, longG: 0.05,
    brakePressure: 0, steeringAngle: 0, throttlePct: 20, driveMode: 'Normal', raceReady: false, awdTorqueL: 0, awdTorqueR: 0,
  }))
  return { id, name: `Invented ${id}`, importedAt: T0, tags: [],
    trip: { points, summary: { distanceKm: 60, durationMs: n * 1000, peakRpm: 4999, peakSpeedKph: 89, peakEvents: [], modeBreakdown: {} } },
    diagnostics: { canInventory: [], fpsTimeline: [], sessionEvents: [], decodeTrace: [], probeResults: [], dtcResults: [] },
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' } }
}
async function writeInventedV1(count: number, points: number) {
  for (let i = 0; i < count; i++) await writeV1([inventedSession(`hour-${i}`, points, i)])
}

/** Every value as exact bytes in key order (numbers as IEEE-754 bytes; undefined differs from a missing key). */
function exact(v: unknown): string {
  if (typeof v === 'number') {
    const b = new Uint8Array(new Float64Array([v]).buffer)
    return `n${Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')}`
  }
  if (v === undefined) return 'u'
  if (v === null || typeof v !== 'object') return `${typeof v}:${JSON.stringify(v)}`
  if (Array.isArray(v)) return `[${Array.from({ length: v.length }, (_, i) => (i in v ? exact(v[i]) : 'hole')).join(',')}]`
  return `{${Object.keys(v).map((k) => `${JSON.stringify(k)}:${exact((v as Record<string, unknown>)[k])}`).join(',')}}`
}

/** The three stores' records as exact strings, sorted by id (a whole-database comparison). */
async function exactStores(): Promise<Record<string, string[]>> {
  const raw = await readRaw()
  const out: Record<string, string[]> = {}
  for (const s of ['drives', 'points', 'diagnostics']) out[s] = (raw.records[s] ?? []).map(exact).sort()
  return out
}

/**
 * Run [work] while a timer on the page ticks every 10 ms; the longest gap between ticks is how long the page could not
 * answer input. [how] picks the path: makeBackup as built (a worker where one starts), or the page path forced.
 */
let lastBackup: Blob | null = null
async function backupWithHeartbeat(how: 'as-built' | 'page') {
  let longest = 0, last = performance.now(), ticking = true
  const tick = () => { const now = performance.now(); longest = Math.max(longest, now - last); last = now; if (ticking) setTimeout(tick, 10) }
  setTimeout(tick, 10)
  const t0 = performance.now()
  let steps = 0
  const file = how === 'as-built'
    ? await makeBackup(() => { steps++ })
    : { ...(await buildBackup(await readEverything(), () => { steps++ }, () => new Promise((r) => setTimeout(r, 0)))), builtIn: 'page' as const }
  const ms = performance.now() - t0
  ticking = false
  lastBackup = file.blob
  return { builtIn: file.builtIn, drives: file.drives, bytes: file.blob.size, name: file.name, ms: Math.round(ms), longestGapMs: Math.round(longest), steps }
}
const restoreLast = () => restoreBackup(lastBackup!)
/** Restore the last backup while a 10 ms page timer runs: how long it takes, and the longest gap (the page's pause). */
async function restoreWithHeartbeat() {
  let longest = 0, last = performance.now(), ticking = true
  const tick = () => { const now = performance.now(); longest = Math.max(longest, now - last); last = now; if (ticking) setTimeout(tick, 10) }
  setTimeout(tick, 10)
  const t0 = performance.now()
  const outcome = await restoreBackup(lastBackup!)
  const ms = performance.now() - t0
  ticking = false
  return { ...outcome, ms: Math.round(ms), longestGapMs: Math.round(longest) }
}
const deleteDatabase = () => new Promise<void>((resolve, reject) => {
  storage.closeStorage()
  const r = indexedDB.deleteDatabase('sapphire'); r.onsuccess = () => resolve(); r.onerror = () => reject(r.error)
})

Object.assign(window, { sapphire: { ...storage, importBatchZip, writeV1, readRaw, holdOpen, release, writeInventedV1,
  backupWithHeartbeat, restoreLast, restoreWithHeartbeat, deleteDatabase, exactStores }, ready: true })
