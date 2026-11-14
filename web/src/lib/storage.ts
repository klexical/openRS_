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
  /**
   * The fastest valid lap (a time above 0), for the list (#384 ticket 04); null when the drive has no lap with a valid time.
   * Absent: stored before ticket 04 (live delivery A, or a tab still running it); filled in when the drive is next
   * opened ([fillBestLap]).
   */
  bestLapMs?: number | null
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

/** A part of a drive a later import can fill in (owner, 2026-11-14, Q6). */
export type FilledPart = 'profile' | 'session log' | 'fault-code scan'

export interface ImportOutcome {
  stored: DriveRecord[]
  /**
   * Drives already stored ("already imported"), with the parts this import filled in (ticket 06): 'profile',
   * 'session log', 'fault-code scan'; empty when nothing was missing.
   */
  alreadyImported: { name: string; existingId: string; filled: FilledPart[] }[]
}

export const DB_NAME = 'sapphire'
export const DB_VERSION = 2
export const DRIVES = 'drives'
export const POINTS = 'points'
export const DIAGNOSTICS = 'diagnostics'
export const OLD_SESSIONS = 'sessions'
export const ALL = [DRIVES, POINTS, DIAGNOSTICS]
/** Keys this module adds to a drive record; stripped again when a session is rebuilt. */
const DERIVED = ['driveStart', 'summary', 'pointCount', 'hasDrive', 'hasSession', 'hasLaps', 'bestLapMs', 'hasWarnings', 'warnings',
  'contentHash', 'sessionProof'] as const

/**
 * A write whose promise nobody awaits: its failure already reaches the caller through the transaction's own result
 * (`tx.done`, or openDB for the upgrade), so a rejection here must not also surface as an unhandled one.
 */
export const settle = (request: Promise<unknown>): void => { request.catch(() => {}) }

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

/** The open database (upgraded). Exported for storageBackup.ts, the backup's half of this module (#384 ticket 02). */
export function getDb(): Promise<IDBPDatabase> {
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

export interface Split { drive: DriveRecord; points: { id: string; trip: TripData | null }; diagnostics: { id: string; diagnostics: DiagnosticData | null } }

class ConvertError extends Error {}

/** Exported for storageBackup.ts: a version-1 session in a backup converts as the upgrade converts it. */
export function split(raw: unknown): Split {
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
    bestLapMs: bestLap(trip),
    hasWarnings: warnings.faultCodes === 'found' || warnings.recordingGaps === 'found',
    warnings,
    contentHash: contentHash(s),
    ...(points.length === 0 ? { sessionProof: sessionProof(s, diagnostics) } : {}),
  }
}

/** The fastest lap with a valid time (finite, above 0); null when there is none. */
function bestLap(trip: TripData | null): number | null {
  const times = (trip?.laps ?? []).map((l) => l?.lapTimeMs).filter((t): t is number => typeof t === 'number' && Number.isFinite(t) && t > 0)
  return times.length > 0 ? Math.min(...times) : null
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
  return rebuildSession(drive, points?.trip ?? null, diagnostics?.diagnostics ?? null)
}

/** A session from its stored records: the drive record without the fields this module derives, with its points and log. */
export function rebuildSession(drive: object, trip: TripData | null, diagnostics: DiagnosticData | null): Session {
  const session = { ...drive } as Record<string, unknown>
  for (const k of DERIVED) delete session[k]
  return { ...(session as unknown as Session), trip, diagnostics }
}

/**
 * The same-drive rule, for one transaction that stores drives (import, and restore from a backup): a drive with points
 * is the same as a stored one with the same content hash; a session-only entry is the same only on proof (the scan's
 * finish time, or the whole event list). [add] notes a drive this transaction stores, so a second copy in the same
 * batch is found too.
 */
export function sameDriveFinder(tx: IDBPTransaction<unknown, string[], 'readwrite'>) {
  const seen = new Map<string, string>()
  let sessionsOnly: DriveRecord[] | null = null   // stored session-only entries, read once when needed
  return {
    async find(d: DriveRecord): Promise<string | undefined> {
      if (d.hasDrive) {
        return seen.get(d.contentHash) ?? (await tx.objectStore(DRIVES).index('byHash').getKey(d.contentHash)) as string | undefined
      }
      sessionsOnly ??= ((await tx.objectStore(DRIVES).getAll()) as DriveRecord[]).filter((x) => !x.hasDrive)
      return sessionsOnly.find((x) => sameSession(x.sessionProof, d.sessionProof))?.id
    },
    add(d: DriveRecord): void {
      if (d.hasDrive) seen.set(d.contentHash, d.id)
      else sessionsOnly?.push(d)
    },
  }
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
  const prepared = sessions.map((s) => ({ ...split(s), session: s }))   // a session that cannot be split fails before anything is written
  const db = await getDb()
  const tx = db.transaction(ALL, 'readwrite')
  const outcome: ImportOutcome = { stored: [], alreadyImported: [] }
  const same = sameDriveFinder(tx)
  try {
    for (const r of prepared) {
      const existing = await same.find(r.drive)
      if (existing !== undefined) {
        // the same drive: nothing new is stored; what the stored drive lacks is filled in, in this transaction (ticket 06)
        outcome.alreadyImported.push({ name: r.drive.name, existingId: existing, filled: await fillIn(tx, existing, r.session) })
        continue
      }
      same.add(r.drive)
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

/** Whether a drive holds a fault-code scan: its flag, or (an older record without the flag) any stored code. */
const hasScan = (s: Pick<Session, 'dtcScan'>, d: DiagnosticData | null) => s.dtcScan === true || (d?.dtcResults?.length ?? 0) > 0
const SCAN_FIELDS = ['dtcScan', 'dtcScanStatus', 'dtcScanFinishedAtMs'] as const

/**
 * Fill in a stored drive from the same drive imported again (owner, 2026-11-14, Q6; ticket 06). Each part on its own:
 * - the profile (its summary figures, laps, performance runs, thermal record, bookmarks, mode timeline): only when the
 *   stored drive has none; the stored points are kept;
 * - the session log: only when the stored drive has none;
 * - the fault-code scan: when the stored drive has none, or when its fault codes are "not checked" and this import's
 *   scan answers them (found or none). An answer is never replaced, and never turned back into "not checked".
 * The id, name, note, tags, import time, points and content hash stay; the derived fields (the warnings flag among
 * them) are recomputed as an import computes them. Writes only the records that change, in the caller's transaction;
 * nothing at all when nothing is missing. Restore never comes here: it merges nothing.
 */
async function fillIn(tx: IDBPTransaction<unknown, string[], 'readwrite'>, id: string, incoming: Session): Promise<FilledPart[]> {
  const stored = (await tx.objectStore(DRIVES).get(id)) as DriveRecord | undefined
  if (!stored) return []
  const storedTrip = ((await tx.objectStore(POINTS).get(id)) as { trip: TripData | null } | undefined)?.trip ?? null
  const storedDiag = ((await tx.objectStore(DIAGNOSTICS).get(id)) as { diagnostics: DiagnosticData | null } | undefined)?.diagnostics ?? null
  const inDiag = incoming.diagnostics ?? null
  const filled: FilledPart[] = []

  let trip = storedTrip
  if (storedTrip && incoming.trip && !storedTrip.profile && incoming.trip.profile) {
    trip = { ...incoming.trip, points: storedTrip.points }
    filled.push('profile')
  }
  const logFill = !hasSessionLog(storedDiag) && hasSessionLog(inDiag)
  const inCause = split(incoming).drive.warnings.faultCodes
  const scanFill = hasScan(incoming, inDiag) && stored.warnings?.faultCodes === 'not-checked'
    && (inCause !== 'not-checked' || !hasScan(stored, storedDiag))
  if (scanFill) filled.push('fault-code scan')
  if (logFill) filled.push('session log')
  if (filled.length === 0) return []

  // E1 (QA, ticket 06): a log fill takes the incoming diagnostics whole, except the stored fault codes. So a stored
  // record whose detail held no log but did hold nrcSuppression, didInventory or sessionDurationMs gets those from the
  // incoming file. Safe: they come from the same detail file as the log, so a record without a log has them only as
  // an older parser's empty values; for the same drive's fuller export they are the same data or more.
  const logFrom = logFill ? inDiag : storedDiag
  const codesFrom = scanFill ? inDiag : storedDiag
  const base = logFrom ?? codesFrom
  const diagnostics: DiagnosticData | null = base ? { ...base, dtcResults: codesFrom?.dtcResults ?? [] } : null
  const session = rebuildSession(stored, trip, diagnostics)
  if (scanFill) {
    const bag = session as unknown as Record<string, unknown>
    for (const k of SCAN_FIELDS) {
      if (incoming[k] !== undefined) bag[k] = incoming[k]
      else delete bag[k]
    }
  }
  const next = split(session)
  settle(tx.objectStore(DRIVES).put(next.drive))
  if (logFill || scanFill) settle(tx.objectStore(DIAGNOSTICS).put(next.diagnostics))
  if (filled.includes('profile')) settle(tx.objectStore(POINTS).put(next.points))
  return filled
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

/**
 * Fill in the best lap of a drive stored before ticket 04 (#384), from the session that was just opened (its points are
 * read then anyway). One write of the small record, in one transaction, and only when the stored record still lacks the
 * field: the record is rebuilt as an import stores it today (same fields, values and key order), from the stored record
 * itself, so a rename or tag edit made meanwhile is kept. Returns the new record, or null when nothing was written.
 * A failure writes nothing: the record stays as it was (the caller only logs it).
 */
export async function fillBestLap(session: Session): Promise<DriveRecord | null> {
  const db = await getDb()
  const tx = db.transaction(DRIVES, 'readwrite')
  const stored = (await tx.store.get(session.id)) as DriveRecord | undefined
  if (!stored || 'bestLapMs' in stored || !stored.hasLaps) { await tx.done; return null }
  const next = split(rebuildSession(stored, session.trip, session.diagnostics)).drive
  try {
    await tx.store.put(next)
  } catch (err) {
    try { tx.abort() } catch { /* already ended */ }
    await tx.done.catch(() => {})
    throw err
  }
  await tx.done
  return next
}

/** Rename: the small record only (the content hash is not recomputed). */
export function renameDrive(id: string, name: string): Promise<DriveRecord | null> {
  return updateDrive(id, (d) => ({ ...d, name }))
}

/** Write the owner's note (#384 ticket 05): the small record only. An empty note removes the field. */
export function setDriveNote(id: string, note: string): Promise<DriveRecord | null> {
  return updateDrive(id, (d) => {
    const { note: _old, ...rest } = d
    void _old
    const text = note.trim()
    return text ? { ...rest, note: text } : rest
  })
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
