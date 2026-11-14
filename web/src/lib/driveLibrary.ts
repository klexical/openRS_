// The Drive library (spec SPEC-drive-library.md; plan PLAN-drive-library.md): the drives Sapphire keeps in this browser,
// and its session-only entries, the Current drive and the drives picked for Compare. It is the only code that changes
// them. Every ask writes the database first (lib/storage.ts, unchanged), then mirrors what it stored into the store
// (store/driveLibrarySlice.ts). Pages ask; they read the store's fields as before.
//
// Each ask states its failure contract: "rejects" means nothing was mirrored and the error reaches the caller, as today.
import { useStore } from '../store'
import { clearAllDrives, deleteDrives, fillBestLap, listDrives, loadDrive, renameDrive, setDriveNote, setDriveTags, storeImported,
  type DriveRecord, type ImportOutcome } from './storage'
import type { Session } from '../types/session'
import type { FileResult, ImportProgress } from './importFiles'
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

/** The Import's reading pipeline (lib/importFiles.ts importFiles): reads and parses each file, and stores it with [store]. */
export type ReadingPipeline = (files: File[], onProgress: (p: ImportProgress) => void, store: StoringStep) => Promise<FileResult[]>

/** The Import's reading code could not load (a tab left open across a deploy): nothing was read or stored. */
export class ImportCodeUnavailable extends Error {
  constructor(cause: unknown) { super('The import code could not load.', { cause }) }
}

/**
 * One Import, all of it. The library loads the Import's reading pipeline (lib/importFiles.ts, on first use: the
 * first-visit budget) and runs it on [files], handing it the library's storing step for each file; the step refuses
 * once this Import has ended (I3). [read] replaces the pipeline in tests only. After it ends the library mirrors once (K4):
 * the drives stored are added to the list (no re-read, K2); when a stored drive was filled in, the list is read again,
 * and the Current drive too if it was filled in and is still current (K3). Compare's copies are not read again: a
 * known defect, ledger L110, kept. Resolves with the pipeline's results. Rejects with [ImportCodeUnavailable] when the
 * reading code cannot load, or when the pipeline itself rejects (nothing mirrored); a re-read that fails is logged.
 */
export async function importDrives(files: File[], onProgress: (p: ImportProgress) => void, read?: ReadingPipeline): Promise<FileResult[]> {
  let pipeline = read
  if (!pipeline) {
    try { pipeline = (await import('./importFiles')).importFiles } catch (err) { throw new ImportCodeUnavailable(err) }
  }
  let open = true
  const step: StoringStep = (sessions) => (open ? storeImported(sessions) : Promise.reject(new Error('This import has ended: nothing was stored.')))
  let results: FileResult[]
  try { results = await pipeline(files, onProgress, step) } finally { open = false }

  const stored = results.flatMap((r) => r.stored)
  const filled = results.flatMap((r) => r.already.filter((a) => a.filled.length > 0).map((a) => a.existingId))
  if (stored.length > 0) store().addDrives(stored)
  if (filled.length > 0) {
    // a stored drive was filled in: its small record, and the Current drive if it is that one, are read again
    try {
      store().setDrives(await listDrives())
      const current = store().activeSessionId
      if (current && filled.includes(current)) {
        const token = store().libraryReads.current          // the one guard: applied only if no newer Current drive was asked for
        const fresh = await loadDrive(current)
        if (fresh && newest('current', token)) store().setActiveSession(fresh)
      }
    } catch (err) { console.warn('[Sapphire] Could not read the filled-in drives again:', err) }
  }
  return results
}

// ── The Current drive and Compare ──

/**
 * The Current drive's state, derived from the store's fields (the whole-app tests set those directly):
 * none, being read, shown, read failed. "Not stored here" is not a resting state: it is the `onNotStored` event, after
 * which the app shell goes to the list and the state is none.
 */
export type CurrentDriveState = 'none' | 'being read' | 'shown' | 'read failed'
export function currentDriveState(s: { activeSessionId: string | null; activeSession: Session | null; openFailedId: string | null }): CurrentDriveState {
  if (s.activeSessionId === null) return 'none'
  if (s.openFailedId === s.activeSessionId) return 'read failed'
  return s.activeSession?.id === s.activeSessionId ? 'shown' : 'being read'
}

type Channel = keyof ReturnType<typeof store>['libraryReads']
/** The one guard against a late answer: a new token per read; an answer counts only while its token is the newest. */
function nextToken(channel: Channel): number {
  const reads = store().libraryReads
  const token = reads[channel] + 1
  useStore.setState({ libraryReads: { ...reads, [channel]: token } })
  return token
}
const newest = (channel: Channel, token: number) => store().libraryReads[channel] === token

/** Read the Current drive the store names (as App's effect did): the old one goes first; one guard; the best-lap fill. */
function readCurrent(id: string | null): void {
  const token = nextToken('current')
  store().setActiveSession(null)
  store().setOpenFailedId(null)
  if (!id) return
  loadDrive(id)
    .then((s) => {
      if (!newest('current', token)) return
      if (s === null) {
        // not stored here (deleted, or another browser's link): the app shell goes to the list with its message; with
        // no app attached, the Current drive is none, so nothing waits on a read that will not come (I1)
        const notStored = store().libraryAttachment.onNotStored
        if (notStored) notStored()
        else store().setActiveSessionId(null)
        return
      }
      store().setActiveSession(s)
      // a drive with laps stored before ticket 04 gets its best lap now that its points are read (only then: no write
      // on any other opening, and not while the list is not loaded, K9); a failure changes nothing
      const rec = store().drives.find((d) => d.id === s.id)
      if (rec?.hasLaps && !('bestLapMs' in rec)) {
        fillBestLap(s)
          .then((filled) => { if (filled) store().updateDrive(filled) })
          .catch((err) => console.warn('[Sapphire] Could not note the best lap:', err))
      }
    })
    .catch((err) => {
      // a failed read is not "not stored" (ticket 03): the drive's page says it could not be opened (fix round)
      console.error('[Sapphire] Failed to open the drive:', err)
      if (newest('current', token)) store().setOpenFailedId(id)
    })
}

/** Read the compared drives (as App's effect did): the Current drive is reused from memory; one guard (K6, K10). */
function readCompared(ids: string[]): void {
  const token = nextToken('compare')
  if (ids.length === 0) { store().setCompareSessions([]); return }
  const current = store().activeSession
  Promise.all(ids.map((id) => (current?.id === id ? current : loadDrive(id))))
    .then((loaded) => { if (newest('compare', token)) store().setCompareSessions(loaded.filter((s) => s !== null)) })
    // kept, a known defect (L111): a failed read keeps the old copies and says nothing
    .catch((err) => console.error('[Sapphire] Failed to open the compared drives:', err))
}

const attached = () => store().libraryAttachment.count > 0

/**
 * Start the reads that follow the store (K10): the Current drive whenever its id changes, the compared drives whenever
 * a new id array is set (equal ids included), from whatever sets them. [onNotStored] is called once when the Current
 * drive's read finds no such drive. Returns a detach function. Attaching again while attached adds no second
 * subscription (I2): it counts, and the reads stop when every attach has detached.
 */
export function attach(handlers: { onNotStored?: () => void } = {}): () => void {
  const a = store().libraryAttachment
  if (a.count > 0) {
    useStore.setState({ libraryAttachment: { ...a, count: a.count + 1, onNotStored: handlers.onNotStored ?? a.onNotStored } })
    return detachOnce()
  }
  const stop = useStore.subscribe((s, prev) => {
    if (s.activeSessionId !== prev.activeSessionId) readCurrent(s.activeSessionId)
    if (s.compareSessionIds !== prev.compareSessionIds) readCompared(s.compareSessionIds)
  })
  useStore.setState({ libraryAttachment: { count: 1, stop, onNotStored: handlers.onNotStored ?? null } })
  // as App's effects did when they first ran
  readCurrent(store().activeSessionId)
  readCompared(store().compareSessionIds)
  return detachOnce()
}
function detachOnce(): () => void {
  let done = false
  return () => {
    if (done) return
    done = true
    const a = store().libraryAttachment
    if (a.count > 1) { useStore.setState({ libraryAttachment: { ...a, count: a.count - 1 } }); return }
    a.stop?.()
    nextToken('current'); nextToken('compare')                 // an answer still on its way is no longer wanted
    useStore.setState({ libraryAttachment: { count: 0, stop: null, onNotStored: null } })
  }
}

/**
 * Make [id] the Current drive (what the address names), or none. Attached, the subscription reads it; not attached, this
 * ask starts the read itself, so the state never stays "being read" with nothing on its way (I1).
 */
export function showDrive(id: string | null): void {
  const changed = store().activeSessionId !== id
  store().setActiveSessionId(id)
  if (changed && !attached()) readCurrent(id)
}

/** Compare exactly these drives, in this order (the first is the Base drive), at most four (K6). */
export function compareDrives(ids: string[]): void {
  store().setCompare(ids)
  if (!attached()) readCompared(store().compareSessionIds)
}
/** Add a drive to Compare, or take it out; a fifth is ignored (K6). */
export function toggleCompared(id: string): void {
  store().toggleCompareSession(id)
  if (!attached()) readCompared(store().compareSessionIds)
}
/** Compare no drives: the copies go too (K6). */
export function clearCompared(): void {
  store().clearCompare()
  if (!attached()) readCompared(store().compareSessionIds)
}
