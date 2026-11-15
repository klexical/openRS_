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
  return defaults
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
  setPalette: (palette) => { persist({ palette }); set({ palette }) },

  resetDefaults: () => {
    localStorage.removeItem(STORAGE_KEY)
    set(defaults)
  },
}))
