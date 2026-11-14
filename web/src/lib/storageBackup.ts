// The backup's half of the storage module (#384 ticket 02): reading everything for a backup, and writing a restore.
// Kept apart from storage.ts so the first visit does not load it (300 kB budget); it uses storage.ts's own opening,
// upgrade and store names, and no other code touches IndexedDB.

import { openDB, type IDBPDatabase, type IDBPTransaction } from 'idb'
import { getDb, settle, sameDriveFinder, DB_NAME, DB_VERSION, DRIVES, POINTS, DIAGNOSTICS, OLD_SESSIONS, ALL, type DriveRecord } from './storage'
import type { DiagnosticData, TripData } from '../types/session'

/** Everything stored, as it is stored. A database whose upgrade failed is still version 1: then only `sessions`. */
export interface StoredEverything {
  source: 'version-1' | 'version-2'
  drives: DriveRecord[]
  points: { id: string; trip: TripData | null }[]
  diagnostics: { id: string; diagnostics: DiagnosticData | null }[]
  /** Version 1's whole sessions (after a failed upgrade). */
  sessions: unknown[]
}

/** Thrown when the stored data was written by a newer Sapphire than this one. */
export class NewerStorageError extends Error {
  constructor() { super('Your drives were stored by a newer Sapphire: reload this tab to update Sapphire, then try again.') }
}

/**
 * Read every record at once (one read-only transaction: a snapshot) without upgrading anything. The database is opened
 * at whatever version it has, so this works after a failed upgrade too (the backup of the old data). A browser with no
 * Sapphire database gets nothing back, and none is created.
 */
export async function readEverything(): Promise<StoredEverything> {
  const empty: StoredEverything = { source: 'version-2', drives: [], points: [], diagnostics: [], sessions: [] }
  let db: IDBPDatabase
  try {
    db = await openDB(DB_NAME, undefined, {
      upgrade(_db, _old, _new, tx) { settle(tx.done); tx.abort() },   // runs only when there is no database: create none
      blocking() { db.close() },                       // a newer tab asks for it: let go (this read then fails)
    })
  } catch (err) {
    if ((err as { name?: unknown } | null)?.name === 'AbortError') return empty
    throw err
  }
  try {
    if (db.version > DB_VERSION) throw new NewerStorageError()
    const stores = [DRIVES, POINTS, DIAGNOSTICS, OLD_SESSIONS].filter((s) => db.objectStoreNames.contains(s))
    if (stores.length === 0) return empty
    const tx = db.transaction(stores, 'readonly')
    const all = (s: string) => (stores.includes(s) ? tx.objectStore(s).getAll() : Promise.resolve([]))
    const [drives, points, diagnostics, sessions] = await Promise.all([all(DRIVES), all(POINTS), all(DIAGNOSTICS), all(OLD_SESSIONS)])
    await tx.done
    return { source: db.version < DB_VERSION ? 'version-1' : 'version-2', drives, points, diagnostics, sessions }
  } finally {
    db.close()
  }
}

/** A drive's records from a backup, as they were stored (a part the backup did not hold is not written). */
export interface RestoreRecord { drive: DriveRecord; points: { id: string } | null; diagnostics: { id: string } | null }

/**
 * Restore records from a backup in one transaction. A drive already here is skipped and counted, never overwritten or
 * merged (owner, 2026-11-14): an id present in any of the three stores (an orphan record left by an earlier fault
 * counts), or the same drive by the import's rule under another id. Nothing is deleted.
 */
export async function restoreRecords(records: RestoreRecord[]): Promise<{ added: number; skipped: number }> {
  const db = await getDb()
  const tx = db.transaction(ALL, 'readwrite')
  const same = sameDriveFinder(tx)
  const out = { added: 0, skipped: 0 }
  try {
    for (const r of records) {
      if (await idHere(tx, r.drive.id) || (await same.find(r.drive)) !== undefined) { out.skipped++; continue }
      same.add(r.drive)
      settle(tx.objectStore(DRIVES).put(r.drive))
      if (r.points) settle(tx.objectStore(POINTS).put(r.points))
      if (r.diagnostics) settle(tx.objectStore(DIAGNOSTICS).put(r.diagnostics))
      out.added++
    }
  } catch (err) {
    try { tx.abort() } catch { /* already ended */ }
    await tx.done.catch(() => {})
    throw err
  }
  await tx.done
  return out
}

/** An id present in any of the three stores (an orphan record left by an earlier fault counts). */
async function idHere(tx: IDBPTransaction<unknown, string[], 'readwrite'>, id: string): Promise<boolean> {
  for (const store of ALL) if ((await tx.objectStore(store).getKey(id)) !== undefined) return true
  return false
}
