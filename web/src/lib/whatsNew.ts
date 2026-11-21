// "What's new" (#384 ticket 09): shown once to someone who had Sapphire before this update, never on a fresh install,
// never twice. "Seen" is remembered beside the settings (localStorage), not with the drives, so Clear all leaves it as
// it is (owner, 2026-11-14) and it is never shown again after one.
import { storageCreatedNow, storageStatus } from './storage'

/**
 * The update "What's new" speaks of: a new name here shows it once more to everyone who had Sapphire before. The dialog
 * shows this release's own changelog section (#416 ticket 4); it moves to a slice in that slice's last delivery (#416 ticket 4).
 */
export const RELEASE = 'slice-2'
const SEEN_KEY = 'sapphire_whats_new_seen'

function seen(): boolean {
  try { return localStorage.getItem(SEEN_KEY) === RELEASE } catch { return true }   // unknown: never show it by itself
}
export function markWhatsNewSeen(): void {
  try { localStorage.setItem(SEEN_KEY, RELEASE) } catch { /* not remembered: it is not shown by itself anyway */ }
}

/**
 * Called once Sapphire's storage has opened (the list was read): true when "What's new" should be shown now. A fresh
 * install marks it seen without showing it; storage that did not open shows nothing.
 */
export function showWhatsNewNow(): boolean {
  if (seen() || storageStatus().state !== 'ready') return false
  if (storageCreatedNow()) { markWhatsNewSeen(); return false }
  return true
}
