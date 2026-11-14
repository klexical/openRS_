/**
 * Sapphire's storage module (#384 ticket 01): the only code that touches IndexedDB.
 *
 * Database `sapphire`, version 2, three stores keyed by the drive's id:
 * - `drives`: one small record per drive (or drive-less session): the session's own fields without its points and log,
 *   plus what the list shows (headline numbers, flags, which warning causes were checked, the content hash);
 * - `points`: `{ id, trip }`, read only when that drive is opened;
 * - `diagnostics`: `{ id, diagnostics }`, read only when that drive is opened.
 * Version 1 kept whole sessions in one `sessions` store; the upgrade splits each one and keeps every field and id.
 */
import { openDB, type IDBPDatabase, type IDBPTransaction } from 'idb'
import { sha256Hex } from './sha256'
import type { DiagnosticData, DtcEntry, Session, TripData, TripSummary } from '../types/session'

/**
 * What a warning cause found for one drive. "not-checked": the import holds nothing to check it with (no fault-code scan,
 * no session log), or this delivery does not check it yet. Never read "not checked" as "none" (owner, 2026-11-14).
 */
export type CauseState = 'found' | 'none' | 'not-checked'

export interface WarningCauses {
  faultCodes: CauseState
  recordingGaps: CauseState
  /** Not checked in this delivery: their parsing waits for a real rc.8 drive export (owner, 2026-11-14). */
  knockEvents: 'not-checked'
  criticalTemperatures: 'not-checked'
}

/** The small record per drive (or drive-less session): everything but the points and the diagnostic log. */
export type DriveRecord = Omit<Session, 'trip' | 'diagnostics'> & {
  /** First point's time, else the session's stated start, else the import time (the list's order). */
  driveStart: number
  summary: TripSummary | null
  pointCount: number
  hasDrive: boolean
  hasSession: boolean
  hasLaps: boolean
  hasWarnings: boolean
  warnings: WarningCauses
  /** SHA-256 (hex) of the drive's points, computed once when stored; see [contentHash]. */
  contentHash: string
  /** A session with no drive: what can prove it is the same as another one (see [sameSession]); absent for a drive. */
  sessionProof?: SessionProof
}

/** What proves two drive-less sessions are the same one (owner, 2026-11-14, draft 10; the integrator's rule). */
export interface SessionProof {
  /** The fault-code scan's own finish time (never an export time). */
  scanFinishedAtMs?: number
  /** SHA-256 of the whole session event list (relative time, type, message, in order), when it has 3 or more events. */
  eventsHash?: string
  /** The session's length, when the detail stated it. */
  durationMs?: number
}

export type StorageStatus =
  | { state: 'opening' }
  | { state: 'ready' }
  | { state: 'blocked' }
  | { state: 'outdated' }
  | { state: 'upgrade-failed'; reason: string }

/** An import with nothing readable in it: no drive, no session log and no fault codes. It is not stored. */
export class NothingReadableError extends Error {
  constructor() {
    super('Nothing in this file could be read: it holds no drive, no session log and no fault codes. Nothing was stored.')
    this.name = 'NothingReadableError'
  }
}

export interface ImportOutcome {
  stored: DriveRecord[]
  alreadyImported: { name: string; existingId: string }[]
}

const DB_NAME = 'sapphire'
const DB_VERSION = 2
const DRIVES = 'drives'
const POINTS = 'points'
const DIAGNOSTICS = 'diagnostics'
const OLD_SESSIONS = 'sessions'
const ALL = [DRIVES, POINTS, DIAGNOSTICS]
/** Keys this module adds to a drive record; stripped again when a session is rebuilt. */
const DERIVED = ['driveStart', 'summary', 'pointCount', 'hasDrive', 'hasSession', 'hasLaps', 'hasWarnings', 'warnings',
  'contentHash', 'sessionProof'] as const

/**
 * A write whose promise nobody awaits: its failure already reaches the caller through the transaction's own result
 * (`tx.done`, or openDB for the upgrade), so a rejection here must not also surface as an unhandled one.
 */
const settle = (request: Promise<unknown>): void => { request.catch(() => {}) }

// ── Status, for the page's plain-words messages ──

let status: StorageStatus = { state: 'opening' }
const listeners = new Set<(s: StorageStatus) => void>()
function setStatus(s: StorageStatus) { status = s; for (const l of listeners) l(s) }
export function storageStatus(): StorageStatus { return status }
export function onStorageStatus(cb: (s: StorageStatus) => void): () => void {
  listeners.add(cb)
  return () => { listeners.delete(cb) }
}

// ── Opening and upgrading ──

let dbPromise: Promise<IDBPDatabase> | null = null
let openDb: IDBPDatabase | null = null

function getDb(): Promise<IDBPDatabase> {
  if (status.state === 'outdated') return Promise.reject(new Error('Sapphire was updated in another tab: reload'))
  if (dbPromise) return dbPromise
  let upgrading = false
  let convertProblem: string | null = null      // our own plain words for a record that cannot convert
  setStatus({ state: 'opening' })
  // Promise.resolve().then: an open() that throws at once rejects like any other failure to open
  dbPromise = Promise.resolve().then(() => openDB(DB_NAME, DB_VERSION, {
    async upgrade(db, oldVersion, _newVersion, tx) {
      upgrading = true
      // An error the transaction itself hits (a full disk) aborts it outside the try below; openDB then rejects
      tx.done.catch((e: unknown) => console.warn('[Sapphire] Storage update stopped:', e instanceof Error ? e.name : e))
      try {
        const drives = db.createObjectStore(DRIVES, { keyPath: 'id' })
        drives.createIndex('byStart', 'driveStart')
        drives.createIndex('byTag', 'tags', { multiEntry: true })
        drives.createIndex('byHash', 'contentHash', { unique: false })
        db.createObjectStore(POINTS, { keyPath: 'id' })
        db.createObjectStore(DIAGNOSTICS, { keyPath: 'id' })
        if (oldVersion >= 1 && db.objectStoreNames.contains(OLD_SESSIONS)) {
          const t = tx as unknown as IDBPTransaction<unknown, string[], 'versionchange'>
          let cursor = await t.objectStore(OLD_SESSIONS).openCursor()
          while (cursor) {
            const r = split(cursor.value)              // throws on a record it cannot convert
            settle(t.objectStore(DRIVES).put(r.drive))
            settle(t.objectStore(POINTS).put(r.points))
            settle(t.objectStore(DIAGNOSTICS).put(r.diagnostics))
            cursor = await cursor.continue()
          }
          // Only after every record converted, in the same transaction
          db.deleteObjectStore(OLD_SESSIONS)
        }
      } catch (err) {
        if (err instanceof ConvertError) convertProblem = err.message
        else console.warn('[Sapphire] Storage update failed:', err instanceof Error ? err.name : err)
        try { tx.abort() } catch { /* the engine already ended it */ }   // version 1 stays exactly as it was
      }
    },
    blocked() { setStatus({ state: 'blocked' }) },
    blocking() {
      // A newer Sapphire in another tab asks for the database: let it upgrade, and say so here
      openDb?.close()
      openDb = null
      dbPromise = null
      setStatus({ state: 'outdated' })
    },
    terminated() { openDb = null; dbPromise = null },
  })).then(
    (db) => { openDb = db; setStatus({ state: 'ready' }); return db },
    (err) => {
      // Any failure to open says so in plain words, never the engine's text (that goes to the console): a record that
      // could not convert, an engine or quota error that aborted the upgrade, or a database newer than this Sapphire.
      // In each case nothing stored was changed.
      console.warn('[Sapphire] Storage could not be opened:', err instanceof Error ? err.name : err)
      setStatus({ state: 'upgrade-failed', reason: !upgrading ? "Sapphire couldn't open its storage."
        : convertProblem !== null ? `Sapphire couldn't update its storage: ${convertProblem}.`
          : "Sapphire couldn't update its storage." })
      throw err
    },
  )
  return dbPromise
}

/** Close the connection (tests, and a newer tab's request). The next call opens it again. */
export function closeStorage(): void {
  openDb?.close()
  openDb = null
  dbPromise = null
  setStatus({ state: 'opening' })
}

// ── A session split into its three records ──

interface Split { drive: DriveRecord; points: { id: string; trip: TripData | null }; diagnostics: { id: string; diagnostics: DiagnosticData | null } }

class ConvertError extends Error {}

function split(raw: unknown): Split {
  const s = raw as Session & Record<string, unknown>
  if (!s || typeof s !== 'object' || typeof s.id !== 'string') throw new ConvertError('a stored record has no id')
  const trip = (s.trip ?? null) as TripData | null
  if (trip !== null && !Array.isArray(trip.points)) throw new ConvertError(`the stored drive "${String(s.name)}" has unreadable points`)
  const diagnostics = (s.diagnostics ?? null) as DiagnosticData | null
  const { trip: _t, diagnostics: _d, ...rest } = s
  void _t; void _d
  return {
    drive: { ...(rest as Omit<Session, 'trip' | 'diagnostics'>), ...derive(s, trip, diagnostics) },
    points: { id: s.id, trip },
    diagnostics: { id: s.id, diagnostics },
  }
}

function derive(s: Session, trip: TripData | null, diagnostics: DiagnosticData | null): Omit<DriveRecord, keyof Omit<Session, 'trip' | 'diagnostics'>> {
  const points = trip?.points ?? []
  const stated = Date.parse(s.meta?.sessionStart ?? '')
  const driveStart = points.length > 0 && Number.isFinite(points[0].ts) ? points[0].ts
    : Number.isFinite(stated) ? stated : s.importedAt
  const warnings = warningCauses(s, diagnostics)
  return {
    driveStart,
    summary: trip?.summary ?? null,
    pointCount: points.length,
    hasDrive: points.length > 0,
    hasSession: diagnostics !== null,
    hasLaps: (trip?.laps?.length ?? 0) > 0,
    hasWarnings: warnings.faultCodes === 'found' || warnings.recordingGaps === 'found',
    warnings,
    contentHash: contentHash(s),
    ...(points.length === 0 ? { sessionProof: sessionProof(s, diagnostics) } : {}),
  }
}

/** Fewer events than this are the app's start-up lines, which two different short sessions share. */
const PROOF_MIN_EVENTS = 3

function sessionProof(s: Session, d: DiagnosticData | null): SessionProof {
  const events = hasSessionLog(d) ? d!.sessionEvents : []
  return {
    ...(typeof s.dtcScanFinishedAtMs === 'number' ? { scanFinishedAtMs: s.dtcScanFinishedAtMs } : {}),
    ...(events.length >= PROOF_MIN_EVENTS
      ? { eventsHash: sha256Hex(`${HASH_VERSION}\nevents\n${canonical(events.map((e) => [e.ts, e.type, e.message]))}`) } : {}),
    ...(typeof d?.sessionDurationMs === 'number' ? { durationMs: d.sessionDurationMs } : {}),
  }
}

/**
 * Two drive-less sessions are the same one when (a) both carry the scan's finish time and it is equal, or (b) both
 * carry an event list of at least 3 events and the whole list is equal, with equal durations when both state one.
 * Equal fault codes, and export times, prove nothing: without proof a session is imported again.
 */
function sameSession(a: SessionProof | undefined, b: SessionProof | undefined): boolean {
  if (!a || !b) return false
  if (a.scanFinishedAtMs !== undefined && a.scanFinishedAtMs === b.scanFinishedAtMs) return true
  return a.eventsHash !== undefined && a.eventsHash === b.eventsHash
    && (a.durationMs === undefined || b.durationMs === undefined || a.durationMs === b.durationMs)
}

// ── The warnings marker (owner, 2026-11-14: fault codes and recording gaps in this delivery) ──

/** Which fault-code entries raise the "with warnings" marker: active or pending in that drive's scan (owner, 2026-11-14). */
export function faultCodeRaisesMarker(entry: DtcEntry): boolean {
  const st = String(entry.status ?? '').toUpperCase()
  return st === 'ACTIVE' || st === 'PENDING'
}

/** The app's REC_GAP message names its reason first ("DISCONNECTED gap 12.0 s (…)"); only a link loss counts (GLOSSARY). */
const DISCONNECTED_GAP = /\bDISCONNECTED gap\b/

function warningCauses(s: Session, diagnostics: DiagnosticData | null): WarningCauses {
  const codes = diagnostics?.dtcResults ?? []
  // Owner, 2026-11-14: found = an active or pending code, whatever the scan's status; none = only a scan marked COMPLETE
  // with no such code; anything else (another status, no status, a text-only scan, no scan, a version-1 record) is
  // not checked, never none
  const faultCodes: CauseState = codes.some(faultCodeRaisesMarker) ? 'found'
    : s.dtcScan === true && s.dtcScanStatus === 'COMPLETE' ? 'none' : 'not-checked'
  const recordingGaps: CauseState = !hasSessionLog(diagnostics) ? 'not-checked'
    : diagnostics!.sessionEvents.some((e) => String(e.type).toUpperCase() === 'REC_GAP' && DISCONNECTED_GAP.test(e.message))
      ? 'found' : 'none'
  return { faultCodes, recordingGaps, knockEvents: 'not-checked', criticalTemperatures: 'not-checked' }
}

/**
 * Whether the import held a session log (the diagnostic detail file). An import with fault codes alone also gets a
 * diagnostics object: it is marked `sessionLog: false`; a version-1 record has no marker, and main's importer left every
 * log part empty in that case, so a record whose log parts are all empty has no log either.
 */
function hasSessionLog(d: DiagnosticData | null): boolean {
  if (d === null || d.sessionLog === false) return false
  return [d.sessionEvents, d.canInventory, d.fpsTimeline, d.decodeTrace, d.probeResults].some((part) => (part?.length ?? 0) > 0)
}

// ── "Already imported" ──

/** JSON with object keys sorted, so the same content always gives the same text. */
function canonical(v: unknown): string {
  if (v === null || typeof v !== 'object') return JSON.stringify(v) ?? 'null'
  if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`
  const o = v as Record<string, unknown>
  return `{${Object.keys(o).filter((k) => o[k] !== undefined).sort().map((k) => `${JSON.stringify(k)}:${canonical(o[k])}`).join(',')}}`
}

/**
 * The point fields that identify a drive, in the precision the app's CSV writes them (DriveExportBuilder.buildCsv, the
 * same precision in every app version from 2026-04-01 to staging 6212b15): time in whole milliseconds, latitude and
 * longitude to 6 decimals, speed to 2, rpm whole. The text is not always the same: before rc.7 the app formatted
 * these with the phone's language, so a decimal comma or other digits wrote different text (which Sapphire rejects
 * or reads differently), and such a drive's re-export from rc.7 on will not match it. Every version of Sapphire has kept these five fields on each point (the
 * `TripPoint` type since its first commit), so a drive stored by any of them hashes as its re-import does today; a
 * later parser that keeps more fields does not change the hash. Change the list only with a new HASH_VERSION.
 */
const HASH_VERSION = 'sapphire-drive-points/1'
const HASHED_POINT_FIELDS: readonly [field: 'ts' | 'lat' | 'lng' | 'speedKph' | 'rpm', decimals: number][] = [
  ['ts', 0], ['lat', 6], ['lng', 6], ['speedKph', 2], ['rpm', 0],
]
const fixed = (v: unknown, decimals: number): string => {
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n.toFixed(decimals) : 'NaN'
}

/**
 * SHA-256 that decides whether two imports are the same drive (owner, 2026-11-14, Q5 and Q7): the drive's own recording,
 * its points, alone, as the fixed list above. The summary and profile (distance, peaks, laps, performance runs, the
 * app's tags), the name, notes, tags, import and export times, fault codes, scan flag and session log do not decide
 * identity: they change between shares of one drive. A session with no drive is compared by its diagnostics. A
 * snapshot taken while recording has fewer points, so it is a different (partial) drive. A session with no drive gets a
 * hash of its own id: it is matched by its proof instead (owner, draft 10). Computed once when stored.
 */
function contentHash(s: Session): string {
  const points = s.trip?.points ?? []
  // a session with no drive is matched by its proof (sessionProof / sameSession), never by this hash
  if (points.length === 0) return sha256Hex(`${HASH_VERSION}\nsession ${s.id}`)
  const rows = points.map((p) => HASHED_POINT_FIELDS.map(([f, d]) => fixed((p as unknown as Record<string, unknown>)[f], d)).join(','))
  return sha256Hex(`${HASH_VERSION}\npoints ${HASHED_POINT_FIELDS.map(([f]) => f).join(',')}\n${rows.join('\n')}`)
}

// ── Public functions ──

/** The small record a session is stored as (no I/O): what the list sees. */
export function toDriveRecord(session: Session): DriveRecord {
  return split(session).drive
}

/** Every stored drive's small record; reads no points and no log. */
export async function listDrives(): Promise<DriveRecord[]> {
  const db = await getDb()
  return db.getAll(DRIVES) as Promise<DriveRecord[]>
}

/** One drive with its points and log, as a session; null when it is not stored. */
export async function loadDrive(id: string): Promise<Session | null> {
  const db = await getDb()
  const tx = db.transaction(ALL, 'readonly')
  const [drive, points, diagnostics] = await Promise.all([
    tx.objectStore(DRIVES).get(id), tx.objectStore(POINTS).get(id), tx.objectStore(DIAGNOSTICS).get(id),
  ]) as [Record<string, unknown> | undefined, { trip: TripData | null } | undefined, { diagnostics: DiagnosticData | null } | undefined]
  await tx.done
  if (!drive) return null
  const session = { ...drive }
  for (const k of DERIVED) delete session[k]
  return { ...(session as unknown as Session), trip: points?.trip ?? null, diagnostics: diagnostics?.diagnostics ?? null }
}

/**
 * Store freshly imported sessions in one transaction: all of them, or none (a batch ZIP). A session whose content is
 * already stored is not stored again and is returned in `alreadyImported` (never an error).
 */
export async function storeImported(sessions: Session[]): Promise<ImportOutcome> {
  // An import in which nothing could be read (no points, no diagnostics) is not stored, and says so (owner, draft 10)
  for (const s of sessions) {
    if ((s.trip?.points?.length ?? 0) === 0 && !s.diagnostics) throw new NothingReadableError()
  }
  const prepared = sessions.map(split)          // a session that cannot be split fails before anything is written
  const db = await getDb()
  const tx = db.transaction(ALL, 'readwrite')
  const outcome: ImportOutcome = { stored: [], alreadyImported: [] }
  const seen = new Map<string, string>()
  let storedSessions: DriveRecord[] | null = null  // stored drive-less sessions, read once when needed
  try {
    for (const r of prepared) {
      let existing: string | undefined
      if (r.drive.hasDrive) {
        existing = seen.get(r.drive.contentHash)
          ?? (await tx.objectStore(DRIVES).index('byHash').getKey(r.drive.contentHash)) as string | undefined
      } else {
        storedSessions ??= ((await tx.objectStore(DRIVES).getAll()) as DriveRecord[]).filter((d) => !d.hasDrive)
        existing = storedSessions.find((d) => sameSession(d.sessionProof, r.drive.sessionProof))?.id
        if (existing === undefined) storedSessions.push(r.drive)
      }
      if (existing !== undefined) { outcome.alreadyImported.push({ name: r.drive.name, existingId: existing }); continue }
      seen.set(r.drive.contentHash, r.drive.id)
      settle(tx.objectStore(DRIVES).put(r.drive))
      settle(tx.objectStore(POINTS).put(r.points))
      settle(tx.objectStore(DIAGNOSTICS).put(r.diagnostics))
      outcome.stored.push(r.drive)
    }
  } catch (err) {
    // A put that throws (e.g. DataCloneError) doesn't end the transaction on its own: abort so nothing is kept
    tx.abort()
    await tx.done.catch(() => {})
    throw err
  }
  await tx.done
  return outcome
}

async function updateDrive(id: string, change: (d: DriveRecord) => DriveRecord): Promise<DriveRecord | null> {
  const db = await getDb()
  const tx = db.transaction(DRIVES, 'readwrite')
  const d = (await tx.store.get(id)) as DriveRecord | undefined
  if (!d) { await tx.done; return null }
  const next = change(d)
  await tx.store.put(next)
  await tx.done
  return next
}

/** Rename: the small record only (the content hash is not recomputed). */
export function renameDrive(id: string, name: string): Promise<DriveRecord | null> {
  return updateDrive(id, (d) => ({ ...d, name }))
}

/** Replace the tags: the small record only. */
export function setDriveTags(id: string, tags: string[]): Promise<DriveRecord | null> {
  return updateDrive(id, (d) => ({ ...d, tags }))
}

/**
 * Run [write] in one read-write transaction over the three stores: all of it, or none. A call that throws part-way
 * does not end a transaction by itself (the earlier writes would commit), so it is aborted here.
 */
async function allOrNone(write: (tx: IDBPTransaction<unknown, string[], 'readwrite'>) => void): Promise<void> {
  const db = await getDb()
  const tx = db.transaction(ALL, 'readwrite')
  try {
    write(tx)
  } catch (err) {
    try { tx.abort() } catch { /* already ended */ }
    await tx.done.catch(() => {})
    throw err
  }
  await tx.done
}

/** Delete drives: each one's three records, all in one transaction; a failure deletes none of them. */
export function deleteDrives(ids: string[]): Promise<void> {
  return allOrNone((tx) => { for (const id of ids) for (const store of ALL) settle(tx.objectStore(store).delete(id)) })
}

/** Empty the three stores in one transaction; a failure empties none of them. */
export function clearAllDrives(): Promise<void> {
  return allOrNone((tx) => { for (const store of ALL) settle(tx.objectStore(store).clear()) })
}
