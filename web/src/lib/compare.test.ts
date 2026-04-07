import { describe, it, expect } from 'vitest'
import { computeDeltas, normalizePoints, resampleNormalized, sessionShortName } from './compare'
import type { TripSummary, TripPoint, Session } from '../types/session'

function makeSummary(overrides: Partial<TripSummary> = {}): TripSummary {
  return {
    distanceKm: 10, durationMs: 600000, fuelUsedL: 2, avgSpeedKph: 80,
    peakRpm: 6000, peakBoostPsi: 18, peakLatG: 0.8, peakLongG: 0.5,
    peakSpeedKph: 160, peakCoolantC: 95, peakOilTempC: 105,
    avgRpm: 3500, avgFuelEconomy: 8.5, modeBreakdown: {}, peakEvents: [],
    ...overrides,
  }
}

function makePoint(ts: number, overrides: Partial<TripPoint> = {}): TripPoint {
  return {
    ts, lat: 0, lng: 0, rpm: 3000, boostPsi: 10, speedKph: 80,
    coolantC: 90, oilTempC: 95, rduTempC: 60, ptuTempC: 55, ambientC: 20,
    latG: 0.3, longG: 0.1, fuelPct: 75,
    wheelSpeedFL: 80, wheelSpeedFR: 80, wheelSpeedRL: 80, wheelSpeedRR: 80,
    driveMode: 'NORMAL', awdTorqueL: 0, awdTorqueR: 0,
    gear: '3', throttlePct: 45,
    tirePressLF: 35, tirePressRF: 35, tirePressLR: 33, tirePressRR: 33,
    tireTempLF: 40, tireTempRF: 40, tireTempLR: 38, tireTempRR: 38,
    ...overrides,
  }
}

describe('computeDeltas', () => {
  it('returns deltas for all numeric KPIs', () => {
    const base = makeSummary({ peakRpm: 5000, peakSpeedKph: 140 })
    const comp = makeSummary({ peakRpm: 6000, peakSpeedKph: 160 })
    const deltas = computeDeltas(base, comp)

    const rpmDelta = deltas.find((d) => d.label === 'Peak RPM')
    expect(rpmDelta).toBeDefined()
    expect(rpmDelta!.baseValue).toBe(5000)
    expect(rpmDelta!.compValue).toBe(6000)
    expect(rpmDelta!.diff).toBe(1000)
    expect(rpmDelta!.pctChange).toBeCloseTo(20, 0)
    expect(rpmDelta!.higherIsBetter).toBe(true)
  })

  it('marks fuel economy as lower-is-better', () => {
    const base = makeSummary({ avgFuelEconomy: 10 })
    const comp = makeSummary({ avgFuelEconomy: 8 })
    const deltas = computeDeltas(base, comp)

    const fuelDelta = deltas.find((d) => d.label === 'Fuel Economy')
    expect(fuelDelta!.higherIsBetter).toBe(false)
    expect(fuelDelta!.diff).toBe(-2)
  })

  it('skips KPIs where both values are zero', () => {
    const base = makeSummary({ peakLongG: 0 })
    const comp = makeSummary({ peakLongG: 0 })
    const deltas = computeDeltas(base, comp)
    expect(deltas.find((d) => d.label === 'Peak Long G')).toBeUndefined()
  })

  it('handles non-finite values gracefully', () => {
    const base = makeSummary({ peakRpm: NaN })
    const comp = makeSummary({ peakRpm: 6000 })
    const deltas = computeDeltas(base, comp)
    expect(deltas.find((d) => d.label === 'Peak RPM')).toBeUndefined()
  })
})

describe('normalizePoints', () => {
  it('normalizes time to 0..1 range', () => {
    const points = [
      makePoint(1000),
      makePoint(2000),
      makePoint(3000),
    ]
    const norm = normalizePoints(points)

    expect(norm).toHaveLength(3)
    expect(norm[0].t).toBeCloseTo(0, 5)
    expect(norm[1].t).toBeCloseTo(0.5, 5)
    expect(norm[2].t).toBeCloseTo(1.0, 5)
  })

  it('preserves data values', () => {
    const points = [
      makePoint(1000, { rpm: 4000, boostPsi: 12, speedKph: 100 }),
      makePoint(2000, { rpm: 5000, boostPsi: 16, speedKph: 130 }),
    ]
    const norm = normalizePoints(points)

    expect(norm[0].rpm).toBe(4000)
    expect(norm[1].boostPsi).toBe(16)
    expect(norm[1].speedKph).toBe(130)
  })

  it('clamps sentinel coolant to 0', () => {
    const points = [
      makePoint(1000, { coolantC: -99 }),
      makePoint(2000, { coolantC: 90 }),
    ]
    const norm = normalizePoints(points)
    expect(norm[0].coolantC).toBe(0)
    expect(norm[1].coolantC).toBe(90)
  })

  it('clamps sentinel throttle to 0', () => {
    const points = [
      makePoint(1000, { throttlePct: -1 }),
      makePoint(2000, { throttlePct: 50 }),
    ]
    const norm = normalizePoints(points)
    expect(norm[0].throttlePct).toBe(0)
    expect(norm[1].throttlePct).toBe(50)
  })

  it('returns empty for single point', () => {
    expect(normalizePoints([makePoint(1000)])).toHaveLength(0)
  })

  it('returns empty for zero-duration session', () => {
    expect(normalizePoints([makePoint(1000), makePoint(1000)])).toHaveLength(0)
  })
})

describe('resampleNormalized', () => {
  it('resamples to the requested number of bins', () => {
    const norm = normalizePoints([
      makePoint(1000, { rpm: 2000 }),
      makePoint(2000, { rpm: 4000 }),
      makePoint(3000, { rpm: 6000 }),
    ])
    const resampled = resampleNormalized(norm, 5)

    expect(resampled).toHaveLength(5)
    expect(resampled[0].t).toBeCloseTo(0, 5)
    expect(resampled[4].t).toBeCloseTo(1.0, 5)
  })

  it('returns empty for empty input', () => {
    expect(resampleNormalized([], 10)).toHaveLength(0)
  })

  it('picks nearest neighbor for each bin', () => {
    const norm = normalizePoints([
      makePoint(1000, { rpm: 1000 }),
      makePoint(2000, { rpm: 5000 }),
      makePoint(3000, { rpm: 3000 }),
    ])
    const resampled = resampleNormalized(norm, 3)

    // At t=0, nearest is point 0 (rpm 1000)
    expect(resampled[0].rpm).toBe(1000)
    // At t=0.5, nearest is point 1 (rpm 5000)
    expect(resampled[1].rpm).toBe(5000)
    // At t=1.0, nearest is point 2 (rpm 3000)
    expect(resampled[2].rpm).toBe(3000)
  })
})

describe('sessionShortName', () => {
  it('returns short name as-is', () => {
    const session = { name: 'Track Day' } as Session
    expect(sessionShortName(session)).toBe('Track Day')
  })

  it('truncates names longer than 25 chars', () => {
    const session = { name: 'A very long session name that exceeds limit' } as Session
    const short = sessionShortName(session)
    expect(short.length).toBeLessThanOrEqual(25)
    expect(short).toContain('...')
  })

  it('returns exactly 25 chars without truncation', () => {
    const session = { name: 'Exactly25charsLong!!!!XY' } as Session
    expect(sessionShortName(session)).toBe('Exactly25charsLong!!!!XY')
  })
})
