// Import as an action (#384 ticket 06): started from the list's Import button or by dropping files anywhere on the page.
// One import at a time. The parser and JSZip load on first use. One drive stored lands on that drive; several land on
// the list, with the new rows marked (and its search and filters cleared, so none of them is hidden).
import { useStore, type ImportJob } from '../../store'
import { ImportCodeUnavailable, importDrives } from '../../lib/driveLibrary'
import type { FileResult } from '../../lib/importFiles'
import { go } from '../../lib/address'

export async function startImport(files: File[]): Promise<void> {
  const st = useStore.getState()
  if (files.length === 0) return
  if (st.importJob?.running) {
    // one import at a time (QA, delivery B review): the files are not queued; the page says so, in one line
    const names = files.map((f) => f.name).join(', ')
    const line = `An import was running: ${names} ${files.length === 1 ? 'was not imported. Drop it again.' : 'were not imported. Drop them again.'}`
    st.setImportJob({ ...st.importJob, notes: [...(st.importJob.notes ?? []), line] })
    return
  }
  st.setNewIds([])
  let job: ImportJob = { running: true, progress: { done: 0, total: files.length, file: files[0].name, stage: 'reading' }, results: [], storedCount: 0 }
  st.setImportJob(job)
  // the Drive library loads the reading code, stores each file's drives and mirrors once at the end (the list, a
  // filled-in Current drive); the store's copy of the job is read again on each step: a note added meanwhile is kept
  let results: FileResult[]
  try {
    results = await importDrives(files, (progress) => {
      job = { ...(useStore.getState().importJob ?? job), progress }; useStore.getState().setImportJob(job)
    })
  } catch (err) {
    if (!(err instanceof ImportCodeUnavailable)) throw err
    console.warn('[Sapphire] The import could not load:', err.cause)
    st.setImportJob({ running: false, progress: null, storedCount: 0,
      results: files.map((f) => ({ file: f.name, stored: [], already: [], error: 'Sapphire was updated: reload this tab to import' })) })
    return
  }

  const stored = results.flatMap((r) => r.stored)
  const s = useStore.getState()
  s.setNewIds(stored.map((d) => d.id))
  s.setImportJob({ running: false, progress: null, results, storedCount: stored.length, notes: useStore.getState().importJob?.notes })
  if (stored.length === 1) go({ place: 'drive', id: stored[0].id })
  else if (stored.length > 1) {
    s.setListSearch('')
    s.setFilters({ warnings: false, laps: false })
    go({ place: 'drives' })
  }
}
