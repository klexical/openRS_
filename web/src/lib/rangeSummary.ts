// The summary numbers of a narrowed range (#416 ticket 2: "the summary numbers follow the range"), worked out from the
// points inside it. The whole drive keeps the stored summary, as the import or the app's profile made it. A reading a
// point does not have takes no part (#389: never 0); a figure with no reading in the range is null (the page shows "—").
import type { TripPoint } from '../types/session'
import { boostFor, type BoostSource } from './boost'
import { haversine } from './distance'
import { numReading, tempReading } from './readings'
import { pointsInRange, type TimeRange } from './selection'

export interface RangeSummary {
  /** The range's own length. */
  durationMs: number
  /** A sum of straight lines between the points inside the range: an estimate, never the app's recorded figure (#284). */
  distanceKm: number
  distanceSource: 'estimate'
  avgSpeedKph: number | null
  avgRpm: number | null
  peakRpm: number | null
  peakSpeedKph: number | null
  /** The largest size either side, as the import's peaks. */
  peakLatG: number | null
  peakLongG: number | null
  /** −99, the import's "no reading" marker, when no point in the range has one (drawn as "—"). */
  peakCoolantC: number
  peakOilTempC: number
  /** Only the stored peak's measurement (#207); null with no reading of it in the range. */
  peakBoostPsi: number | null
  peakBoostSource: BoostSource | null
}

/** The summary of [range] over [points], boost read as [boostSource]; null for the whole drive (the stored summary stands). */
export function summaryInRange(points: TripPoint[], range: TimeRange | null, boostSource: BoostSource | null): RangeSummary | null {
  if (!range) return null
  const inside = pointsInRange(points, range)
  const values = (f: (p: TripPoint) => number | null) => inside.map(f).filter((v): v is number => v !== null)
  const max = (v: number[]) => (v.length > 0 ? Math.max(...v) : null)
  const mean = (v: number[]) => (v.length > 0 ? v.reduce((a, b) => a + b, 0) / v.length : null)
  let distanceKm = 0
  for (let i = 1; i < inside.length; i++) distanceKm += haversine(inside[i - 1].lat, inside[i - 1].lng, inside[i].lat, inside[i].lng)
  const rpm = values((p) => numReading(p.rpm))
  const speed = values((p) => numReading(p.speedKph))
  const latG = values((p) => numReading(p.latG)).map(Math.abs)
  const longG = values((p) => numReading(p.longG)).map(Math.abs)
  return {
    durationMs: range.to - range.from,
    distanceKm,
    distanceSource: 'estimate',
    avgSpeedKph: mean(speed),
    avgRpm: mean(rpm),
    peakRpm: max(rpm),
    peakSpeedKph: max(speed),
    peakLatG: max(latG),
    peakLongG: max(longG),
    peakCoolantC: max(values((p) => tempReading(p.coolantC))) ?? -99,
    peakOilTempC: max(values((p) => tempReading(p.oilTempC))) ?? -99,
    peakBoostPsi: max(values((p) => boostFor(p, boostSource))),
    peakBoostSource: boostSource,
  }
}
