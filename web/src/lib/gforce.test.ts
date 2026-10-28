import { describe, it, expect } from 'vitest'
import { gForceSamples } from './gforce'
import type { TripPoint } from '../types/session'

const pt = (latG: number, speedKph = 50): TripPoint => ({ latG, speedKph, driveMode: 'Normal' }) as TripPoint

describe('G-force scatter samples', () => {
  it('short drives keep every moving sample and drop standstill', () => {
    const out = gForceSamples([pt(0.2), pt(0, 0), pt(-0.3)])
    expect(out.map((s) => s.latG)).toEqual([0.2, -0.3])
  })

  it('long drives are downsampled but keep the left and right lateral-G peaks', () => {
    const points = Array.from({ length: 10001 }, (_, i) => pt(0.1 * Math.sin(i)))
    points[4567] = pt(1.31)     // off every stride
    points[8889] = pt(-1.12)
    const out = gForceSamples(points)
    expect(out.length).toBeLessThanOrEqual(2002)
    expect(out.length).toBeGreaterThan(1500)
    expect(Math.max(...out.map((s) => s.latG))).toBe(1.31)
    expect(Math.min(...out.map((s) => s.latG))).toBe(-1.12)
  })
})
