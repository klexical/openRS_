/** Unit conversion and formatting helpers. */

export const KM_TO_MI = 0.621371
export const KPA_TO_PSI = 0.145038
export const L_PER_100KM_TO_MPG = 235.215

export function fmtNumber(n: number, decimals = 1): string {
  if (!isFinite(n)) return '—'
  return n.toFixed(decimals)
}

export function fmtDuration(ms: number): string {
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

export function fmtTimestamp(epoch: number): string {
  return new Date(epoch).toLocaleString()
}

export function fmtDateShort(epoch: number): string {
  return new Date(epoch).toLocaleDateString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
  })
}

export function fmtTemp(c: number): string {
  if (c <= -90) return '—'
  return `${Math.round(c)}°C`
}

export function fmtSpeed(kph: number): string {
  return `${Math.round(kph)} kph`
}

export function fmtPsi(psi: number): string {
  if (psi < 0) return '—'
  return `${fmtNumber(psi)} PSI`
}

// ── Unit-aware formatting hook ──

import { useSettings } from '../store/settings'
import {
  fmtSpeed as unitFmtSpeed,
  fmtDistance as unitFmtDistance,
  fmtTemp as unitFmtTemp,
  fmtBoost as unitFmtBoost,
  fmtTirePress as unitFmtTirePress,
  fmtFuelEconomy as unitFmtFuelEconomy,
  speedUnit,
  distanceUnit,
  tempUnit,
  fuelEconomyUnit,
} from './units'

/** Returns formatters bound to the user's current unit preferences. */
export function useUnitFormatters() {
  const { unitSystem, boostUnit, tirePressUnit } = useSettings()

  return {
    speed: (kph: number) => unitFmtSpeed(kph, unitSystem),
    speedUnit: speedUnit(unitSystem),
    distance: (km: number) => unitFmtDistance(km, unitSystem),
    distanceUnit: distanceUnit(unitSystem),
    temp: (c: number) => unitFmtTemp(c, unitSystem),
    tempUnit: tempUnit(unitSystem),
    boost: (psi: number) => unitFmtBoost(psi, boostUnit),
    boostUnit,
    tirePress: (psi: number) => unitFmtTirePress(psi, tirePressUnit),
    tirePressUnit,
    fuelEconomy: (lPer100km: number) => unitFmtFuelEconomy(lPer100km, unitSystem),
    fuelEconomyUnit: fuelEconomyUnit(unitSystem),
    unitSystem,
  }
}
