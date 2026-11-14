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
  const { drive = true, diag = true, events = [], codes = [], dtcScan, scanStatus, laps = false, tags = [] } = o
  const points = drive ? [pt(0), pt(1), pt(2)] : []
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
async function driveZip(o: { compression: 'DEFLATE' | 'STORE'; reverse?: boolean; exportedAt?: number; scan?: unknown }): Promise<File> {
  const zip = new JSZip()
  const files: [string, string][] = [['drive_20960101_000000.csv', CSV], ['diagnostic_detail_20960101_000000.json', DIAG]]
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
