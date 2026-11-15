// Keep my data (#384 ticket 07): after the first import that stores a drive, Sapphire asks the browser to keep its data
// (navigator.storage.persist()), once. Settings reads the answer with persisted() and the space with estimate(), neither
// of which ever asks. "Asked" is remembered beside the settings (localStorage), not with the drives, so Clear all leaves
// it as it is and Sapphire never asks again (owner, 2026-11-14).

const ASKED_KEY = 'sapphire_keep_asked'

type Manager = Pick<StorageManager, 'persist' | 'persisted' | 'estimate'>
function manager(): Partial<Manager> | null {
  return typeof navigator !== 'undefined' && navigator.storage ? navigator.storage : null
}

function asked(): boolean {
  try { return localStorage.getItem(ASKED_KEY) !== null } catch { return false }
}

/**
 * After an import that stored a drive: ask the browser to keep the data, unless Sapphire asked before. Remembered before
 * the browser answers, so a second import while the first answer is pending does not ask again. Never rejects.
 */
export async function askToKeepOnce(): Promise<void> {
  const m = manager()
  if (asked() || !m?.persist) return
  try { localStorage.setItem(ASKED_KEY, '1') } catch { return }   // not remembered: never ask
  try { await m.persist() } catch (err) { console.warn('[Sapphire] The browser did not answer the request to keep the data:', err) }
}

export interface KeepStatus {
  /** The browser's answer: true kept, false not kept; null when it does not say. */
  kept: boolean | null
  /** Bytes used and allowed, the browser's estimate; null when it does not say. */
  usage: number | null
  quota: number | null
}

/** What the browser says now. Never asks it to keep anything; never rejects. */
export async function keepStatus(): Promise<KeepStatus> {
  const m = manager()
  const [kept, est] = await Promise.all([
    m?.persisted ? m.persisted().catch(() => null) : Promise.resolve(null),
    m?.estimate ? m.estimate().catch(() => null) : Promise.resolve(null),
  ])
  return { kept, usage: est?.usage ?? null, quota: est?.quota ?? null }
}

/** "14 MB", "0.5 MB", "2 GB", "1.5 GB": a size for "about", rounded as a person would say it. */
export function aboutSize(bytes: number): string {
  const mb = bytes / 1e6
  if (mb < 1) return `${Math.max(0.1, Math.round(mb * 10) / 10)} MB`
  if (mb < 1000) return `${Math.round(mb)} MB`
  const gb = Math.round(mb / 100) / 10
  return `${Number.isInteger(gb) ? gb.toFixed(0) : gb} GB`
}
