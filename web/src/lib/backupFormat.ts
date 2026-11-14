// Sapphire's backup file (#384 ticket 02): the format, written and read. No IndexedDB here: the caller hands in what
// storage.ts read, and gets back records for it to store. Used in the backup worker and on the page.
//
// The file is a ZIP:
//   manifest.json      {"format":"sapphire-backup","version":1,"source":"version-2"|"version-1","drives":N}
//   drives/1.json …    one per drive: {"drive","points","diagnostics"} as stored (version-2), or {"session"} (version-1,
//                      written after a failed upgrade)
// A file holding one drive is a valid backup (the one-drive share file of slice 7 uses this format).
// JSON loses NaN, ±Infinity, −0 and undefined; each is written as {"$sapphire":"NaN"} and so on, and a stored object that
// has its own "$sapphire" key is wrapped as {"$sapphire":"object","value":{…}}, so text never reads as a marker.

import JSZip from 'jszip'
import type { StoredEverything } from './storageBackup'

/** The backup file's format version; a file from a newer one is refused. */
export const BACKUP_FORMAT_VERSION = 1
const FORMAT = 'sapphire-backup'
const TAG = '$sapphire'
const SPECIAL: Record<string, unknown> = { NaN: NaN, Infinity: Infinity, '-Infinity': -Infinity, '-0': -0, undefined: undefined }

/** A backup that cannot be written or restored, in plain words. */
export class BackupError extends Error {
  /** Read by the page, which loads this module only on demand (a class name does not survive minifying). */
  readonly plainWords = true
}

const NOTHING_RESTORED = 'Nothing was restored.'
export const notABackup = () => new BackupError(`This file is not a Sapphire backup. ${NOTHING_RESTORED}`)
const damaged = (what: string) => new BackupError(`This backup is damaged (${what}). ${NOTHING_RESTORED}`)

// ── Values ──

const own = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k)
const setOwn = (o: object, k: string, value: unknown) =>
  Object.defineProperty(o, k, { value, enumerable: true, writable: true, configurable: true })   // "__proto__" stays a key

function isPlainObject(v: object): v is Record<string, unknown> {
  if (Object.prototype.toString.call(v) !== '[object Object]') return false
  const p = Object.getPrototypeOf(v)
  return p === null || Object.getPrototypeOf(p) === null          // any realm's Object.prototype (records from a worker)
}

/** A stored value as JSON-safe data. Throws on anything the format cannot hold, rather than lose it. */
export function encodeValue(v: unknown): unknown {
  switch (typeof v) {
    case 'string': case 'boolean': return v
    case 'undefined': return { [TAG]: 'undefined' }
    case 'number':
      if (Number.isNaN(v)) return { [TAG]: 'NaN' }
      if (v === Infinity) return { [TAG]: 'Infinity' }
      if (v === -Infinity) return { [TAG]: '-Infinity' }
      if (Object.is(v, -0)) return { [TAG]: '-0' }
      return v
    case 'object': {
      if (v === null) return null
      if (Array.isArray(v)) {
        const out: unknown[] = new Array(v.length)
        for (let i = 0; i < v.length; i++) out[i] = encodeValue(v[i])
        return out
      }
      if (!isPlainObject(v)) throw new BackupError(`A stored drive holds a value Sapphire cannot write to a backup (${Object.prototype.toString.call(v)}). Nothing was written.`)
      const out = {}
      for (const k of Object.keys(v)) setOwn(out, k, encodeValue(v[k]))
      return own(v, TAG) ? { [TAG]: 'object', value: out } : out
    }
    default:
      throw new BackupError(`A stored drive holds a value Sapphire cannot write to a backup (${typeof v}). Nothing was written.`)
  }
}

/** The reverse of encodeValue, on freshly parsed JSON (changed in place, so key order is kept). */
export function decodeValue(v: unknown): unknown {
  if (Array.isArray(v)) {
    for (let i = 0; i < v.length; i++) v[i] = decodeValue(v[i])
    return v
  }
  if (v !== null && typeof v === 'object') {
    const o = v as Record<string, unknown>
    if (own(o, TAG)) {
      const tag = o[TAG]
      if (tag === 'object') {
        const inner = o.value
        if (inner === null || typeof inner !== 'object' || Array.isArray(inner)) throw damaged('a marked object has no value')
        for (const k of Object.keys(inner)) setOwn(inner, k, decodeValue((inner as Record<string, unknown>)[k]))
        return inner
      }
      if (typeof tag === 'string' && own(SPECIAL, tag)) return SPECIAL[tag]
      throw damaged('an unknown marker')
    }
    for (const k of Object.keys(o)) setOwn(o, k, decodeValue(o[k]))
  }
  return v
}

// ── The file ──

export interface BackupFile {
  blob: Blob
  name: string
  drives: number
}

/** The download's name, by the local date: sapphire-backup-2026-11-14.zip */
export function backupName(now = new Date()): string {
  const two = (n: number) => String(n).padStart(2, '0')
  return `sapphire-backup-${now.getFullYear()}-${two(now.getMonth() + 1)}-${two(now.getDate())}.zip`
}

/**
 * Write what storage.ts read as a backup file. [pause] runs between drives (on the page it yields, so the page stays
 * usable); [onProgress] gets drives done of drives in the file.
 */
export async function buildBackup(data: StoredEverything, onProgress?: (done: number, total: number) => void,
  pause: () => Promise<void> = async () => {}): Promise<BackupFile> {
  const zip = new JSZip()
  const byId = <T extends { id: string }>(rows: T[]) => new Map(rows.map((r) => [r.id, r]))
  const entries: (() => unknown)[] = data.source === 'version-1'
    ? data.sessions.map((session) => () => ({ session }))
    : (() => {
        const points = byId(data.points), diagnostics = byId(data.diagnostics)
        return data.drives.map((drive) => () => ({ drive, points: points.get(drive.id) ?? null, diagnostics: diagnostics.get(drive.id) ?? null }))
      })()
  const total = entries.length
  onProgress?.(0, total)
  for (let i = 0; i < total; i++) {
    zip.file(`drives/${i + 1}.json`, JSON.stringify(encodeValue(entries[i]())))
    onProgress?.(i + 1, total)
    await pause()
  }
  zip.file('manifest.json', JSON.stringify({ format: FORMAT, version: BACKUP_FORMAT_VERSION, source: data.source, drives: total }))
  const blob = await zip.generateAsync({ type: 'blob', compression: 'DEFLATE', compressionOptions: { level: 6 }, mimeType: 'application/zip' })
  return { blob, name: backupName(), drives: total }
}

/** One drive read back from a backup: as stored (version-2), or a whole version-1 session to convert. */
export type BackupEntry =
  | { kind: 'stored'; drive: Record<string, unknown> & { id: string }; points: { id: string; trip: unknown } | null; diagnostics: { id: string; diagnostics: unknown } | null }
  | { kind: 'session'; session: unknown }

/** Read a backup file's entries, checked; throws a BackupError in plain words for anything it cannot restore. */
export async function readBackup(file: Blob, pause: () => Promise<void> = async () => {}): Promise<BackupEntry[]> {
  let zip: JSZip
  try { zip = await JSZip.loadAsync(file) } catch { throw notABackup() }
  const manifestFile = zip.file('manifest.json')
  if (!manifestFile) throw notABackup()
  let manifest: Record<string, unknown>
  try { manifest = JSON.parse(await manifestFile.async('string')) } catch { throw notABackup() }
  if (manifest === null || typeof manifest !== 'object' || manifest.format !== FORMAT
    || !Number.isInteger(manifest.version) || (manifest.version as number) < 1) throw notABackup()
  if ((manifest.version as number) > BACKUP_FORMAT_VERSION) {
    throw new BackupError(`This backup was made by a newer Sapphire (backup format ${manifest.version}); this Sapphire reads format `
      + `${BACKUP_FORMAT_VERSION}. Reload Sapphire to update it, then restore again. ${NOTHING_RESTORED}`)
  }
  const source = manifest.source
  if (source !== 'version-1' && source !== 'version-2') throw damaged('unknown source')
  const total = manifest.drives
  if (!Number.isInteger(total) || (total as number) < 0) throw damaged('no drive count')
  const named = new Set(Object.keys(zip.files).filter((n) => n.startsWith('drives/') && !zip.files[n].dir))
  if (named.size !== total) throw damaged(`${named.size} drive files for ${total} drives`)
  const out: BackupEntry[] = []
  for (let i = 1; i <= (total as number); i++) {
    const f = zip.file(`drives/${i}.json`)
    if (!f) throw damaged(`drive ${i} is missing`)
    let entry: unknown
    try { entry = decodeValue(JSON.parse(await f.async('string'))) } catch (e) {
      throw e instanceof BackupError ? e : damaged(`drive ${i} cannot be read`)
    }
    out.push(source === 'version-1' ? sessionEntry(entry, i) : storedEntry(entry, i))
    await pause()
  }
  return out
}

const isObject = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)

function sessionEntry(e: unknown, i: number): BackupEntry {
  if (!isObject(e) || !own(e, 'session')) throw damaged(`drive ${i} has no session`)
  return { kind: 'session', session: e.session }
}

/** A stored drive's three records, checked so that a damaged file cannot store a row the list or the drive view cannot read. */
function storedEntry(e: unknown, i: number): BackupEntry {
  if (!isObject(e)) throw damaged(`drive ${i} is not a record`)
  const { drive, points, diagnostics } = e
  // Only what every stored drive has (the upgrade stores a version-1 drive with no name, tags or import time): its id and
  // the fields storage.ts derives for each drive, the content hash among them (the index that finds "already imported")
  if (!isObject(drive) || typeof drive.id !== 'string' || typeof drive.hasDrive !== 'boolean' || typeof drive.contentHash !== 'string') {
    throw damaged(`drive ${i} is incomplete`)
  }
  if (points !== null && !(isObject(points) && points.id === drive.id
    && (points.trip === null || (isObject(points.trip) && Array.isArray(points.trip.points))))) {
    throw damaged(`drive ${i}'s points do not match it`)
  }
  if (diagnostics !== null && !(isObject(diagnostics) && diagnostics.id === drive.id)) {
    throw damaged(`drive ${i}'s diagnostics do not match it`)
  }
  return { kind: 'stored', drive: drive as Record<string, unknown> & { id: string },
    points: points as { id: string; trip: unknown } | null, diagnostics: diagnostics as { id: string; diagnostics: unknown } | null }
}
