import { create } from 'zustand'
import type { UnitSystem, BoostUnit, TirePressUnit } from '../lib/units'

export type ThemeId = 'cyan' | 'red' | 'orange' | 'grey' | 'black' | 'white'

interface Settings {
  unitSystem: UnitSystem
  boostUnit: BoostUnit
  tirePressUnit: TirePressUnit
  themeId: ThemeId
}

interface SettingsState extends Settings {
  setUnitSystem: (unit: UnitSystem) => void
  setBoostUnit: (unit: BoostUnit) => void
  setTirePressUnit: (unit: TirePressUnit) => void
  setThemeId: (id: ThemeId) => void
  resetDefaults: () => void
}

const STORAGE_KEY = 'sapphire_settings'

const defaults: Settings = {
  unitSystem: 'metric',
  boostUnit: 'PSI',
  tirePressUnit: 'PSI',
  themeId: 'cyan',
}

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return { ...defaults, ...JSON.parse(raw) }
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

  resetDefaults: () => {
    localStorage.removeItem(STORAGE_KEY)
    set(defaults)
  },
}))
