// Match device (#384 ticket 08): Night when the device is set to dark, Day otherwise. A device that states no preference
// is reported as light by a browser, and the two cannot be told apart, so it shows Day (owner, 2026-11-14); so does a
// browser without matchMedia. A change of the device setting is followed while Sapphire is open: the media query's
// change event, or the older addListener form where a browser (older Safari) has only that.
import { useSyncExternalStore } from 'react'
import type { PaletteChoice } from '../store/settings'
import type { PaletteName } from '../styles/lookA'

const QUERY = '(prefers-color-scheme: dark)'
function query(): MediaQueryList | null {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(QUERY) : null
}
function subscribe(onChange: () => void): () => void {
  const mql = query()
  if (!mql) return () => {}
  if (typeof mql.addEventListener === 'function') {
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }
  mql.addListener(onChange)                                     // older Safari: only the listener form
  return () => mql.removeListener(onChange)
}
const deviceIsDark = () => query()?.matches ?? false

/** The palette to draw in: the one chosen, or for Match device the one the device's setting gives. Never Ultra by itself. */
export function useResolvedPalette(choice: PaletteChoice): PaletteName {
  const dark = useSyncExternalStore(subscribe, deviceIsDark, deviceIsDark)
  if (choice !== 'match') return choice                        // an unknown stored value never gets here: settings.ts (QA R5)
  return dark ? 'night' : 'day'
}
