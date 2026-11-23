// Import as an action (#384 ticket 06): several files at once, each on its own. Inside one ZIP the rule stays as
// before: all of it is stored, or none of it (one transaction). Between files, one that fails loses none of the others;
// each failure is named with its file and reason. Loaded on first use (JSZip and the parser stay off the first visit).
import JSZip from 'jszip'
import { importLoadedZip } from './import'
import type { DriveRecord, FilledPart } from './storage'
import type { StoringStep } from './driveLibrary'

export type ImportStage = 'reading' | 'parsing' | 'storing'
export interface ImportProgress { done: number; total: number; file: string; stage: ImportStage }
export interface FileResult {
  file: string
  stored: DriveRecord[]
  already: { name: string; existingId: string; filled: FilledPart[] }[]
  /** Why nothing of this file was stored; absent when it was read. */
  error?: string
  /** A part of this file that was skipped, one line each (#266: a damaged diagnostic detail file); absent when none. */
  notes?: string[]
}

const yieldToPage = () => new Promise<void>((r) => setTimeout(r, 0))

/**
 * Read and store the files of one Import. [store] is the Drive library's storing step (lib/driveLibrary.ts importDrives):
 * the library alone writes drives; this module reads and parses, as before.
 */
export async function importFiles(files: File[], onProgress: (p: ImportProgress) => void, store: StoringStep): Promise<FileResult[]> {
  const results: FileResult[] = []
  for (const [i, file] of files.entries()) {
    let stage: ImportStage = 'reading'
    const step = (s: ImportStage) => { stage = s; onProgress({ done: i, total: files.length, file: file.name, stage: s }) }
    const result: FileResult = { file: file.name, stored: [], already: [] }
    try {
      if (!/\.zip$/i.test(file.name)) throw new Error('not a .zip file')
      step('reading')
      await yieldToPage()
      let zip: JSZip
      try { zip = await JSZip.loadAsync(file) } catch { throw new Error('not a ZIP this browser can read (damaged or incomplete)') }
      if (await isSapphireBackup(zip)) throw new Error('this is a Sapphire backup: restore it in Settings')
      step('parsing')
      await yieldToPage()
      const notes: string[] = []
      const sessions = await importLoadedZip(zip, file.name, notes)
      if (notes.length > 0) result.notes = notes
      step('storing')
      await yieldToPage()
      const outcome = await store(sessions)
      result.stored = outcome.stored
      result.already = outcome.alreadyImported
    } catch (err) {
      result.error = plainWords(err, stage)
    }
    results.push(result)
    onProgress({ done: i + 1, total: files.length, file: file.name, stage: 'storing' })
    await yieldToPage()
  }
  return results
}

/**
 * The reason shown for a file (QA, delivery B review: plain words, never the engine's own text). Sapphire's own errors
 * (the parser's, "nothing could be read", the checks above) are already plain; a failure of the browser's storage is
 * named by its kind, and its own text goes to the console only.
 */
function plainWords(err: unknown, stage: ImportStage): string {
  const name = (err as { name?: unknown } | null)?.name
  if (stage === 'storing' && (err instanceof DOMException || name === 'QuotaExceededError')) {
    console.warn('[Sapphire] Storing an import failed:', err)
    return name === 'QuotaExceededError'
      ? 'the browser has no room left to store it; nothing of this file was stored'
      : 'the browser could not store it; nothing of this file was stored'
  }
  return err instanceof Error && err.message ? err.message : 'it could not be imported'
}

async function isSapphireBackup(zip: JSZip): Promise<boolean> {
  const m = zip.file('manifest.json')
  if (!m) return false
  try { return (JSON.parse(await m.async('string')) as { format?: unknown }).format === 'sapphire-backup' } catch { return false }
}
