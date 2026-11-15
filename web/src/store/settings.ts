import { create } from 'zustand'
import type { UnitSystem, BoostUnit, TirePressUnit } from '../lib/units'

export type ThemeId = 'cyan' | 'red' | 'orange' | 'grey' | 'black' | 'white'
/** The palette chosen in Settings (#384 ticket 08): Match device follows the device's light / dark setting. */
export type PaletteChoice = 'match' | 'night' | 'day' | 'ultra'

interface Settings {
  unitSystem: UnitSystem
  boostUnit: BoostUnit
  tirePressUnit: TirePressUnit
  themeId: ThemeId
  palette: PaletteChoice
}

interface SettingsState extends Settings {
  setUnitSystem: (unit: UnitSystem) => void
  setBoostUnit: (unit: BoostUnit) => void
  setTirePressUnit: (unit: TirePressUnit) => void
  setThemeId: (id: ThemeId) => void
  setPalette: (palette: PaletteChoice) => void
  resetDefaults: () => void
}

const STORAGE_KEY = 'sapphire_settings'

const defaults: Settings = {
  unitSystem: 'metric',
  boostUnit: 'PSI',
  tirePressUnit: 'PSI',
  themeId: 'cyan',
  palette: 'match',               // a first visit follows the device (owner, 2026-11-14)
}

const PALETTES: readonly PaletteChoice[] = ['match', 'night', 'day', 'ultra']

function hasRecord(): boolean {
  try { return localStorage.getItem(STORAGE_KEY) !== null } catch { return false }
}
/**
 * No settings record at load: is this a first visit, or a returning user who never changed a setting (main wrote the
 * record only on a change)? Not known until the storage opens (QA G2). Until then the palette is Night, the only look
 * there was, so a returning user never sees a frame of Day; [decideFirstVisitPalette] then settles it, once.
 */
let paletteUndecided = !hasRecord()

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const stored = JSON.parse(raw)
      const s = { ...defaults, ...stored }
      // settings stored before the palettes (#384 ticket 08) keep Night, the only look there was: Match device is for a
      // first visit (owner, 2026-11-15, Q8)
      if (stored && typeof stored === 'object' && !('palette' in stored)) s.palette = 'night'
      // a palette this build does not know (a later build's, after a revert): Match device, as an unknown paint
      // falls back to Nitrous Blue (QA R5)
      if (!PALETTES.includes(s.palette)) s.palette = defaults.palette
      return s
    }
  } catch { /* ignore */ }
  return { ...defaults, palette: 'night' }        // no record: Night until the first visit is known (above)
}

/**
 * Once Sapphire's storage has opened: with no settings record, a visit that created the storage is a first visit and
 * takes Match device; anyone with earlier data keeps Night (owner, 2026-11-15, Q8). Stored, so the next visit knows at
 * once. Does nothing when there was a record, or when a palette was chosen meanwhile.
 */
export function decideFirstVisitPalette(storageCreatedNow: boolean): void {
  if (!paletteUndecided) return
  paletteUndecided = false
  useSettings.getState().setPalette(storageCreatedNow ? 'match' : 'night')
}

function persist(partial: Partial<Settings>) {
  try {
    const current = loadSettings()
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...current, ...partial }))
  } catch { /* ignore */ }
}

export const useSettings = create<SettingsState>((set) => ({
  ...loadSettings(),

  setUnitSystem: (unitSystem) => { persist({ unitSystem }); set({ unitSystem }) },
  setBoostUnit: (boostUnit) => { persist({ boostUnit }); set({ boostUnit }) },
  setTirePressUnit: (tirePressUnit) => { persist({ tirePressUnit }); set({ tirePressUnit }) },
  setThemeId: (themeId) => { persist({ themeId }); set({ themeId }) },
  setPalette: (palette) => { paletteUndecided = false; persist({ palette }); set({ palette }) },

  resetDefaults: () => {
    paletteUndecided = false                      // a Reset before the storage opens is the choice: not overwritten (QA item 5)
    // a record with the defaults, not none: no record would read as "returning, keep Night" on the next visit (QA G2)
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(defaults)) } catch { /* ignore */ }
    set(defaults)
  },
}))
