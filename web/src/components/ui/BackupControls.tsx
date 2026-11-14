// Back up all drives, and restore (#384 ticket 02). This module and lib/backup load together when the controls are
// first shown (LazyBackupControls), not on the first visit.
import { useEffect, useRef, useState } from 'react'
import { useStore } from '../../store'
import { listDrives } from '../../lib/storage'
import { makeBackup, restoreBackup } from '../../lib/backup'

const drivesText = (n: number) => `${n} drive${n === 1 ? '' : 's'}`
const button = `px-3 py-2 rounded-md text-xs font-mono uppercase tracking-wider border border-brd text-frost
  hover:bg-surf3 transition-colors disabled:opacity-30 disabled:cursor-not-allowed`

type BackupState =
  | { state: 'idle' }
  | { state: 'building'; done: number; total: number }
  | { state: 'ready'; url: string; name: string; drives: number }
  | { state: 'failed'; message: string }

/**
 * A button that builds the backup file, shows its progress, then offers it as a download link. Leaving the page while
 * it is built stops it: the worker is ended and no download address is made.
 */
export function BackupButton({ label, afterFailedUpgrade = false }: { label: string; afterFailedUpgrade?: boolean }) {
  const [s, setS] = useState<BackupState>({ state: 'idle' })
  const storage = useStore((st) => st.storage)
  const url = useRef<string | null>(null)
  const building = useRef<AbortController | null>(null)
  const forget = () => { if (url.current) URL.revokeObjectURL(url.current); url.current = null }
  useEffect(() => () => { building.current?.abort(); forget() }, [])

  const run = async () => {
    forget()
    building.current?.abort()
    const stop = new AbortController()
    building.current = stop
    setS({ state: 'building', done: 0, total: 0 })
    try {
      const file = await makeBackup((done, total) => { if (!stop.signal.aborted) setS({ state: 'building', done, total }) }, { signal: stop.signal })
      if (stop.signal.aborted) return
      url.current = URL.createObjectURL(file.blob)
      setS({ state: 'ready', url: url.current, name: file.name, drives: file.drives })
    } catch (err) {
      if (stop.signal.aborted) return
      console.warn('[Sapphire] Backup failed:', err)
      setS({ state: 'failed', message: plainMessage(err, "Sapphire couldn't build the backup. Nothing was written.") })
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-3">
      <button onClick={run} disabled={s.state === 'building'} className={button}>{label}</button>
      <span role="status" className="text-xs font-mono text-dim">
        {s.state === 'building' && (storage.state === 'blocked'
          // the database waits for an older tab, and so does the backup: say what unblocks it
          ? <span className="text-warn">Close Sapphire's other tabs: the backup continues once they are closed.</span>
          : s.total > 0 ? `Building the backup: ${s.done} of ${drivesText(s.total)}…` : 'Building the backup…')}
        {s.state === 'ready' && (
          <a href={s.url} download={s.name} className="text-accent underline">
            Download {s.name} ({drivesText(s.drives)})
          </a>
        )}
        {s.state === 'failed' && <span className="text-warn">{s.message}</span>}
      </span>
      {afterFailedUpgrade && (
        <span className="text-xs font-mono">Keep this file. It can be restored once Sapphire's update has completed in this browser.</span>
      )}
    </div>
  )
}

/** Choose a backup file and restore it: adds what is missing, skips what is here, never overwrites. */
export function RestoreInput() {
  const setDrives = useStore((st) => st.setDrives)
  const [text, setText] = useState<{ message: string; warn: boolean } | null>(null)
  const [busy, setBusy] = useState(false)

  const restore = async (file: File | undefined) => {
    if (!file) return
    setBusy(true)
    setText({ message: 'Restoring…', warn: false })
    try {
      const out = await restoreBackup(file)
      setDrives(await listDrives())
      const parts = [`Restored ${drivesText(out.added)}.`]
      parts.push(`${drivesText(out.skipped)} already here, left as they are.`)   // the same id, or the same drive
      if (out.notRestorable.length > 0) parts.push(`Could not convert: ${out.notRestorable.join(', ')}.`)
      setText({ message: parts.join(' '), warn: out.notRestorable.length > 0 })
    } catch (err) {
      console.warn('[Sapphire] Restore failed:', err)
      setText({ message: plainMessage(err, "Sapphire couldn't restore the backup. Nothing was restored."), warn: true })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-3">
      <label className={`${button} cursor-pointer`}>
        Restore from a backup
        <input type="file" accept=".zip,application/zip" aria-label="Restore from a backup" className="sr-only" disabled={busy}
          onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; void restore(f) }} />
      </label>
      {text && <span role="status" className={`text-xs font-mono ${text.warn ? 'text-warn' : 'text-dim'}`}>{text.message}</span>}
    </div>
  )
}

/** The backup module's own plain words, or [fallback] for anything else (the engine's text goes to the console). */
function plainMessage(err: unknown, fallback: string): string {
  return err instanceof Error && (err as { plainWords?: unknown }).plainWords === true ? err.message : fallback
}
