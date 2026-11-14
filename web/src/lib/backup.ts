// Back up all drives, and restore (#384 ticket 02). Loaded on demand (Settings, the failed-upgrade message), not on the
// first visit. The file format is in backupFormat.ts; the IndexedDB reads and writes are in storageBackup.ts, which
// opens the database through storage.ts (and on its own, at any version, to read for a backup).

import { split } from './storage'
import { readEverything, restoreRecords, NewerStorageError, type RestoreRecord } from './storageBackup'
import { buildBackup, readBackup, BackupError, BACKUP_FORMAT_VERSION, type BackupFile as File_ } from './backupFormat'
import type { WorkerReply } from './backupWorker'

export { BackupError, BACKUP_FORMAT_VERSION }

export interface BackupFile extends File_ {
  /** Where the file was built: in a worker, or on the page in steps (where no worker could start). */
  builtIn: 'worker' | 'page'
}

export interface RestoreOutcome {
  /** Drives brought in. */
  added: number
  /** Drives already here (same id): skipped, never overwritten. */
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

/** Restore a backup file: adds what is missing, skips what is here (same id), never overwrites or deletes. */
export async function restoreBackup(file: Blob): Promise<RestoreOutcome> {
  const entries = await readBackup(file, yieldToPage)
  const records: RestoreRecord[] = []
  const notRestorable: string[] = []
  for (const e of entries) {
    if (e.kind === 'stored') { records.push(e); continue }
    // A version-1 session converts as the upgrade would; one that cannot is named, and the others still restore
    try {
      records.push(split(e.session))
    } catch {
      const name = (e.session as { name?: unknown } | null)?.name
      notRestorable.push(typeof name === 'string' && name !== '' ? name : 'A drive with no name')
    }
  }
  const { added, skipped } = await restoreRecords(records)
  return { added, skipped, notRestorable }
}
