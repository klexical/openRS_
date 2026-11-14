import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { openDB } from 'idb'
import JSZip from 'jszip'
import {
  listDrives, loadDrive, storeImported, renameDrive, setDriveTags, deleteDrives, clearAllDrives, closeStorage,
  storageStatus, faultCodeRaisesMarker, toDriveRecord, type DriveRecord,
} from './storage'
import { importZip } from './import'
import type { DtcEntry, Session, SessionEvent, TripPoint } from '../types/session'

// #384 ticket 01: drives stored one record each (database version 2), with a safe upgrade from version 1. Tested at the
// storage module's public functions against an in-memory IndexedDB (fake-indexeddb); the upgrade, the failed upgrade
// and the two-tab cases also run in a real browser (scripts/idb-check). Invented data only.

const T0 = 4_000_000_000_000   // an invented time (2096)

const pt = (i: number): TripPoint => ({
  ts: T0 + i * 1000, lat: 10 + i * 0.0001, lng: 20, speedKph: 50 + i, rpm: 2000, gear: '3', boostPsi: null, coolantC: 90,
  oilTempC: 95, ambientC: 15, rduTempC: 40, ptuTempC: 50, fuelPct: 50, tirePressLF: 36, tirePressRF: 36, tirePressLR: 36,
  tirePressRR: 36, tireTempLF: 30, tireTempRF: 30, tireTempLR: 30, tireTempRR: 30, wheelSpeedFL: 50, wheelSpeedFR: 50,
  wheelSpeedRL: 50, wheelSpeedRR: 50, latG: 0, longG: 0, brakePressure: 0, steeringAngle: 0, throttlePct: 20,
  driveMode: 'Normal', raceReady: false, awdTorqueL: 0, awdTorqueR: 0,
} as unknown as TripPoint)

const dtc = (status: string): DtcEntry => ({ module: 'PCM', code: 'P0000', status, description: 'invented' })
const ev = (type: string, message: string, ts = 1000): SessionEvent => ({ ts, type, message })
const DISCONNECTED = 'drive 7: DISCONNECTED gap 12.0 s (adapter disconnected — samples skipped)'
const PAUSED = 'drive 7: PAUSED gap 30.0 s (recording paused)'
const NO_LOCATION = 'drive 7: NO_LOCATION gap 8.0 s (no location update while connected — telemetry not recorded)'

interface Opts { drive?: boolean; diag?: boolean; events?: SessionEvent[]; codes?: DtcEntry[]; dtcScan?: boolean; scanStatus?: string | null; laps?: boolean; tags?: string[] }
function session(id: string, o: Opts = {}): Session {
  // a session log always holds events: the default one carries the app's session start (an empty log is codes-only)
  const { drive = true, diag = true, events = [ev('SESSION', 'Started')], codes = [], dtcScan, scanStatus, laps = false, tags = [] } = o
  // each invented drive records its own points: drives are told apart by their points (#384, the owner's Q5 / Q7)
  const off = [...id].reduce((n, c) => n + c.charCodeAt(0), 0) / 10_000
  const points = drive ? [pt(0), pt(1), pt(2)].map((p) => ({ ...p, lat: p.lat + off })) : []
  const s: Session = {
    id, name: `Invented ${id}`, importedAt: T0 + 5000, tags,
    trip: drive ? {
      points,
      summary: { distanceKm: 1.2, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 51, peakRpm: 2000, peakBoostPsi: null,
        peakLatG: 0, peakLongG: 0, peakSpeedKph: 52, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 2000, avgFuelEconomy: 0,
        modeBreakdown: { Normal: 2 }, peakEvents: [] },
      ...(laps ? { laps: [{ number: 1, timeMs: 61000 }] as never } : {}),
    } : null,
    diagnostics: diag ? { canInventory: [], fpsTimeline: [], sessionEvents: events, decodeTrace: [], probeResults: [], dtcResults: codes } : null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' },
  }
  if (dtcScan !== undefined) s.dtcScan = dtcScan
  if (scanStatus !== undefined) s.dtcScanStatus = scanStatus
  return s
}

/** A version-1 database as Sapphire stored it before this change: one `sessions` store, whole sessions. */
async function writeV1(sessions: unknown[]) {
  const db = await openDB('sapphire', 1, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
  for (const s of sessions) await db.put('sessions', s)
  db.close()
}

// Observer on the stores: which store each read and write touched, and which transactions were opened
const log = { reads: [] as string[], writes: [] as string[], txs: [] as { stores: string[]; mode: string }[] }
const g = globalThis as unknown as Record<string, { prototype: Record<string, unknown> }>
const originals: [Record<string, unknown>, string, unknown][] = []
function observe() {
  // `into` names the log array, read at call time: resetLog() replaces the arrays
  const wrap = (proto: Record<string, unknown>, name: string, into: 'reads' | 'writes', storeOf: (self: unknown) => string) => {
    const orig = proto[name] as (...a: unknown[]) => unknown
    originals.push([proto, name, orig])
    proto[name] = function (this: unknown, ...a: unknown[]) { log[into].push(storeOf(this)); return orig.apply(this, a) }
  }
  const storeName = (s: unknown) => (s as { name: string }).name
  const indexStore = (i: unknown) => (i as { objectStore: { name: string } }).objectStore.name
  for (const m of ['get', 'getAll', 'getAllKeys', 'openCursor', 'count', 'getKey']) {
    wrap(g.IDBObjectStore.prototype, m, 'reads', storeName)
    wrap(g.IDBIndex.prototype, m, 'reads', indexStore)
  }
  for (const m of ['put', 'add', 'delete', 'clear']) wrap(g.IDBObjectStore.prototype, m, 'writes', storeName)
  const tx = g.IDBDatabase.prototype.transaction as (...a: unknown[]) => unknown
  originals.push([g.IDBDatabase.prototype, 'transaction', tx])
  g.IDBDatabase.prototype.transaction = function (this: unknown, stores: unknown, mode?: unknown, ...rest: unknown[]) {
    log.txs.push({ stores: [stores].flat() as string[], mode: String(mode ?? 'readonly') })
    return tx.call(this, stores, mode, ...rest)
  }
}
const resetLog = () => { log.reads = []; log.writes = []; log.txs = [] }

beforeEach(() => {
  try { closeStorage() } catch { /* stub */ }
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
  resetLog()
  observe()
})
afterEach(() => { for (const [proto, name, orig] of originals.splice(0).reverse()) proto[name] = orig })

const byId = (ds: DriveRecord[]) => Object.fromEntries(ds.map((d) => [d.id, d]))

describe('#384 upgrade from version 1', () => {
  it('three sessions (drive + session, drive alone, session alone) open as version 2: same ids, every point and event readable', async () => {
    const both = session('both', { events: [ev('SESSION', 'Started')] })
    const driveOnly = session('drive-only', { diag: false })
    const sessionOnly = session('session-only', { drive: false, events: [ev('STATE', 'Connected')] })
    await writeV1([both, driveOnly, sessionOnly])

    const drives = byId(await listDrives())
    expect(Object.keys(drives).sort()).toEqual(['both', 'drive-only', 'session-only'])
    expect([drives.both.hasDrive, drives.both.hasSession]).toEqual([true, true])
    expect([drives['drive-only'].hasDrive, drives['drive-only'].hasSession]).toEqual([true, false])
    expect([drives['session-only'].hasDrive, drives['session-only'].hasSession]).toEqual([false, true])
    for (const s of [both, driveOnly, sessionOnly]) expect(await loadDrive(s.id)).toEqual(s)
  })

  it('a field this slice does not show is kept as stored, and the old store is gone after a successful upgrade', async () => {
    const s = { ...session('kept'), futureField: { nested: [1, 2, 3] } }
    await writeV1([s])
    await listDrives()
    expect(await loadDrive('kept')).toEqual(s)
    closeStorage()
    const raw = await openDB('sapphire')
    expect(raw.version).toBe(2)
    expect([...raw.objectStoreNames].sort()).toEqual(['diagnostics', 'drives', 'points'])
    raw.close()
  })

  it('a record that cannot convert (points not an array) aborts the upgrade: version 1 untouched and readable', async () => {
    const good = session('good')
    const bad = { ...session('bad'), trip: { ...session('bad').trip!, points: 'not an array' } }
    await writeV1([good, bad])

    await expect(listDrives()).rejects.toThrow()
    const status = storageStatus()
    expect(status.state).toBe('upgrade-failed')
    expect(status.state === 'upgrade-failed' && status.reason).toMatch(/could not|couldn't/i)

    closeStorage()
    const raw = await openDB('sapphire')
    expect(raw.version).toBe(1)
    expect([...raw.objectStoreNames]).toEqual(['sessions'])
    expect(await raw.getAll('sessions')).toEqual([bad, good])   // key order
    raw.close()
  })

  it('an engine error during the upgrade (DataCloneError) leaves version 1 and says so without the engine\'s text (M1)', async () => {
    await writeV1([session('clone')])
    const proto = g.IDBObjectStore.prototype as Record<string, (...a: unknown[]) => unknown>
    const orig = proto.put
    originals.push([proto, 'put', orig])
    proto.put = function (this: { name: string }, ...a: unknown[]) {
      if (this.name === 'drives') throw new DOMException('The object could not be cloned (engine text).', 'DataCloneError')
      return orig.apply(this, a)
    }
    await expect(listDrives()).rejects.toThrow()
    for (const [p2, name, o] of originals.splice(0).reverse()) p2[name] = o
    const status = storageStatus()
    expect(status.state).toBe('upgrade-failed')
    const reason = status.state === 'upgrade-failed' ? status.reason : ''
    expect(reason).toBe("Sapphire couldn't update its storage.")
    expect(reason).not.toMatch(/engine|DataCloneError|cloned/)
    closeStorage()
    const raw = await openDB('sapphire')
    expect([raw.version, [...raw.objectStoreNames]]).toEqual([1, ['sessions']])
    raw.close()
  })

  it('an engine error that aborts the upgrade asynchronously (ConstraintError) says so in plain words, no unhandled error (M1)', async () => {
    await writeV1([session('async')])
    const proto = g.IDBObjectStore.prototype as Record<string, (...a: unknown[]) => unknown>
    const orig = proto.put
    originals.push([proto, 'put', orig])
    proto.put = function (this: { name: string; add: (v: unknown) => unknown }, ...a: unknown[]) {
      if (this.name !== 'drives') return orig.apply(this, a)
      this.add(a[0])
      return this.add(a[0])                    // the same key twice: the request fails later, aborting the transaction
    }
    await expect(listDrives()).rejects.toThrow()
    for (const [p2, name, o] of originals.splice(0).reverse()) p2[name] = o
    const status = storageStatus()
    expect(status.state === 'upgrade-failed' && status.reason).toBe("Sapphire couldn't update its storage.")
    closeStorage()
    const raw = await openDB('sapphire')
    expect([raw.version, [...raw.objectStoreNames]]).toEqual([1, ['sessions']])
    raw.close()
  })

  it('any other failure to open is reported in plain words too, never left at "opening" (a newer database than this Sapphire)', async () => {
    const newer = await openDB('sapphire', 5, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
    newer.close()
    await expect(listDrives()).rejects.toThrow()
    const status = storageStatus()
    expect(status.state).toBe('upgrade-failed')
    expect(status.state === 'upgrade-failed' && status.reason).toMatch(/couldn't open its storage/)
  })

  it('the drives store has the three indexes; the hash index is not unique', async () => {
    await writeV1([session('a')])
    await listDrives()
    closeStorage()
    const raw = await openDB('sapphire')
    const store = raw.transaction('drives').store
    expect([...store.indexNames].sort()).toEqual(['byHash', 'byStart', 'byTag'])
    expect(store.index('byTag').multiEntry).toBe(true)
    expect(store.index('byHash').unique).toBe(false)
    raw.close()
  })
})

describe('#384 reads and writes touch only what they need', () => {
  it('listing every stored drive reads no points', async () => {
    await writeV1(Array.from({ length: 5 }, (_, i) => session(`d${i}`)))
    await listDrives()            // the upgrade itself reads the old store; count from here on
    resetLog()
    const drives = await listDrives()
    expect(drives).toHaveLength(5)
    expect(log.reads.filter((s) => s === 'points' || s === 'diagnostics')).toEqual([])
  })

  it('opening a drive reads its points', async () => {
    await storeImported([session('open-me')])
    resetLog()
    const s = await loadDrive('open-me')
    expect(s?.trip?.points).toHaveLength(3)
    expect(log.reads).toContain('points')
  })

  it('a rename and a tag edit write the small record only, and never recompute the content hash', async () => {
    const { stored } = await storeImported([session('r')])
    const hash = stored[0].contentHash
    resetLog()
    expect((await renameDrive('r', 'Renamed'))?.name).toBe('Renamed')
    expect((await setDriveTags('r', ['Track Day']))?.tags).toEqual(['Track Day'])
    expect(new Set(log.writes)).toEqual(new Set(['drives']))
    const [d] = await listDrives()
    expect([d.name, d.tags, d.contentHash]).toEqual(['Renamed', ['Track Day'], hash])
  })

  it('a delete removes the drive\'s three records in one transaction', async () => {
    await storeImported([session('x'), session('y', { events: [ev('STATE', 'other drive')] })])
    resetLog()
    await deleteDrives(['x'])
    const writeTxs = log.txs.filter((t) => t.mode === 'readwrite')
    expect(writeTxs).toHaveLength(1)
    expect(writeTxs[0].stores.sort()).toEqual(['diagnostics', 'drives', 'points'])
    expect((await listDrives()).map((d) => d.id)).toEqual(['y'])
    expect(await loadDrive('x')).toBeNull()
    closeStorage()
    const raw = await openDB('sapphire')
    for (const store of ['drives', 'points', 'diagnostics'] as const) expect(await raw.getKey(store, 'x')).toBeUndefined()
    raw.close()
  })

  // Spec review H3 / QA: a failure part-way (a synchronous throw does not end a transaction by itself) keeps everything
  const injectThrow = (method: 'delete' | 'clear', when: (self: { name: string }, arg: unknown) => boolean) => {
    const proto = g.IDBObjectStore.prototype as Record<string, (...a: unknown[]) => unknown>
    const orig = proto[method]
    originals.push([proto, method, orig])
    proto[method] = function (this: { name: string }, ...a: unknown[]) {
      if (when(this, a[0])) throw new DOMException('injected', 'DataError')
      return orig.apply(this, a)
    }
  }

  it('a delete that fails on its second drive deletes none of them', async () => {
    await storeImported([session('first'), session('second')])
    injectThrow('delete', (_s, key) => key === 'second')
    await expect(deleteDrives(['first', 'second'])).rejects.toThrow()
    for (const [proto, name, orig] of originals.splice(0).reverse()) proto[name] = orig
    expect((await listDrives()).map((d) => d.id).sort()).toEqual(['first', 'second'])
    expect(await loadDrive('first')).not.toBeNull()
  })

  it('clear all that fails on its second store clears none of them', async () => {
    await storeImported([session('p1'), session('q1')])
    injectThrow('clear', (self) => self.name === 'points')
    await expect(clearAllDrives()).rejects.toThrow()
    for (const [proto, name, orig] of originals.splice(0).reverse()) proto[name] = orig
    expect((await listDrives()).map((d) => d.id).sort()).toEqual(['p1', 'q1'])
    expect((await loadDrive('p1'))?.trip?.points).toHaveLength(3)
  })

  it('clear all empties the three stores in one transaction', async () => {
    await storeImported([session('p'), session('q')])
    resetLog()
    await clearAllDrives()
    const writeTxs = log.txs.filter((t) => t.mode === 'readwrite')
    expect(writeTxs).toHaveLength(1)
    expect(writeTxs[0].stores.sort()).toEqual(['diagnostics', 'drives', 'points'])
    closeStorage()
    const raw = await openDB('sapphire')
    for (const store of ['drives', 'points', 'diagnostics'] as const) expect(await raw.count(store)).toBe(0)
    raw.close()
  })
})

describe('#384 the warnings flag (fault codes and recording gaps; knock and temperatures not checked)', () => {
  // Fault codes (owner, 2026-11-14): found = an ACTIVE or PENDING code whatever the scan's status; none = only a scan
  // marked COMPLETE with no such code; everything else (another status, no status, a text-only scan, no scan) = not checked
  const C = { dtcScan: true, scanStatus: 'COMPLETE' } as const
  const CASES: [string, Opts, { faultCodes: string; recordingGaps: string; flag: boolean }][] = [
    ['an active fault code (complete scan)', { ...C, codes: [dtc('ACTIVE')] }, { faultCodes: 'found', recordingGaps: 'none', flag: true }],
    ['a pending fault code (complete scan)', { ...C, codes: [dtc('PENDING')] }, { faultCodes: 'found', recordingGaps: 'none', flag: true }],
    ['an active fault code in a PARTIAL scan (found whatever the status)', { dtcScan: true, scanStatus: 'PARTIAL', codes: [dtc('ACTIVE')] },
      { faultCodes: 'found', recordingGaps: 'none', flag: true }],
    ['an active code from a text-only scan (found)', { dtcScan: true, scanStatus: null, codes: [dtc('Active')] },
      { faultCodes: 'found', recordingGaps: 'none', flag: true }],
    ['only stored, permanent and status-less codes in a complete scan', { ...C, codes: [dtc('STORED'), dtc('PERMANENT'), dtc('UNKNOWN')] },
      { faultCodes: 'none', recordingGaps: 'none', flag: false }],
    ['a complete scan with no codes', { ...C }, { faultCodes: 'none', recordingGaps: 'none', flag: false }],
    ...(['PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'RUNNING'].map((st) =>
      [`a ${st} scan with no active or pending code`, { dtcScan: true, scanStatus: st, codes: [dtc('PERMANENT')] },
        { faultCodes: 'not-checked', recordingGaps: 'none', flag: false }]) as [string, Opts, { faultCodes: string; recordingGaps: string; flag: boolean }][]),
    ['a scan with no status (an older app)', { dtcScan: true, scanStatus: null }, { faultCodes: 'not-checked', recordingGaps: 'none', flag: false }],
    ['no fault-code scan in the import', { dtcScan: false }, { faultCodes: 'not-checked', recordingGaps: 'none', flag: false }],
    ['a REC_GAP with the DISCONNECTED reason', { ...C, events: [ev('REC_GAP', DISCONNECTED)] },
      { faultCodes: 'none', recordingGaps: 'found', flag: true }],
    ['only a pause and a missing location', { ...C, events: [ev('REC_GAP', PAUSED), ev('REC_GAP', NO_LOCATION)] },
      { faultCodes: 'none', recordingGaps: 'none', flag: false }],
    ['no session log', { diag: false, dtcScan: false }, { faultCodes: 'not-checked', recordingGaps: 'not-checked', flag: false }],
    ['none of them', { ...C, events: [ev('SESSION', 'Started')] }, { faultCodes: 'none', recordingGaps: 'none', flag: false }],
    ['a disconnected gap and no fault-code scan (one cause found shows though another was not checked)',
      { dtcScan: false, events: [ev('REC_GAP', DISCONNECTED)] }, { faultCodes: 'not-checked', recordingGaps: 'found', flag: true }],
  ]

  for (const [label, opts, want] of CASES) {
    it(`at import: ${label}`, async () => {
      const { stored } = await storeImported([session('w', opts)])
      const d = stored[0]
      expect([d.warnings.faultCodes, d.warnings.recordingGaps, d.hasWarnings]).toEqual([want.faultCodes, want.recordingGaps, want.flag])
      expect([d.warnings.knockEvents, d.warnings.criticalTemperatures]).toEqual(['not-checked', 'not-checked'])
    })
  }

  it('fault codes without a session log: the gap cause is not checked (at import, through the importer)', async () => {
    const s = await importZip(await driveZip({ compression: 'DEFLATE', diag: false,
      scan: { scan: { status: 'COMPLETE' }, codes: [{ module: 'PCM', code: 'P0000', status: 'STORED', description: 'invented' }] } }))
    expect(s.diagnostics?.dtcResults).toHaveLength(1)                     // the importer keeps the codes in a diagnostics object
    const d = toDriveRecord(s)
    expect([d.warnings.faultCodes, d.warnings.recordingGaps]).toEqual(['none', 'not-checked'])
  })

  it('fault codes without a session log: the gap cause is not checked (at the upgrade)', async () => {
    // as main's importer stored it: codes, and every log part empty because there was no diagnostic detail file
    const v1 = session('codes-only', { codes: [dtc('ACTIVE')], events: [] })
    delete v1.dtcScan
    await writeV1([v1])
    const [d] = await listDrives()
    expect([d.warnings.faultCodes, d.warnings.recordingGaps, d.hasWarnings]).toEqual(['found', 'not-checked', true])
  })

  it('at the upgrade (version-1 records carry no scan status: an active or pending code = found; anything else = not checked)', async () => {
    const v1 = (id: string, o: Opts) => { const s = session(id, o); delete s.dtcScan; return s }
    await writeV1([
      v1('active', { codes: [dtc('ACTIVE')] }),
      v1('stored-only', { codes: [dtc('STORED')] }),
      v1('no-codes', {}),
      v1('gap', { events: [ev('REC_GAP', DISCONNECTED)] }),
      v1('pause', { events: [ev('REC_GAP', PAUSED)] }),
      v1('no-log', { diag: false }),
    ])
    const d = byId(await listDrives())
    const row = (id: string) => [d[id].warnings.faultCodes, d[id].warnings.recordingGaps, d[id].hasWarnings]
    expect(row('active')).toEqual(['found', 'none', true])
    expect(row('stored-only')).toEqual(['not-checked', 'none', false])   // no scan status in version 1: never "none"
    expect(row('no-codes')).toEqual(['not-checked', 'none', false])
    expect(row('gap')).toEqual(['not-checked', 'found', true])
    expect(row('pause')).toEqual(['not-checked', 'none', false])
    expect(row('no-log')).toEqual(['not-checked', 'not-checked', false])
  })

  it('the rule is one small function: active and pending raise the marker; stored, permanent and status-less do not', () => {
    expect(['ACTIVE', 'PENDING', 'STORED', 'PERMANENT', 'UNKNOWN'].map((s) => faultCodeRaisesMarker(dtc(s))))
      .toEqual([true, true, false, false, false])
  })
})

// ── "Already imported": the same drive's parsed content, however its ZIP was built ──
const CSV = '# openrs_csv_v2\ntimestamp_ms,lat,lng,speed_kph,rpm\n' +
  [0, 1, 2].map((i) => `${T0 + i * 1000},10.00${i},20.000,${50 + i},2000`).join('\n') + '\n'
const DIAG = JSON.stringify({ sessionEvents: [{ relMs: 1000, type: 'REC_GAP', message: DISCONNECTED }] })
const csvRows = (n: number) => '# openrs_csv_v2\ntimestamp_ms,lat,lng,speed_kph,rpm\n' +
  Array.from({ length: n }, (_, i) => `${T0 + i * 1000},10.00${i},20.000,${50 + i},2000`).join('\n') + '\n'
/** The app's profile file for the same drive: its own figures and the tags set in the app. */
const profile = (tags: string) => JSON.stringify({ version: 2, drive: { startTime: T0, endTime: T0 + 2000, distanceKm: 1.5,
  maxSpeedKph: 52, peakRpm: 2000, tags }, peaks: [], bookmarks: [] })
async function driveZip(o: { compression: 'DEFLATE' | 'STORE'; reverse?: boolean; exportedAt?: number; scan?: unknown;
  diag?: boolean; profileTags?: string; rows?: number }): Promise<File> {
  const zip = new JSZip()
  const files: [string, string][] = [['drive_20960101_000000.csv', o.rows ? csvRows(o.rows) : CSV]]
  if (o.diag !== false) files.push(['diagnostic_detail_20960101_000000.json', DIAG])
  if (o.profileTags !== undefined) files.push(['drive_profile_20960101_000000.json', profile(o.profileTags)])
  if (o.scan !== undefined) files.push(['dtc_scan_20960101_000000.json', JSON.stringify(o.scan)])
  if (o.exportedAt !== undefined) files.push(['manifest.json', JSON.stringify({ exportedAt: o.exportedAt,
    files: files.map(([name]) => ({ name, type: name.startsWith('drive_') ? 'drive_csv' : name.startsWith('dtc') ? 'dtc_json' : 'diagnostic_detail' })) })])
  for (const [n, t] of o.reverse ? [...files].reverse() : files) zip.file(n, t)
  return new File([await zip.generateAsync({ type: 'blob', compression: o.compression })], 'invented.zip')
}

describe('#384 already imported', () => {
  it('the same drive from two ZIPs with different bytes is one drive, and says so instead of throwing', async () => {
    const a = await driveZip({ compression: 'DEFLATE', exportedAt: T0 + 60_000 })
    const b = await driveZip({ compression: 'STORE', reverse: true, exportedAt: T0 + 3_600_000 })
    expect(new Uint8Array(await a.arrayBuffer())).not.toEqual(new Uint8Array(await b.arrayBuffer()))
    const first = await storeImported([await importZip(a)])
    const second = await storeImported([await importZip(b)])
    expect(first.stored).toHaveLength(1)
    expect(second.stored).toEqual([])
    expect(second.alreadyImported).toEqual([{ name: expect.any(String), existingId: first.stored[0].id }])
    expect(await listDrives()).toHaveLength(1)
    expect(first.stored[0].contentHash).toMatch(/^[0-9a-f]{64}$/)   // SHA-256: 256 bits
  })

  // Owner, 2026-11-14 (Q5, Q7): two imports are the same drive when the drive's own recording (its points) is the same
  it('the same drive with and without its profile file is one drive', async () => {
    const a = await storeImported([await importZip(await driveZip({ compression: 'DEFLATE' }))])
    const b = await storeImported([await importZip(await driveZip({ compression: 'DEFLATE', profileTags: 'Street' }))])
    expect([a.stored.length, b.stored.length, b.alreadyImported.length]).toEqual([1, 0, 1])
  })

  it('the same drive after a tag edit in the app (its profile) is one drive', async () => {
    await storeImported([await importZip(await driveZip({ compression: 'DEFLATE', profileTags: 'Street' }))])
    const b = await storeImported([await importZip(await driveZip({ compression: 'DEFLATE', profileTags: 'Street,Track Day' }))])
    expect(b.alreadyImported).toHaveLength(1)
  })

  it('the same drive with and without its fault-code report and session log is one drive', async () => {
    await storeImported([await importZip(await driveZip({ compression: 'DEFLATE', diag: false }))])
    const b = await storeImported([await importZip(await driveZip({ compression: 'DEFLATE',
      scan: { scan: { status: 'COMPLETE' }, codes: [{ module: 'PCM', code: 'P0000', status: 'ACTIVE', description: 'invented' }] } }))])
    expect(b.alreadyImported).toHaveLength(1)
  })

  it('a snapshot with fewer points (taken while recording) is a different drive', async () => {
    await storeImported([await importZip(await driveZip({ compression: 'DEFLATE', rows: 2 }))])
    const b = await storeImported([await importZip(await driveZip({ compression: 'DEFLATE', rows: 3 }))])
    expect([b.stored.length, (await listDrives()).length]).toEqual([1, 2])
  })

  it('a point field beyond the hashed list keeps the hash (a later parser that reads one more column)', () => {
    const base = session('h')
    const extra = { ...base, trip: { ...base.trip!, points: base.trip!.points.map((p) => ({ ...p, coolantC: 42, aNewColumn: 7 })) } }
    expect(toDriveRecord(extra as Session).contentHash).toBe(toDriveRecord(base).contentHash)
  })

  it('a version-1 record with fewer point fields hashes the same as its re-import today', async () => {
    const zip = await driveZip({ compression: 'DEFLATE' })
    const today = await importZip(zip)
    // as an older Sapphire stored it: only the fields its point type had (ts, lat, lng, rpm, speedKph and a few more)
    const older = { ...today, trip: { ...today.trip!, points: today.trip!.points.map(({ ts, lat, lng, rpm, speedKph }) => ({ ts, lat, lng, rpm, speedKph })) } }
    delete older.dtcScan; delete older.dtcScanStatus
    await writeV1([older])
    const again = await storeImported([await importZip(zip)])
    expect(again.alreadyImported.map((x) => x.existingId)).toEqual([older.id])
  })

  it('−0 hashes as 0; NaN is written as NaN, never as 0 or null (pins the hashed bytes)', () => {
    const base = session('z')
    const withPoint = (p: Partial<TripPoint>) => ({ ...base, trip: { ...base.trip!, points: [{ ...base.trip!.points[0], ...p }, ...base.trip!.points.slice(1)] } })
    const h = (s: Session) => toDriveRecord(s).contentHash
    expect(h(withPoint({ speedKph: -0 }))).toBe(h(withPoint({ speedKph: 0 })))
    expect(h(withPoint({ rpm: NaN }))).not.toBe(h(withPoint({ rpm: 0 })))
    expect(h(withPoint({ rpm: NaN }))).toBe(h(withPoint({ rpm: NaN })))
  })

  it('values are compared as the CSV rounds them (lat / lng 6 decimals, speed 2)', () => {
    const base = session('r')
    const noisy = { ...base, trip: { ...base.trip!, points: base.trip!.points.map((p) => ({ ...p, lat: p.lat + 1e-9, speedKph: p.speedKph + 1e-4 })) } }
    expect(toDriveRecord(noisy).contentHash).toBe(toDriveRecord(base).contentHash)
  })

  it('a drive stored before the update is recognised when imported again', async () => {
    const zip = await driveZip({ compression: 'DEFLATE' })
    const before = await importZip(zip)
    delete before.dtcScan                                     // version-1 records never carried it
    await writeV1([before])
    const again = await storeImported([await importZip(zip)])
    expect(again.stored).toEqual([])
    expect(again.alreadyImported.map((x) => x.existingId)).toEqual([before.id])
  })

  it('the importer records whether a fault-code scan was present and its status (a scan with no codes is not "no scan")', async () => {
    const noScan = await importZip(await driveZip({ compression: 'DEFLATE' }))
    const complete = await importZip(await driveZip({ compression: 'DEFLATE', scan: { scan: { status: 'COMPLETE' }, codes: [] } }))
    const partial = await importZip(await driveZip({ compression: 'DEFLATE', scan: { scan: { status: 'PARTIAL' }, codes: [] } }))
    const noStatus = await importZip(await driveZip({ compression: 'DEFLATE', scan: { codes: [] } }))
    const pending = await importZip(await driveZip({ compression: 'DEFLATE',
      scan: { scan: { status: 'PARTIAL' }, codes: [{ module: 'PCM', code: 'P0000', status: 'PENDING', description: 'invented' }] } }))
    expect([noScan.dtcScan, complete.dtcScan, partial.dtcScan, noStatus.dtcScan, pending.dtcScan]).toEqual([false, true, true, true, true])
    expect([complete.dtcScanStatus, partial.dtcScanStatus, noStatus.dtcScanStatus]).toEqual(['COMPLETE', 'PARTIAL', null])
    // the same drive five times: read each one's record without storing (storing would say "already imported")
    expect([noScan, complete, partial, noStatus, pending].map((s) => toDriveRecord(s).warnings.faultCodes))
      .toEqual(['not-checked', 'none', 'not-checked', 'not-checked', 'found'])
  })
})

// L104: the app writes a code with its failure type ("P0123-1C", displayCode); main's text reader dropped that line
describe('#384 the fault-code text reader', () => {
  const TEXT = [
    '═══════════════════════════════════════════════════════════',
    '  openRS_ DTC Scan Report',
    '═══════════════════════════════════════════════════════════',
    '',
    '─── PCM ────────────────────────────────────────────────────',
    '  P0123-1C  [Active]  Invented description',
    '  P0456  [Permanent]  Another invented description',
    '',
    'Total: 2 fault code(s) across 1 module(s)',
  ].join('\n')
  it('keeps a code written with a failure type (the app\'s exact line), as its base code', async () => {
    const zip = new JSZip()
    zip.file('drive_20960101_000000.csv', CSV)
    zip.file('dtc_scan_20960101_000000.txt', TEXT)
    const s = await importZip(new File([await zip.generateAsync({ type: 'blob' })], 'invented.zip'))
    expect(s.diagnostics?.dtcResults.map((d) => [d.module, d.code, d.status])).toEqual([['PCM', 'P0123', 'Active'], ['PCM', 'P0456', 'Permanent']])
    expect([s.dtcScan, s.dtcScanStatus]).toEqual([true, null])                 // a text-only scan has no status
    const d = (await storeImported([s])).stored[0]
    expect([d.warnings.faultCodes, d.hasWarnings]).toEqual(['found', true])
  })
})

// Owner (draft 10) and the integrator's rule: a session with no drive is "already imported" only on proof:
// (a) both carry the scan's own finish time and it is equal; or (b) both carry a session log of at least 3 events and
// the whole list is equal (relative time, type, message, in order), with equal session durations when both state one.
// Export times and equal fault codes never count; nothing readable is not stored.
describe('#384 a session with no drive: already imported only on proof', () => {
  const logOf = (n: number, tag = '') => Array.from({ length: n }, (_, i) => ev(i === 0 ? 'SESSION' : 'STATE', `invented ${i}${tag}`, i * 1000))
  const sess = (id: string, o: { events?: SessionEvent[]; finishedAt?: number; durationMs?: number; codes?: DtcEntry[]; log?: boolean }) => {
    const s = session(id, { drive: false, events: o.events ?? [], codes: o.codes ?? [] })
    if (o.log === false) s.diagnostics!.sessionLog = false
    if (o.durationMs !== undefined) s.diagnostics!.sessionDurationMs = o.durationMs
    if (o.finishedAt !== undefined) s.dtcScanFinishedAtMs = o.finishedAt
    return s
  }
  const twice = async (a: Session, b: Session) => { await storeImported([a]); return storeImported([b]) }

  it('(a) the same scan finish time proves it: already imported', async () => {
    const r = await twice(sess('a1', { finishedAt: T0 + 5, codes: [dtc('ACTIVE')], log: false }), sess('a2', { finishedAt: T0 + 5, codes: [dtc('ACTIVE')], log: false }))
    expect(r.alreadyImported.map((x) => x.existingId)).toEqual(['a1'])
  })

  it('(b) the same whole event list (3 or more events) proves it: already imported', async () => {
    const r = await twice(sess('b1', { events: logOf(4) }), sess('b2', { events: logOf(4) }))
    expect(r.alreadyImported.map((x) => x.existingId)).toEqual(['b1'])
  })

  it('equal fault codes alone prove nothing: two scan-only imports are two records', async () => {
    const r = await twice(sess('c1', { codes: [dtc('ACTIVE')], log: false }), sess('c2', { codes: [dtc('ACTIVE')], log: false }))
    expect([r.stored.length, (await listDrives()).length]).toEqual([1, 2])
  })

  it('two minimal sessions with the same start-up lines (fewer than 3 events) both import', async () => {
    const r = await twice(sess('d1', { events: logOf(2) }), sess('d2', { events: logOf(2) }))
    expect(r.stored).toHaveLength(1)
  })

  it('the same events with different session durations both import', async () => {
    const r = await twice(sess('e1', { events: logOf(3), durationMs: 60_000 }), sess('e2', { events: logOf(3), durationMs: 61_000 }))
    expect(r.stored).toHaveLength(1)
  })

  it('different scan finish times: both import even with the same codes and events', async () => {
    const r = await twice(sess('f1', { finishedAt: T0 + 1, events: logOf(1) }), sess('f2', { finishedAt: T0 + 2, events: logOf(1) }))
    expect(r.stored).toHaveLength(1)
  })

  it('a version-1 session is recognised by its event list', async () => {
    const v1 = sess('v1-session', { events: logOf(5) })
    await writeV1([v1])
    const r = await storeImported([sess('again', { events: logOf(5) })])
    expect(r.alreadyImported.map((x) => x.existingId)).toEqual(['v1-session'])
  })

  it('the importer reads the scan finish time (detail JSON first, then the scan JSON) and the session duration', async () => {
    const zip = async (files: Record<string, string>) => {
      const z = new JSZip(); for (const [n, t] of Object.entries(files)) z.file(n, t)
      return importZip(new File([await z.generateAsync({ type: 'blob' })], 'invented.zip'))
    }
    const detail = (o: object) => JSON.stringify({ sessionEvents: logOf(3).map((e) => ({ relMs: e.ts, type: e.type, message: e.message })), ...o })
    const both = await zip({ 'diagnostic_detail_20960101_000000.json': detail({ meta: { sessionDurationMs: 90_000 }, dtcScan: { status: 'COMPLETE', finishedAtMs: T0 + 7 } }),
      'dtc_scan_20960101_000000.json': JSON.stringify({ timestamp: T0 + 999, scan: { status: 'COMPLETE', finishedAtMs: T0 + 8 }, codes: [] }) })
    const jsonOnly = await zip({ 'diagnostic_detail_20960101_000000.json': detail({ dtcScan: { status: 'NONE' } }),
      'dtc_scan_20960101_000000.json': JSON.stringify({ timestamp: T0 + 999, scan: { status: 'COMPLETE', finishedAtMs: T0 + 8 }, codes: [] }) })
    const none = await zip({ 'diagnostic_detail_20960101_000000.json': detail({ dtcScan: { status: 'NONE' } }) })
    expect([both.dtcScanFinishedAtMs, both.diagnostics?.sessionDurationMs]).toEqual([T0 + 7, 90_000])
    expect(jsonOnly.dtcScanFinishedAtMs).toBe(T0 + 8)                     // never the export's root timestamp
    expect(none.dtcScanFinishedAtMs).toBeUndefined()
  })

  it('a file with nothing readable is not stored, and says so (two of them are not "already imported" as each other)', async () => {
    const empty = async (name: string) => { const z = new JSZip(); z.file(name, 'invented'); return importZip(new File([await z.generateAsync({ type: 'blob' })], 'invented.zip')) }
    await expect(storeImported([await empty('readme.txt')])).rejects.toThrow(/Nothing in this file could be read/)
    await expect(storeImported([await empty('other.txt')])).rejects.toThrow(/Nothing in this file could be read/)
    expect(await listDrives()).toEqual([])
  })
})

describe('#384 two tabs', () => {
  it('the old tab closes its connection when a newer version asks, and reports "outdated"', async () => {
    await storeImported([session('t')])
    expect(storageStatus().state).toBe('ready')
    const newer = await openDB('sapphire', 3, { upgrade() {} })   // another tab, a newer Sapphire
    expect(newer.version).toBe(3)
    expect(storageStatus().state).toBe('outdated')
    newer.close()
  })

  it('a blocked upgrade reports "blocked" and continues by itself once the other tab closes', async () => {
    await writeV1([session('held')])
    const oldTab = await openDB('sapphire', 1)                   // an old Sapphire that does not close on request
    const listing = listDrives()
    await new Promise((r) => setTimeout(r, 50))
    expect(storageStatus().state).toBe('blocked')
    oldTab.close()
    expect((await listing).map((d) => d.id)).toEqual(['held'])
    expect(storageStatus().state).toBe('ready')
  })
})
