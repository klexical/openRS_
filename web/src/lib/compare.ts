import type { Session, TripSummary, TripPoint } from '../types/session'

/** A single KPI delta between two sessions. */
export interface KpiDelta {
  label: string
  unit: string
  /** Value from first (baseline) session. */
  baseValue: number
  /** Value from second session. */
  compValue: number
  /** Absolute difference (comp - base). */
  diff: number
  /** Percentage change. */
  pctChange: number
  /** true = higher is better (speed, boost); false = lower is better (fuel economy). */
  higherIsBetter: boolean
}

/** Normalized point — time expressed as 0..1 fraction of session duration. */
export interface NormalizedPoint {
  /** 0..1 fraction through the session. */
  t: number
  rpm: number
  boostPsi: number
  speedKph: number
  coolantC: number
  latG: number
  throttlePct: number
}

/** Compute KPI deltas between two trip summaries. */
export function computeDeltas(base: TripSummary, comp: TripSummary): KpiDelta[] {
  const kpis: { key: keyof TripSummary; label: string; unit: string; higherIsBetter: boolean }[] = [
    { key: 'peakSpeedKph', label: 'Peak Speed', unit: 'kph', higherIsBetter: true },
    { key: 'avgSpeedKph', label: 'Avg Speed', unit: 'kph', higherIsBetter: true },
    { key: 'peakRpm', label: 'Peak RPM', unit: 'RPM', higherIsBetter: true },
    { key: 'avgRpm', label: 'Avg RPM', unit: 'RPM', higherIsBetter: true },
    { key: 'peakBoostPsi', label: 'Peak Boost', unit: 'PSI', higherIsBetter: true },
    { key: 'peakLatG', label: 'Peak Lat G', unit: 'G', higherIsBetter: true },
    { key: 'peakLongG', label: 'Peak Long G', unit: 'G', higherIsBetter: false },
    { key: 'distanceKm', label: 'Distance', unit: 'km', higherIsBetter: true },
    { key: 'durationMs', label: 'Duration', unit: 'ms', higherIsBetter: false },
    { key: 'avgFuelEconomy', label: 'Fuel Economy', unit: 'L/100km', higherIsBetter: false },
    { key: 'peakCoolantC', label: 'Peak Coolant', unit: '°C', higherIsBetter: false },
    { key: 'peakOilTempC', label: 'Peak Oil', unit: '°C', higherIsBetter: false },
  ]

  return kpis
    .map(({ key, label, unit, higherIsBetter }) => {
      const baseValue = base[key] as number
      const compValue = comp[key] as number
      if (!isFinite(baseValue) || !isFinite(compValue)) return null
      if (baseValue === 0 && compValue === 0) return null
      const diff = compValue - baseValue
      const pctChange = baseValue !== 0 ? (diff / Math.abs(baseValue)) * 100 : 0
      return { label, unit, baseValue, compValue, diff, pctChange, higherIsBetter }
    })
    .filter(Boolean) as KpiDelta[]
}

/** Normalize trip points to 0..1 time fraction for overlay comparison. */
export function normalizePoints(points: TripPoint[]): NormalizedPoint[] {
  if (points.length < 2) return []
  const t0 = points[0].ts
  const duration = points[points.length - 1].ts - t0
  if (duration <= 0) return []

  return points.map((p) => ({
    t: (p.ts - t0) / duration,
    rpm: p.rpm,
    boostPsi: p.boostPsi,
    speedKph: p.speedKph,
    coolantC: p.coolantC > -90 ? p.coolantC : 0,
    latG: p.latG,
    throttlePct: p.throttlePct >= 0 ? p.throttlePct : 0,
  }))
}

/**
 * Resample normalized points to a fixed number of bins for overlay alignment.
 * Uses nearest-neighbor sampling for simplicity.
 */
export function resampleNormalized(points: NormalizedPoint[], bins = 200): NormalizedPoint[] {
  if (points.length === 0) return []
  const result: NormalizedPoint[] = []
  for (let i = 0; i < bins; i++) {
    const targetT = i / (bins - 1)
    // Find closest point
    let best = points[0]
    let bestDist = Math.abs(best.t - targetT)
    for (let j = 1; j < points.length; j++) {
      const dist = Math.abs(points[j].t - targetT)
      if (dist < bestDist) {
        best = points[j]
        bestDist = dist
      } else {
        break // points are sorted by t, so once distance increases we're past the closest
      }
    }
    result.push({ ...best, t: targetT })
  }
  return result
}

/** Get a display-friendly short name for a session. */
export function sessionShortName(session: Session): string {
  // Truncate to first 25 chars
  const name = session.name
  return name.length > 25 ? name.slice(0, 22) + '...' : name
}
