import { useEffect, useState } from 'react'
import { useStore } from '../../store'
import { hrefOf } from '../../lib/address'
import { startImport } from './startImport'

/**
 * The import's progress and outcome, on every page (#384 ticket 06): files done of files chosen and the current file's
 * stage while it runs; then what was imported, each failure with its file and reason, and each "already imported" with
 * a link to the drive and what it filled in, and each part of a file that was skipped (#266).
 */
export function ImportStatus() {
  const job = useStore((s) => s.importJob)
  const setJob = useStore((s) => s.setImportJob)
  const drives = useStore((s) => s.drives)
  if (!job) return null
  const p = job.progress
  const nameOf = (id: string, fallback: string) => drives.find((d) => d.id === id)?.name ?? fallback
  return (
    <section className="a-page a-import" aria-label="Import">
      {job.running && p && (
        <>
          <div role="progressbar" aria-label="Files imported" aria-valuemin={0} aria-valuemax={p.total} aria-valuenow={p.done}
            className="a-progress"><div style={{ width: `${(p.done / p.total) * 100}%` }} /></div>
          <p data-import-stage>File {Math.min(p.done + 1, p.total)} of {p.total}: {p.file} · {p.stage}</p>
        </>
      )}
      {/* keyed by position: the same file dropped twice gives the same line twice (QA N1, REVIEW-07) */}
      {(job.notes ?? []).map((n, i) => <p key={i} className="a-failed">{n}</p>)}
      {!job.running && (
        <>
          <p role="status">Done. Imported {job.storedCount === 1 ? '1 drive' : `${job.storedCount} drives`}.</p>
          {/* role="list": WebKit stops announcing a list styled without bullets (QA, delivery B review) */}
          <ul className="a-import-lines" role="list">
            {job.results.flatMap((r, i) => [
              ...(r.error || r.already.length > 0 ? [
                <li key={i}>
                  {r.error ? <span className="a-failed">{r.file}: {r.error}</span>
                    : r.already.map((a) => (
                      <span key={a.existingId}>{r.file}: already imported <a href={hrefOf({ place: 'drive', id: a.existingId })}>{nameOf(a.existingId, a.name)}</a>
                        {a.filled.length > 0 ? ` (filled in: ${a.filled.join(', ')})` : ''}</span>
                    ))}
                </li>] : []),
              // a part of the file that was skipped (#266): a line of its own, under the file's outcome
              ...(r.notes ?? []).map((n, k) => <li key={`${i}-note-${k}`}>{r.file}: {n}</li>),
            ])}
          </ul>
          <button type="button" className="a-btn" onClick={() => setJob(null)}>Close</button>
        </>
      )}
    </section>
  )
}

/** Files dropped anywhere on the page import (only drags that carry files; the page is never left for the file). */
export function DropToImport() {
  const [over, setOver] = useState(false)
  useEffect(() => {
    const hasFiles = (e: DragEvent) => [...(e.dataTransfer?.types ?? [])].includes('Files')
    const enter = (e: DragEvent) => { if (hasFiles(e)) { e.preventDefault(); setOver(true) } }
    const leave = (e: DragEvent) => { if (!e.relatedTarget) setOver(false) }
    const drop = (e: DragEvent) => {
      if (!hasFiles(e)) return
      e.preventDefault()
      setOver(false)
      void startImport([...(e.dataTransfer?.files ?? [])])
    }
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragover', enter)
    window.addEventListener('dragleave', leave)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragover', enter)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('drop', drop)
    }
  }, [])
  return over ? <div className="a-dropzone" aria-hidden="true"><p>Drop to import</p></div> : null
}
