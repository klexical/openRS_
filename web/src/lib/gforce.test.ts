import { describe, it, expect } from 'vitest'
import { gForceSamples, modeDotColor, modeLegend } from './gforce'
import { colors, modeColors } from '../styles/tokens'
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

// #516: the app stores "--" while it does not know the drive mode yet (Sport or Track before the first 0x420)
describe('G-force dots coloured by mode', () => {
  it('each known mode keeps its colour, whatever the casing', () => {
    expect(['Normal', 'NORMAL', 'Sport', 'Track', 'Drift'].map(modeDotColor))
      .toEqual([modeColors.normal, modeColors.normal, modeColors.sport, modeColors.track, modeColors.drift])
  })

  it('"--" is dim, never drawn as Normal', () => {
    expect(modeDotColor('--')).toBe(colors.dim)
  })

  it('the legend names each mode in its dot colour, "--" last', () => {
    expect(modeLegend.map((e) => e.label)).toEqual(['Normal', 'Sport', 'Track', 'Drift', '-- not known yet'])
    for (const e of modeLegend) expect(e.color).toBe(modeDotColor(e.label === '-- not known yet' ? '--' : e.label))
  })
})
