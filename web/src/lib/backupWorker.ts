// The backup is built here, off the page, so the page stays usable (#384 ticket 02). The worker spike showed idb and
// JSZip work in a module worker in Chromium; where a browser cannot start this worker, backup.ts builds on the page.

import { readEverything, NewerStorageError } from './storageBackup'
import { buildBackup, BackupError } from './backupFormat'

export type WorkerReply =
  | { type: 'started' }
  | { type: 'progress'; done: number; total: number }
  | { type: 'done'; blob: Blob; name: string; drives: number }
  | { type: 'failed'; message: string; plain: boolean }

const post = (m: WorkerReply) => (self as unknown as Worker).postMessage(m)

self.onmessage = async () => {
  post({ type: 'started' })              // at once, before the read: the page's watchdog waits only for this
  try {
    const file = await buildBackup(await readEverything(), (done, total) => post({ type: 'progress', done, total }))
    post({ type: 'done', ...file })
  } catch (err) {
    const plain = err instanceof BackupError || err instanceof NewerStorageError
    post({ type: 'failed', message: plain ? (err as Error).message : err instanceof Error ? err.name : String(err), plain })
  }
}
