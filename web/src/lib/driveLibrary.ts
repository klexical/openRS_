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
import { askToKeepOnce } from './keepData'

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
 * Not attached (no app shell: tests, later pages), removing the Current drive does not move the read token, so its read
 * already on its way can still land (QA's FINAL, 6); the app always attaches, where the id change moves it.
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
  // a restore that stored drives asks the browser to keep the data too, once ever, as the first import does (owner, Q2)
  if (outcome.added > 0) void askToKeepOnce()
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
 * and the Current drive too if it was filled in and is still current (K3), then the compared drives if one of them was
 * filled in (#406), all before it resolves. Resolves with the pipeline's results. Rejects with [ImportCodeUnavailable]
 * when the reading code cannot load, or when the pipeline itself rejects (nothing mirrored); a re-read that fails is
 * logged.
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
    // a stored drive was filled in: its small record, and the Current drive if it is that one, are read again. The
    // Current drive's copy in memory is stale until its re-read lands: if that re-read fails (or the list's before it),
    // Compare reads the drive from storage until it is read again (owner, Q14)
    const before = store().activeSessionId
    if (before && filled.includes(before)) useStore.setState({ staleCurrentId: before })
    try {
      store().setDrives(await listDrives())
      const current = store().activeSessionId
      if (current && filled.includes(current)) {
        const token = store().libraryReads.current          // the one guard: applied only if no newer Current drive was asked for
        const fresh = await loadDrive(current)
        if (fresh && newest('current', token)) store().setActiveSession(fresh)
        if (fresh && store().activeSession === fresh && store().staleCurrentId === current) useStore.setState({ staleCurrentId: null })
      }
    } catch (err) { console.warn('[Sapphire] Could not read the filled-in drives again:', err) }
    // a compared drive was filled in: Compare's copies are read again, the filled-in ones from storage (the Current
    // drive in memory may be the old copy if its re-read failed); run even when the list's re-read failed (#406)
    const compared = store().compareSessionIds
    if (filled.some((id) => compared.includes(id))) await readCompared(compared, filled)
  }
  return results
}

// ── The Current drive and Compare ──

// The Current drive's state lives beside the fields it is derived from (store/driveLibrarySlice.ts), so the store's one
// reading of it (`useCurrentDrive`) needs no import from here (#416 ticket 1).
export { currentDriveState, type CurrentDriveState } from '../store/driveLibrarySlice'

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
  if (store().staleCurrentId !== null) useStore.setState({ staleCurrentId: null })   // the drive is read again (Q14)
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

/**
 * Read the compared drives (as App's effect did): the Current drive is reused from memory, unless it is in [fresh] or
 * its copy is stale (`staleCurrentId`, owner Q14); one guard (K6, K10). A drive whose read fails is named in
 * `compareFailedIds` for Compare to say so (#407), and the copies become the drives this read did open, so no copy from
 * an earlier read stays beside it (owner Q13) and Compare compares the drives that did open (#416 ticket 3);
 * a new read clears the names. Never rejects.
 */
function readCompared(ids: string[], fresh: string[] = []): Promise<void> {
  const token = nextToken('compare')
  if (store().compareFailedIds.length > 0) store().setCompareFailedIds([])
  if (ids.length === 0) { store().setCompareSessions([]); return Promise.resolve() }
  const current = store().activeSession
  const reuse = (id: string) => current?.id === id && !fresh.includes(id) && store().staleCurrentId !== id
  return Promise.allSettled(ids.map((id) => (reuse(id) ? current! : loadDrive(id))))
    .then((answers) => {
      if (!newest('compare', token)) return
      const failed = ids.filter((_, i) => answers[i].status === 'rejected')
      for (const a of answers) if (a.status === 'rejected') console.error('[Sapphire] Failed to open the compared drives:', a.reason)
      // only what this read opened: a drive not stored is left out (U4), one that failed is named (#407, #416 ticket 3)
      store().setCompareSessions(answers.flatMap((a) => (a.status === 'fulfilled' && a.value !== null ? [a.value] : [])))
      if (failed.length > 0) store().setCompareFailedIds(failed)
    })
}

const attached = () => store().libraryAttachment.count > 0

/**
 * Start the reads that follow the store (K10): the Current drive whenever its id changes, the compared drives whenever
 * a new id array is set (equal ids included), from whatever sets them. [onNotStored] is called once when the Current
 * drive's read finds no such drive. Returns a detach function. Attaching again while attached adds no second
 * subscription (I2): it counts, and the reads stop when every attach has detached.
 * A second attach's [onNotStored] replaces the first, and its detach does not put the first back (QA's FINAL, 7).
 */
export function attach(handlers: { onNotStored?: () => void } = {}): () => void {
  const a = store().libraryAttachment
  if (a.count > 0) {
    useStore.setState({ libraryAttachment: { ...a, count: a.count + 1, onNotStored: handlers.onNotStored ?? a.onNotStored } })
    return detachOnce()
  }
  const stop = useStore.subscribe((s, prev) => {
    if (s.activeSessionId !== prev.activeSessionId) readCurrent(s.activeSessionId)
    if (s.compareSessionIds !== prev.compareSessionIds) void readCompared(s.compareSessionIds)
  })
  useStore.setState({ libraryAttachment: { count: 1, stop, onNotStored: handlers.onNotStored ?? null } })
  // as App's effects did when they first ran
  readCurrent(store().activeSessionId)
  void readCompared(store().compareSessionIds)
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
    useStore.setState({ libraryAttachment: { count: 0, stop: null, onNotStored: null }, compareFailedIds: [] })
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
  if (!attached()) void readCompared(store().compareSessionIds)
}
/** Add a drive to Compare, or take it out; a fifth is ignored (K6). */
export function toggleCompared(id: string): void {
  store().toggleCompareSession(id)
  if (!attached()) void readCompared(store().compareSessionIds)
}
/** Compare no drives: the copies go too (K6). */
export function clearCompared(): void {
  store().clearCompare()
  if (!attached()) void readCompared(store().compareSessionIds)
}
