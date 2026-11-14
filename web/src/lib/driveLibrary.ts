// The Drive library (spec SPEC-drive-library.md; plan PLAN-drive-library.md): the drives Sapphire keeps in this browser,
// and its session-only entries, the Current drive and the drives picked for Compare. It is the only code that changes
// them. Every ask writes the database first (lib/storage.ts, unchanged), then mirrors what it stored into the store
// (store/driveLibrarySlice.ts). Pages ask; they read the store's fields as before.
//
// Each ask states its failure contract: "rejects" means nothing was mirrored and the error reaches the caller, as today.
import { useStore } from '../store'
import { clearAllDrives, deleteDrives, listDrives, loadDrive, renameDrive, setDriveNote, setDriveTags, storeImported,
  type DriveRecord, type ImportOutcome } from './storage'
import type { Session } from '../types/session'
import type { FileResult } from './importFiles'
import type { RestoreOutcome } from './backup'

const store = () => useStore.getState()

// ── The list ──

/** Read the list once, at start. Never rejects: a failure is logged and the list stays "not loaded" (K7). */
export async function loadList(): Promise<void> {
  try {
    const drives = await listDrives()
    store().setDrives(drives)
    store().setDrivesLoaded(true)
  } catch (err) {
    console.error('[Sapphire] Failed to load drives from IndexedDB:', err)
  }
}

/**
 * Rename a drive. Resolves with its new record, mirrored into the list, the Current drive and Compare's copies (the
 * owner's fields only there, K5); resolves null when it is no longer stored (nothing mirrored). Rejects when the write
 * fails (nothing mirrored).
 */
export function rename(id: string, name: string): Promise<DriveRecord | null> {
  return mirrored(renameDrive(id, name))
}
/** The owner's note on a drive (an empty note removes it). As [rename]. */
export function setNote(id: string, note: string): Promise<DriveRecord | null> {
  return mirrored(setDriveNote(id, note))
}
/** A drive's tags. As [rename]. */
export function setTags(id: string, tags: string[]): Promise<DriveRecord | null> {
  return mirrored(setDriveTags(id, tags))
}
async function mirrored(write: Promise<DriveRecord | null>): Promise<DriveRecord | null> {
  const rec = await write
  if (rec) store().updateDrive(rec)
  return rec
}

/**
 * Delete drives, all or none (one transaction). Mirrored after the write: they leave the list, stop being the Current
 * drive, and leave Compare's ids and copies (K1). Rejects when nothing was deleted (nothing mirrored).
 */
export async function remove(ids: string[]): Promise<void> {
  await deleteDrives(ids)
  store().removeDrives(ids)
}

/**
 * Empty the library (one transaction), then the list. Kept (K1): the Current drive and Compare's ids and copies are left
 * as they are, unlike [remove]. Rejects when nothing was cleared (nothing mirrored).
 */
export async function clearAll(): Promise<void> {
  await clearAllDrives()
  store().setDrives([])
}

/**
 * Restore a backup file, then read the whole list again (K2); the Current drive and Compare are not touched. The backup
 * code loads on first use (the first-visit budget). Rejects when the restore fails (nothing mirrored), or when the
 * list cannot be read after it.
 */
export async function restore(file: Blob): Promise<RestoreOutcome> {
  const { restoreBackup } = await import('./backup')
  const outcome = await restoreBackup(file)
  store().setDrives(await listDrives())
  return outcome
}

// ── Import ──

/** The storing step of one file of an Import: all of that file's drives, or none (one transaction). */
export type StoringStep = (sessions: Session[]) => Promise<ImportOutcome>

/**
 * One Import, all of it. [read] is the Import's own reading pipeline (lib/importFiles.ts), handed the library's storing
 * step for each file; the step refuses once this Import has ended. After [read] ends the library mirrors once (K4):
 * the drives stored are added to the list (no re-read, K2); when a stored drive was filled in, the list is read again,
 * and the Current drive too if it was filled in and is still current (K3). Compare's copies are not read again: a
 * known defect, ledger L110, kept. Resolves with [read]'s results; rejects only if [read] does (a re-read that fails is
 * logged).
 */
export async function importDrives(read: (store: StoringStep) => Promise<FileResult[]>): Promise<FileResult[]> {
  let open = true
  const step: StoringStep = (sessions) => (open ? storeImported(sessions) : Promise.reject(new Error('This import has ended: nothing was stored.')))
  let results: FileResult[]
  try { results = await read(step) } finally { open = false }

  const stored = results.flatMap((r) => r.stored)
  const filled = results.flatMap((r) => r.already.filter((a) => a.filled.length > 0).map((a) => a.existingId))
  if (stored.length > 0) store().addDrives(stored)
  if (filled.length > 0) {
    // a stored drive was filled in: its small record, and the Current drive if it is that one, are read again
    try {
      store().setDrives(await listDrives())
      const current = store().activeSessionId
      if (current && filled.includes(current)) {
        const fresh = await loadDrive(current)
        if (fresh && store().activeSessionId === current) store().setActiveSession(fresh)
      }
    } catch (err) { console.warn('[Sapphire] Could not read the filled-in drives again:', err) }
  }
  return results
}
