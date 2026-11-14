// Back up all drives, and restore (#384 ticket 02). Loaded on demand (Settings, the failed-upgrade message), not on the
// first visit. The file format is in backupFormat.ts; the IndexedDB reads and writes are in storageBackup.ts, which
// opens the database through storage.ts (and on its own, at any version, to read for a backup).

import { split, rebuildSession } from './storage'
import { readEverything, restoreRecords, NewerStorageError, type RestoreRecord } from './storageBackup'
import { buildBackup, readBackup, damaged, BackupError, BACKUP_FORMAT_VERSION, type BackupFile as File_ } from './backupFormat'
import type { WorkerReply } from './backupWorker'
import type { DiagnosticData, TripData } from '../types/session'

export { BackupError, BACKUP_FORMAT_VERSION }

export interface BackupFile extends File_ {
  /** Where the file was built: in a worker, or on the page in steps (where no worker could start). */
  builtIn: 'worker' | 'page'
}

export interface RestoreOutcome {
  /** Drives brought in. */
  added: number
  /** Drives already here (the same id, or the same drive by the import's rule): skipped, never overwritten. */
  skipped: number
  /** Names of version-1 drives in the file that could not be converted (after a failed upgrade). */
  notRestorable: string[]
}

/** Let the page draw and take input between steps. */
const yieldToPage = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

/** The worker could not be used (did not load, sent a message that cannot be read, or said nothing): build on the page. */
class NoWorker extends Error {}

export interface BackupOptions {
  /** Stops the backup: the worker is ended, the page build stops at its next drive, and no file is made. */
  signal?: AbortSignal
  /** How long the worker may stay silent before its first message (its "started", sent before it reads anything). */
  silenceMs?: number
}

const stopped = (signal: AbortSignal) => signal.reason ?? new DOMException('The backup was stopped.', 'AbortError')

function inWorker(onProgress: ((done: number, total: number) => void) | undefined, signal: AbortSignal | undefined,
  silenceMs: number): Promise<BackupFile> {
  return new Promise((resolve, reject) => {
    let worker: Worker
    try {
      worker = new Worker(new URL('./backupWorker.ts', import.meta.url), { type: 'module' })
    } catch {
      reject(new NoWorker()); return
    }
    const onAbort = () => { end(); reject(stopped(signal!)) }
    const end = () => { clearTimeout(watchdog); signal?.removeEventListener('abort', onAbort); worker.terminate() }
    const fallBack = () => { end(); reject(new NoWorker()) }
    let heard = false
    const watchdog = setTimeout(() => { if (!heard) fallBack() }, silenceMs)
    if (signal?.aborted) { onAbort(); return }
    signal?.addEventListener('abort', onAbort, { once: true })
    worker.onmessage = (e: MessageEvent<WorkerReply>) => {
      heard = true
      const m = e.data
      if (m.type === 'started') return
      if (m.type === 'progress') { onProgress?.(m.done, m.total); return }
      end()
      if (m.type === 'done') resolve({ blob: m.blob, name: m.name, drives: m.drives, builtIn: 'worker' })
      else reject(m.plain ? new BackupError(m.message) : new Error(`The backup could not be built (${m.message}).`))
    }
    worker.onmessageerror = fallBack
    // The worker could not start or load (a browser without module workers): build on the page instead
    worker.onerror = (e) => { e.preventDefault(); fallBack() }
    worker.postMessage('backup')
  })
}

/** Build the backup of everything stored (in a worker where one can start), as a file to download. */
export async function makeBackup(onProgress?: (done: number, total: number) => void,
  { signal, silenceMs = 5000 }: BackupOptions = {}): Promise<BackupFile> {
  if (typeof Worker !== 'undefined') {
    try {
      return await inWorker(onProgress, signal, silenceMs)
    } catch (err) {
      if (!(err instanceof NoWorker)) throw err
      console.warn('[Sapphire] The backup worker could not be used; building on the page.')
    }
  }
  try {
    signal?.throwIfAborted()
    const data = await readEverything()
    signal?.throwIfAborted()
    const file = await buildBackup(data, onProgress, async () => { await yieldToPage(); signal?.throwIfAborted() })
    return { ...file, builtIn: 'page' }
  } catch (err) {
    if (err instanceof BackupError) throw err
    if (err instanceof NewerStorageError) throw new BackupError(err.message)
    throw err
  }
}

/** Restore a backup file: adds what is missing, skips what is already here, never overwrites, merges or deletes. */
export async function restoreBackup(file: Blob): Promise<RestoreOutcome> {
  const entries = await readBackup(file, yieldToPage)
  const records: RestoreRecord[] = []
  const notRestorable: string[] = []
  for (const [i, e] of entries.entries()) {
    // Every entry is converted by the import's own split, so the content hash and the session proof (and the other
    // derived fields) come from the entry's points and log, never from what the file claims: a version-1 session as
    // at the upgrade; a stored drive from its three records (QA R1). A version-2 file is restored whole or not at all:
    // a stored entry that cannot convert makes the file damaged. A version-1 file (written after a failed upgrade) is
    // restored in part by design: a session that cannot convert is named, and the others restore.
    try {
      if (e.kind === 'stored') {
        const r = split(rebuildSession(e.drive, (e.points?.trip ?? null) as TripData | null,
          (e.diagnostics?.diagnostics ?? null) as DiagnosticData | null))
        records.push({ drive: r.drive, points: e.points ? r.points : null, diagnostics: e.diagnostics ? r.diagnostics : null })
      } else {
        records.push(split(e.session))
      }
    } catch {
      if (e.kind === 'stored') throw damaged(`drive ${i + 1} cannot be converted`)
      const name = (e.session as { name?: unknown } | null)?.name
      notRestorable.push(typeof name === 'string' && name !== '' ? name : 'A drive with no name')
    }
    await yieldToPage()                                       // hashing a long drive takes a moment: let the page answer
  }
  const { added, skipped } = await restoreRecords(records)
  return { added, skipped, notRestorable }
}
