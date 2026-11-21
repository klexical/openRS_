import { describe, it, expect } from 'vitest'
import { summaryInRange } from './rangeSummary'
import type { TripPoint } from '../types/session'

// #416 ticket 2: the summary numbers follow the range. For a narrowed range they are worked out from the points inside
// it; the whole drive keeps the stored summary (null here). Every expected value is worked out by hand. Invented values.

const T0 = 4_000_000_000_000
const rows: [number, number, number, number, number, number | null, string | null][] = [
  // rpm,  kph, latG,  longG, coolant, boost, source
  [3000, 40, 0.1, 0.2, 88, null, null],
  [3400, 50, -0.6, 0.1, 89, 12.0, 'CAN_0F8'],
  [5200, 61, 0.3, -0.5, 91, 15.5, 'TIP_033E'],
  [4100, 70, 0.2, 0.3, -99, 14.0, 'CAN_0F8'],
  [2500, 30, 0, 0, 95, null, null],
]
const points = rows.map(([rpm, speedKph, latG, longG, coolantC, boostPsi, boostSource], i) => ({
  ts: T0 + i * 1000, lat: 10, lng: 20 + i * 0.001, rpm, speedKph, latG, longG, coolantC, oilTempC: -99,
  boostPsi, boostSource,
})) as unknown as TripPoint[]

describe('#416 ticket 2: the summary numbers in a range', () => {
  it('a range from 0:01 to 0:03: the three points inside it', () => {
    const s = summaryInRange(points, { from: 1000, to: 3000 }, 'CAN_0F8')!
    expect(s.durationMs).toBe(2000)
    expect(s.peakRpm).toBe(5200)
    expect(s.avgRpm).toBeCloseTo(4233.33, 2)                 // (3400 + 5200 + 4100) / 3
    expect(s.peakSpeedKph).toBe(70)
    expect(s.avgSpeedKph).toBeCloseTo(60.33, 2)              // (50 + 61 + 70) / 3
    expect(s.peakLatG).toBe(0.6)                             // the size, either side
    expect(s.peakLongG).toBe(0.5)
    expect(s.peakCoolantC).toBe(91)                          // the −99 marker is no reading
    expect(s.peakOilTempC).toBe(-99)                         // no reading at all: the marker, drawn as "—"
    expect(s.peakBoostPsi).toBe(14.0)                        // the stored peak's measurement only (#207): TIP's 15.5 is not it
    expect(s.peakBoostSource).toBe('CAN_0F8')
    // two steps of 0.001° of longitude at 10° N: 2 × 6371 km × 0.001 × π / 180 × cos 10° = 0.2190 km
    expect(s.distanceKm).toBeCloseTo(0.2190, 3)
    expect(s.distanceSource).toBe('estimate')                // a point sum, never the app's recorded figure
  })

  it('a range holding one point: no distance, and no boost reading of that measurement', () => {
    const s = summaryInRange(points, { from: 3500, to: 4000 }, 'CAN_0F8')!
    expect(s.durationMs).toBe(500)
    expect(s.distanceKm).toBe(0)
    expect(s.peakBoostPsi).toBeNull()
    expect(s.peakRpm).toBe(2500)
  })

  it('a range with no point in it: no figures (the page shows "—")', () => {
    const s = summaryInRange(points, { from: 1200, to: 1800 }, 'CAN_0F8')!
    expect(s.durationMs).toBe(600)
    expect(s.peakRpm).toBeNull()
    expect(s.avgSpeedKph).toBeNull()
    expect(s.peakBoostPsi).toBeNull()
  })

  it('the whole drive: none, so the stored summary stands', () => {
    expect(summaryInRange(points, null, 'CAN_0F8')).toBeNull()
  })
})

// #416 ticket 2: a point at 0/0 is a point without coordinates; it never enters
// a range's distance, and a range with no coordinates at all has no distance (the page shows "—").
describe('#416 ticket 2: the distance of a range and points without coordinates', () => {
  const at = (i: number, lat: number, lng: number) => ({ ts: T0 + i * 1000, lat, lng, rpm: 3000, speedKph: 50, latG: 0, longG: 0,
    coolantC: -99, oilTempC: -99, boostPsi: null, boostSource: null }) as unknown as TripPoint
  it('a 0/0 point between two positions adds nothing: the two positions\' own step only', () => {
    const pts = [at(0, 10, 20), at(1, 0, 0), at(2, 10, 20.001)]
    // one step of 0.001° of longitude at 10° N: 6371 km × 0.001 × π / 180 × cos 10° = 0.1095 km
    expect(summaryInRange(pts, { from: 0, to: 2000 }, null)!.distanceKm).toBeCloseTo(0.1095, 3)
  })

  it('a range whose points have no coordinates: no distance', () => {
    const pts = [at(0, 0, 0), at(1, 0, 0), at(2, 0, 0)]
    expect(summaryInRange(pts, { from: 0, to: 1000 }, null)!.distanceKm).toBeNull()
  })
})
