import type { TripPoint, PeakEvent } from '../types/session'
import { convertBoost, type BoostUnit } from './units'

// Boost provenance (#207), as the Android export records it. Two different measurements:
//   CAN_0F8  — HS-CAN 0x0F8 byte 5, the car's "Boost" signal (shown as BOOST)
//   TIP_033E — PCM throttle-inlet pressure minus measured baro (shown as TIP; pre-throttle, not proven manifold boost)
// A point without a reading has boostPsi null (never 0). A value recorded before the app stored sources (older
// exports, or rows with an empty boost_source) keeps its value with source null — "source not recorded", never
// given today's source. A genuine 0 from a source is a reading.

export type BoostSource = 'CAN_0F8' | 'TIP_033E'

export function parseBoostSource(v: unknown): BoostSource | null {
  return v === 'CAN_0F8' || v === 'TIP_033E' ? v : null
}

/** TIP / BOOST (CAN 0x0F8, or a value whose source wasn't recorded). */
export const boostLabel = (source: BoostSource | null | undefined): string => (source === 'TIP_033E' ? 'TIP' : 'BOOST')

/** Chart series key for a point's boost: one line per measurement, never joined. */
export type BoostKey = 'boostCan' | 'boostTip' | 'boostLegacy'
export const boostKeyOf = (source: BoostSource | null | undefined): BoostKey =>
  source === 'TIP_033E' ? 'boostTip' : source === 'CAN_0F8' ? 'boostCan' : 'boostLegacy'
export const BOOST_SERIES: { key: BoostKey; label: string }[] = [
  { key: 'boostTip', label: 'TIP' },
  { key: 'boostCan', label: 'BOOST 0x0F8' },
  { key: 'boostLegacy', label: 'BOOST (source not recorded)' },
]

/**
 * The one measurement a single-scale view uses (map colours, distribution, compare overlay): the source of the
 * highest sourced reading — the drive peak's source, as on Android. Null when the points carry no source (an older
 * export: its values are used as recorded).
 */
export function primaryBoostSource(points: TripPoint[]): BoostSource | null {
  let best: { psi: number; source: BoostSource } | null = null
  for (const p of points) {
    if (p.boostPsi == null || !p.boostSource) continue
    if (!best || p.boostPsi > best.psi) best = { psi: p.boostPsi, source: p.boostSource }
  }
  return best?.source ?? null
}

/** A point's boost for a single-scale view of [source]: its value if it is that measurement (or unsourced), else null. */
export function boostFor(p: TripPoint, source: BoostSource | null): number | null {
  if (p.boostPsi == null) return null
  if (source == null) return p.boostSource ? null : p.boostPsi
  return p.boostSource === source ? p.boostPsi : null
}

// ── Display in the selected pressure unit (#207 follow-up) ──
// Recorded values are gauge psi. Every plotted number — chart rows, histogram values, peak lines, gauges, sparklines,
// map legend and popups — goes through these so it matches its unit label.

export function pressureIn(psi: number | null | undefined, unit: BoostUnit): number | null {
  return psi == null || !Number.isFinite(psi) ? null : convertBoost(psi, unit)
}

/** TRIP chart rows with the three pressure series converted (other keys untouched). */
export function pressureChartRows<T extends Record<string, number | null>>(rows: T[], unit: BoostUnit): T[] {
  return rows.map((r) => ({
    ...r,
    boostTip: pressureIn(r.boostTip, unit),
    boostCan: pressureIn(r.boostCan, unit),
    boostLegacy: pressureIn(r.boostLegacy, unit),
  }))
}

/** Boost peak events in [unit] (other peak types untouched). */
export function pressurePeaks(peaks: PeakEvent[], unit: BoostUnit): PeakEvent[] {
  return peaks.map((p) => (p.type === 'boost' ? { ...p, value: convertBoost(p.value, unit) } : p))
}

/** One position per point (no reading / another source → null), so gaps and timing survive — never joined. */
export function pressureSeries(points: TripPoint[], source: BoostSource | null, unit: BoostUnit): (number | null)[] {
  return points.map((p) => pressureIn(boostFor(p, source), unit))
}

/** Title naming the measurement(s) a pressure chart shows. */
export function pressureTitle(keys: BoostKey[]): string {
  if (keys.length !== 1) return 'Pressure'
  return keys[0] === 'boostTip' ? 'TIP' : keys[0] === 'boostCan' ? 'Boost 0x0F8' : 'Boost — source not recorded'
}

/** Name of a single-source view (map legend, distribution): TIP / BOOST 0x0F8 / BOOST — SOURCE NOT RECORDED. */
export function sourceName(source: BoostSource | null): string {
  return source === 'TIP_033E' ? 'TIP' : source === 'CAN_0F8' ? 'BOOST 0x0F8' : 'BOOST — SOURCE NOT RECORDED'
}

/** Decimal places per unit for pressure labels. */
export const pressureDecimals = (unit: BoostUnit): number => (unit === 'bar' ? 2 : unit === 'kPa' ? 0 : 1)
