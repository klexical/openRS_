// Import as an action (#384 ticket 06): started from the list's Import button or by dropping files anywhere on the page.
// One import at a time. The parser and JSZip load on first use. One drive stored lands on that drive; several land on
// the list, with the new rows marked (and its search and filters cleared, so none of them is hidden).
import { useStore, type ImportJob } from '../../store'
import { listDrives, loadDrive } from '../../lib/storage'
import { go } from '../../lib/address'

export async function startImport(files: File[]): Promise<void> {
  const st = useStore.getState()
  if (files.length === 0 || st.importJob?.running) return
  st.setNewIds([])
  let job: ImportJob = { running: true, progress: { done: 0, total: files.length, file: files[0].name, stage: 'reading' }, results: [], storedCount: 0 }
  st.setImportJob(job)
  let importFiles: typeof import('../../lib/importFiles').importFiles
  try {
    ({ importFiles } = await import('../../lib/importFiles'))
  } catch (err) {
    console.warn('[Sapphire] The import could not load:', err)
    st.setImportJob({ running: false, progress: null, storedCount: 0,
      results: files.map((f) => ({ file: f.name, stored: [], already: [], error: 'Sapphire was updated: reload this tab to import' })) })
    return
  }
  const results = await importFiles(files, (progress) => { job = { ...job, progress }; useStore.getState().setImportJob(job) })

  const stored = results.flatMap((r) => r.stored)
  const filled = results.flatMap((r) => r.already.filter((a) => a.filled.length > 0).map((a) => a.existingId))
  const s = useStore.getState()
  if (stored.length > 0) s.addDrives(stored)
  if (filled.length > 0) {
    // a stored drive was filled in: its small record, and the open drive if it is that one, are read again
    try {
      s.setDrives(await listDrives())
      const open = useStore.getState().activeSessionId
      if (open && filled.includes(open)) {
        const fresh = await loadDrive(open)
        if (fresh && useStore.getState().activeSessionId === open) useStore.getState().setActiveSession(fresh)
      }
    } catch (err) { console.warn('[Sapphire] Could not read the filled-in drives again:', err) }
  }
  s.setNewIds(stored.map((d) => d.id))
  s.setImportJob({ running: false, progress: null, results, storedCount: stored.length })
  if (stored.length === 1) go({ place: 'drive', id: stored[0].id })
  else if (stored.length > 1) {
    s.setListSearch('')
    s.setFilters({ warnings: false, laps: false })
    go({ place: 'drives' })
  }
}
