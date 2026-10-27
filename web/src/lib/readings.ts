import type { TripPoint } from '../types/session'

// Chart values for recorded readings. A sample without a reading becomes `null`, which Recharts draws as a
// gap — never a 0 dip. Genuine zeros (0 rpm, 0 % throttle, an empty tank, 0 °C) stay plotted.
// Sentinels follow the app's recorder: temperatures −99 ("not yet received"), polled values −1.

/** Temperature (°C) or null — anything ≤ −90 is the −99 sentinel. */
export const tempReading = (v: number): number | null => (Number.isFinite(v) && v > -90 ? v : null)

/** Non-negative polled value (fuel %, throttle %, tyre pressure) or null for the −1 sentinel. */
export const pctReading = (v: number): number | null => (Number.isFinite(v) && v >= 0 ? v : null)

/** Any other number — only NaN / Infinity from a malformed row is missing. */
export const numReading = (v: number): number | null => (Number.isFinite(v) ? v : null)

export type TripChartRow = Record<string, number | null>

/** TRIP chart rows: time in seconds from the first sample, readings or null. */
export function tripChartData(points: TripPoint[]): TripChartRow[] {
  const t0 = points[0]?.ts ?? 0
  return points.map((p) => ({
    ts: (p.ts - t0) / 1000,
    rpm: numReading(p.rpm),
    boostPsi: numReading(p.boostPsi),
    speedKph: numReading(p.speedKph),
    coolantC: tempReading(p.coolantC),
    oilTempC: tempReading(p.oilTempC),
    rduTempC: tempReading(p.rduTempC),
    ptuTempC: tempReading(p.ptuTempC),
    latG: numReading(p.latG),
    longG: numReading(p.longG),
    fuelPct: pctReading(p.fuelPct),
    wsFL: numReading(p.wheelSpeedFL),
    wsFR: numReading(p.wheelSpeedFR),
    wsRL: numReading(p.wheelSpeedRL),
    wsRR: numReading(p.wheelSpeedRR),
    awdL: numReading(p.awdTorqueL),
    awdR: numReading(p.awdTorqueR),
    throttlePct: pctReading(p.throttlePct),
    brakePressure: numReading(p.brakePressure),
    steeringAngle: numReading(p.steeringAngle),
    tirePressLF: pctReading(p.tirePressLF),
    tirePressRF: pctReading(p.tirePressRF),
    tirePressLR: pctReading(p.tirePressLR),
    tirePressRR: pctReading(p.tirePressRR),
    tireTempLF: tempReading(p.tireTempLF),
    tireTempRF: tempReading(p.tireTempRF),
    tireTempLR: tempReading(p.tireTempLR),
    tireTempRR: tempReading(p.tireTempRR),
  }))
}

/** TempStackChart rows (coolant / oil / RDU / PTU) — a missing layer is a gap, not a 0 floor. */
export function tempStackData(points: TripPoint[]): TripChartRow[] {
  const t0 = points[0]?.ts ?? 0
  return points.map((p) => ({
    ts: (p.ts - t0) / 1000,
    coolantC: tempReading(p.coolantC),
    oilTempC: tempReading(p.oilTempC),
    rduTempC: tempReading(p.rduTempC),
    ptuTempC: tempReading(p.ptuTempC),
  }))
}

export interface TpmsReadings {
  pressLF: number; pressRF: number; pressLR: number; pressRR: number
  tempLF: number; tempRF: number; tempLR: number; tempRR: number
}

/** Latest valid pressure (≥ 0) and temperature (> −90) per corner, or null when no corner ever reported. */
export function latestTpms(points: TripPoint[]): TpmsReadings | null {
  const r: TpmsReadings = { pressLF: -1, pressRF: -1, pressLR: -1, pressRR: -1, tempLF: -99, tempRF: -99, tempLR: -99, tempRR: -99 }
  const corners = ['LF', 'RF', 'LR', 'RR'] as const
  let need = 8
  for (let i = points.length - 1; i >= 0 && need > 0; i--) {
    const p = points[i]
    for (const c of corners) {
      const press = p[`tirePress${c}`]
      const temp = p[`tireTemp${c}`]
      if (r[`press${c}`] < 0 && press >= 0) { r[`press${c}`] = press; need-- }
      if (r[`temp${c}`] <= -90 && temp > -90) { r[`temp${c}`] = temp; need-- }
    }
  }
  const any = corners.some((c) => r[`press${c}`] >= 0 || r[`temp${c}`] > -90)
  return any ? r : null
}
