// Import as an action (#384 ticket 06): several files at once, each on its own. Inside one ZIP the rule stays as
// before: all of it is stored, or none of it (one transaction). Between files, one that fails loses none of the others;
// each failure is named with its file and reason. Loaded on first use (JSZip and the parser stay off the first visit).
import JSZip from 'jszip'
import { importLoadedZip } from './import'
import { storeImported, type DriveRecord, type FilledPart } from './storage'

export type ImportStage = 'reading' | 'parsing' | 'storing'
export interface ImportProgress { done: number; total: number; file: string; stage: ImportStage }
export interface FileResult {
  file: string
  stored: DriveRecord[]
  already: { name: string; existingId: string; filled: FilledPart[] }[]
  /** Why nothing of this file was stored; absent when it was read. */
  error?: string
}

const yieldToPage = () => new Promise<void>((r) => setTimeout(r, 0))

export async function importFiles(files: File[], onProgress: (p: ImportProgress) => void): Promise<FileResult[]> {
  const results: FileResult[] = []
  for (const [i, file] of files.entries()) {
    const step = (stage: ImportStage) => onProgress({ done: i, total: files.length, file: file.name, stage })
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
      const sessions = await importLoadedZip(zip, file.name)
      step('storing')
      await yieldToPage()
      const outcome = await storeImported(sessions)
      result.stored = outcome.stored
      result.already = outcome.alreadyImported
    } catch (err) {
      result.error = err instanceof Error && err.message ? err.message : 'it could not be imported'
    }
    results.push(result)
    onProgress({ done: i + 1, total: files.length, file: file.name, stage: 'storing' })
    await yieldToPage()
  }
  return results
}

async function isSapphireBackup(zip: JSZip): Promise<boolean> {
  const m = zip.file('manifest.json')
  if (!m) return false
  try { return (JSON.parse(await m.async('string')) as { format?: unknown }).format === 'sapphire-backup' } catch { return false }
}
