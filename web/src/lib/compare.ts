import type { Session, TripData, TripSummary, TripPoint } from '../types/session'
import { tempReading, pctReading } from './readings'
import { boostFor, boostLabel, primaryBoostSource, type BoostSource } from './boost'
import { hasPosition } from './distance'

/**
 * One card of Compare: a figure of two drives (the base and one other) and their difference. A drive without the reading
 * has no figure (null; the card draws "—"), and then the pair has no difference or percentage (#416 ticket 3).
 */
export interface KpiDelta {
  label: string
  unit: string
  /** Value from first (baseline) session; null when it has no reading. */
  baseValue: number | null
  /** Value from second session; null when it has no reading. */
  compValue: number | null
  /** Absolute difference (comp - base); null unless both drives have the reading. */
  diff: number | null
  /** Percentage change; null unless both drives have the reading. */
  pctChange: number | null
  /** true = higher is better (speed, boost); false = lower is better (fuel economy). */
  higherIsBetter: boolean
}

/** Normalized point — time expressed as 0..1 fraction of session duration. Missing readings are null (a gap). */
export interface NormalizedPoint {
  /** 0..1 fraction through the session. */
  t: number
  rpm: number
  /** Only the session's primary boost measurement (lib/boost.ts); other sources / no reading → null (a gap). */
  boostPsi: number | null
  speedKph: number
  coolantC: number | null
  oilTempC: number | null
  latG: number
  longG: number
  throttlePct: number | null
  brakePressure: number
  steeringAngle: number
}

/**
 * Compare's cards for two trip summaries. A figure is a reading only when the drive received it: a field an older record
 * lacks, a value that is not a finite number, the import's −99 temperature marker (#414), a fuel used or fuel economy of
 * 0 (the import's "could not compute") and an absent aggression score are no reading. A card stays when one drive has the reading, with
 * no figure for the other and no difference (#416 ticket 3); a card with no reading on either side, or with 0
 * on both, is left out.
 */
export function computeDeltas(base: TripSummary, comp: TripSummary): KpiDelta[] {
  const kpis: { key: keyof TripSummary; label: string; unit: string; higherIsBetter: boolean; reading?: (v: number) => number | null }[] = [
    { key: 'peakSpeedKph', label: 'Peak Speed', unit: 'kph', higherIsBetter: true },
    { key: 'avgSpeedKph', label: 'Avg Speed', unit: 'kph', higherIsBetter: true },
    { key: 'peakRpm', label: 'Peak RPM', unit: 'RPM', higherIsBetter: true },
    { key: 'avgRpm', label: 'Avg RPM', unit: 'RPM', higherIsBetter: true },
    { key: 'peakLatG', label: 'Peak Lat G', unit: 'G', higherIsBetter: true },
    { key: 'peakLongG', label: 'Peak Long G', unit: 'G', higherIsBetter: false },
    { key: 'distanceKm', label: 'Distance', unit: 'km', higherIsBetter: true },
    { key: 'durationMs', label: 'Duration', unit: 'ms', higherIsBetter: false },
    { key: 'avgFuelEconomy', label: 'Fuel Economy', unit: 'L/100km', higherIsBetter: false, reading: positive },
    { key: 'peakCoolantC', label: 'Peak Coolant', unit: '°C', higherIsBetter: false, reading: tempReading },   // #414
    { key: 'peakOilTempC', label: 'Peak Oil', unit: '°C', higherIsBetter: false, reading: tempReading },
    { key: 'aggressionScore', label: 'Aggression', unit: '/100', higherIsBetter: true, reading: positive },   // T4A
    { key: 'fuelUsedL', label: 'Fuel Used', unit: 'L', higherIsBetter: false, reading: positive },   // T4A
  ]

  return kpis
    .map(({ key, label, unit, higherIsBetter, reading }) => {
      const read = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? (reading ? reading(v) : v) : null)
      const card = pair(label, unit, higherIsBetter, read(base[key]), read(comp[key]))
      return card && card.baseValue === 0 && card.compValue === 0 ? null : card
    })
    .filter((d): d is KpiDelta => d !== null)
    .concat(boostDelta(base, comp))
}

/**
 * Compare's cards for two drives (the page's call). A drive whose points have no coordinates never received a distance:
 * its summary's 0 is the sum of 0/0 points, so it has no distance reading here (#416). Read from
 * the points, so it holds for drives stored before as well.
 */
export function compareCards(base: TripData, comp: TripData): KpiDelta[] {
  return computeDeltas(withDistanceReading(base), withDistanceReading(comp))
}
function withDistanceReading(trip: TripData): TripSummary {
  return trip.points.some(hasPosition) ? trip.summary : { ...trip.summary, distanceKm: NaN }   // NaN: no reading
}

/** A value only above 0: the import stores 0 (or nothing) for a fuel figure or aggression score it could not compute. */
const positive = (v: number) => (v > 0 ? v : null)

/** One card from two readings (null = no reading): none when neither has it, no difference unless both have it. */
function pair(label: string, unit: string, higherIsBetter: boolean, baseValue: number | null, compValue: number | null): KpiDelta | null {
  if (baseValue === null && compValue === null) return null
  if (baseValue === null || compValue === null) return { label, unit, baseValue, compValue, diff: null, pctChange: null, higherIsBetter }
  const diff = compValue - baseValue
  const pctChange = baseValue !== 0 ? (diff / Math.abs(baseValue)) * 100 : 0
  return { label, unit, baseValue, compValue, diff, pctChange, higherIsBetter }
}

/**
 * Peak boost card (#207): two peaks side by side only when they are the same measurement (both TIP, both CAN 0x0F8, or
 * both from exports without sources); a genuine 0 peak compares. A drive with no boost reading keeps the card with no
 * figure for it, named by the other drive's measurement (#416 ticket 3).
 */
function boostDelta(base: TripSummary, comp: TripSummary): KpiDelta[] {
  const value = (v: number | null | undefined) => (v != null && Number.isFinite(v) ? v : null)
  const a = value(base.peakBoostPsi), b = value(comp.peakBoostPsi)
  if (a !== null && b !== null && (base.peakBoostSource ?? null) !== (comp.peakBoostSource ?? null)) return []
  const source = a !== null ? base.peakBoostSource : comp.peakBoostSource
  const card = pair(`Peak ${boostLabel(source) === 'TIP' ? 'TIP' : 'Boost'}`, 'PSI', true, a, b)
  return card ? [card] : []
}

/** The boost measurement an overlay of these sessions can show, or undefined when they differ (#207). */
export function overlayBoostSource(sessions: Session[]): BoostSource | null | undefined {
  const sources = sessions.map((s) => primaryBoostSource(s.trip?.points ?? []))
  return sources.every((x) => x === sources[0]) ? sources[0] : undefined
}

/** Normalize trip points to 0..1 time fraction for overlay comparison. Sentinel temps / throttle → null, genuine zeros kept. */
export function normalizePoints(points: TripPoint[]): NormalizedPoint[] {
  if (points.length < 2) return []
  const t0 = points[0].ts
  const duration = points[points.length - 1].ts - t0
  if (duration <= 0) return []

  const boostSource = primaryBoostSource(points)
  return points.map((p) => ({
    t: (p.ts - t0) / duration,
    rpm: p.rpm,
    boostPsi: boostFor(p, boostSource),
    speedKph: p.speedKph,
    coolantC: tempReading(p.coolantC),
    oilTempC: tempReading(p.oilTempC),
    latG: p.latG,
    longG: p.longG,
    throttlePct: pctReading(p.throttlePct),
    brakePressure: p.brakePressure,
    steeringAngle: p.steeringAngle,
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
    let best = points[0]
    let bestDist = Math.abs(best.t - targetT)
    for (let j = 1; j < points.length; j++) {
      const dist = Math.abs(points[j].t - targetT)
      if (dist < bestDist) {
        best = points[j]
        bestDist = dist
      } else {
        break
      }
    }
    result.push({ ...best, t: targetT })
  }
  return result
}

/** Get a display-friendly short name for a session. */
export function sessionShortName(session: Session): string {
  const name = session.name
  return name.length > 25 ? name.slice(0, 22) + '...' : name
}
