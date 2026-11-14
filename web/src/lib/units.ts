/** Unit conversion library — metric ↔ imperial. */

export type UnitSystem = 'metric' | 'imperial'

// ── Conversion constants ──

const KM_TO_MI = 0.621371
const KPA_TO_PSI = 0.145038
const L_PER_100KM_TO_MPG = 235.215

// ── Speed ──

export function convertSpeed(kph: number, unit: UnitSystem): number {
  return unit === 'imperial' ? kph * KM_TO_MI : kph
}

export function speedUnit(unit: UnitSystem): string {
  return unit === 'imperial' ? 'MPH' : 'KPH'
}

export function fmtSpeed(kph: number, unit: UnitSystem): string {
  if (!isFinite(kph)) return '—'
  const v = convertSpeed(kph, unit)
  return `${Math.round(v)} ${speedUnit(unit)}`
}

// ── Distance ──

export function convertDistance(km: number, unit: UnitSystem): number {
  return unit === 'imperial' ? km * KM_TO_MI : km
}

export function distanceUnit(unit: UnitSystem): string {
  return unit === 'imperial' ? 'mi' : 'km'
}

export function fmtDistance(km: number, unit: UnitSystem): string {
  if (!isFinite(km)) return '—'
  const v = convertDistance(km, unit)
  return `${v.toFixed(1)} ${distanceUnit(unit)}`
}

// ── Temperature ──

export function convertTemp(c: number, unit: UnitSystem): number {
  return unit === 'imperial' ? c * 9 / 5 + 32 : c
}

export function tempUnit(unit: UnitSystem): string {
  return unit === 'imperial' ? '°F' : '°C'
}

export function fmtTemp(c: number, unit: UnitSystem): string {
  if (c <= -90) return '—'
  const v = convertTemp(c, unit)
  return `${Math.round(v)}${tempUnit(unit)}`
}

// ── Boost pressure ──

export type BoostUnit = 'PSI' | 'bar' | 'kPa'

export function convertBoost(psi: number, boostUnit: BoostUnit): number {
  switch (boostUnit) {
    case 'bar': return psi / KPA_TO_PSI / 100
    case 'kPa': return psi / KPA_TO_PSI
    default: return psi
  }
}

export function fmtBoost(psi: number, bu: BoostUnit): string {
  if (!isFinite(psi)) return '—'
  const v = convertBoost(psi, bu)
  const decimals = bu === 'bar' ? 2 : bu === 'kPa' ? 0 : 1
  return `${v.toFixed(decimals)} ${bu}`
}

// ── Tire pressure ──

export type TirePressUnit = 'PSI' | 'bar' | 'kPa'

export function convertTirePress(psi: number, tpu: TirePressUnit): number {
  switch (tpu) {
    case 'bar': return psi * 0.0689476
    case 'kPa': return psi * 6.89476
    default: return psi
  }
}

/** Decimal places per unit for tyre pressure labels. */
export const tirePressDecimals = (tpu: TirePressUnit): number => (tpu === 'bar' ? 2 : tpu === 'kPa' ? 0 : 1)

export function fmtTirePress(psi: number, tpu: TirePressUnit): string {
  if (psi < 0) return '—'
  const v = convertTirePress(psi, tpu)
  return `${v.toFixed(tirePressDecimals(tpu))} ${tpu}`
}

// ── Fuel economy ──

export function convertFuelEconomy(lPer100km: number, unit: UnitSystem): number {
  if (unit === 'imperial' && lPer100km > 0) return L_PER_100KM_TO_MPG / lPer100km
  return lPer100km
}

export function fuelEconomyUnit(unit: UnitSystem): string {
  return unit === 'imperial' ? 'MPG' : 'L/100km'
}

export function fmtFuelEconomy(lPer100km: number, unit: UnitSystem): string {
  if (!isFinite(lPer100km) || lPer100km <= 0) return '—'
  const v = convertFuelEconomy(lPer100km, unit)
  return `${v.toFixed(1)} ${fuelEconomyUnit(unit)}`
}

// ── TRIP chart rows and peak lines in the selected units (#381) ──
// Rows as `tripChartData` (lib/readings.ts) builds them: speeds in km/h, temperatures in °C, tyre pressures in psi.
// A null (no reading) stays null, a gap, never 0; a genuine 0 converts like any reading (0 °C → 32 °F). Pressure keys
// go through `pressureChartRows` (lib/boost.ts) instead.

const SPEED_KEYS = ['speedKph', 'wsFL', 'wsFR', 'wsRL', 'wsRR']
const TEMP_KEYS = ['coolantC', 'oilTempC', 'rduTempC', 'ptuTempC', 'tireTempLF', 'tireTempRF', 'tireTempLR', 'tireTempRR']
const TIRE_PRESS_KEYS = ['tirePressLF', 'tirePressRF', 'tirePressLR', 'tirePressRR']

/** Chart rows with the speed, temperature and tyre pressure keys converted (other keys untouched). */
export function unitChartRows<T extends Record<string, number | null>>(rows: T[], unit: UnitSystem, tpu: TirePressUnit): T[] {
  if (unit === 'metric' && tpu === 'PSI') return rows
  const each = (out: Record<string, number | null>, keys: string[], f: (v: number) => number) => {
    for (const k of keys) {
      if (!(k in out)) continue
      const v = out[k]
      out[k] = v == null ? null : f(v)
    }
  }
  return rows.map((r) => {
    const out: Record<string, number | null> = { ...r }
    each(out, SPEED_KEYS, (v) => convertSpeed(v, unit))
    each(out, TEMP_KEYS, (v) => convertTemp(v, unit))
    each(out, TIRE_PRESS_KEYS, (v) => convertTirePress(v, tpu))
    return out as T
  })
}

/** Speed peak events in [unit] (other peak types untouched). */
export function speedPeaks<P extends { type: string; value: number }>(peaks: P[], unit: UnitSystem): P[] {
  return peaks.map((p) => (p.type === 'speed' ? { ...p, value: convertSpeed(p.value, unit) } : p))
}
