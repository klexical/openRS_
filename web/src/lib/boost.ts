import type { TripPoint } from '../types/session'

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
