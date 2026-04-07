import { describe, it, expect } from 'vitest'
import { pointColor, colorLegend, peakMarkerStyle } from './mapColors'
import type { ColorMode } from './mapColors'
import { colors } from '../styles/tokens'
import type { TripPoint } from '../types/session'

/** Minimal TripPoint factory for testing color thresholds. */
function pt(overrides: Partial<TripPoint> = {}): TripPoint {
  return {
    ts: 0, lat: 0, lng: 0, rpm: 3000, boostPsi: 10, speedKph: 80,
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

describe('pointColor — speed mode', () => {
  it('<60 kph returns ok (green)', () => {
    expect(pointColor(pt({ speedKph: 30 }), 'speed')).toBe(colors.ok)
  })
  it('60-100 kph returns accent (cyan)', () => {
    expect(pointColor(pt({ speedKph: 80 }), 'speed')).toBe(colors.accent)
  })
  it('100-140 kph returns warn (yellow)', () => {
    expect(pointColor(pt({ speedKph: 120 }), 'speed')).toBe(colors.warn)
  })
  it('140+ kph returns orange', () => {
    expect(pointColor(pt({ speedKph: 180 }), 'speed')).toBe(colors.orange)
  })
})

describe('pointColor — mode', () => {
  it('Normal → accent', () => {
    expect(pointColor(pt({ driveMode: 'Normal' }), 'mode')).toBe(colors.accent)
  })
  it('Sport → warn', () => {
    expect(pointColor(pt({ driveMode: 'Sport' }), 'mode')).toBe(colors.warn)
  })
  it('Track → ok', () => {
    expect(pointColor(pt({ driveMode: 'Track' }), 'mode')).toBe(colors.ok)
  })
  it('Drift → orange', () => {
    expect(pointColor(pt({ driveMode: 'Drift' }), 'mode')).toBe(colors.orange)
  })
})

describe('pointColor — boost', () => {
  it('vacuum (<0) returns accent', () => {
    expect(pointColor(pt({ boostPsi: -5 }), 'boost')).toBe(colors.accent)
  })
  it('0-8 PSI returns ok', () => {
    expect(pointColor(pt({ boostPsi: 5 }), 'boost')).toBe(colors.ok)
  })
  it('8-16 PSI returns warn', () => {
    expect(pointColor(pt({ boostPsi: 12 }), 'boost')).toBe(colors.warn)
  })
  it('16+ PSI returns orange', () => {
    expect(pointColor(pt({ boostPsi: 20 }), 'boost')).toBe(colors.orange)
  })
})

describe('pointColor — latG', () => {
  it('<0.3g returns accent', () => {
    expect(pointColor(pt({ latG: 0.1 }), 'latG')).toBe(colors.accent)
  })
  it('0.3-0.6g returns ok', () => {
    expect(pointColor(pt({ latG: 0.4 }), 'latG')).toBe(colors.ok)
  })
  it('0.6-0.9g returns warn', () => {
    expect(pointColor(pt({ latG: 0.7 }), 'latG')).toBe(colors.warn)
  })
  it('0.9g+ returns orange', () => {
    expect(pointColor(pt({ latG: 1.0 }), 'latG')).toBe(colors.orange)
  })
  it('negative latG uses absolute value', () => {
    expect(pointColor(pt({ latG: -0.5 }), 'latG')).toBe(colors.ok)
  })
})

describe('pointColor — throttle', () => {
  it('<25% returns accent', () => {
    expect(pointColor(pt({ throttlePct: 10 }), 'throttle')).toBe(colors.accent)
  })
  it('25-50% returns ok', () => {
    expect(pointColor(pt({ throttlePct: 40 }), 'throttle')).toBe(colors.ok)
  })
  it('50-75% returns warn', () => {
    expect(pointColor(pt({ throttlePct: 60 }), 'throttle')).toBe(colors.warn)
  })
  it('75%+ returns orange', () => {
    expect(pointColor(pt({ throttlePct: 90 }), 'throttle')).toBe(colors.orange)
  })
})

describe('pointColor — oilTemp', () => {
  it('<90°C returns ok', () => {
    expect(pointColor(pt({ oilTempC: 80 }), 'oilTemp')).toBe(colors.ok)
  })
  it('90-110°C returns warn', () => {
    expect(pointColor(pt({ oilTempC: 100 }), 'oilTemp')).toBe(colors.warn)
  })
  it('110°C+ returns orange', () => {
    expect(pointColor(pt({ oilTempC: 115 }), 'oilTemp')).toBe(colors.orange)
  })
  it('sub-zero returns accent', () => {
    expect(pointColor(pt({ oilTempC: -5 }), 'oilTemp')).toBe(colors.accent)
  })
})

describe('colorLegend', () => {
  const allModes: ColorMode[] = ['speed', 'mode', 'boost', 'throttle', 'latG', 'oilTemp']

  it('returns non-empty legend for every mode', () => {
    for (const mode of allModes) {
      const legend = colorLegend(mode)
      expect(legend.length).toBeGreaterThan(0)
      for (const entry of legend) {
        expect(entry.label).toBeTruthy()
        expect(entry.color).toMatch(/^#/)
      }
    }
  })

  it('speed legend has 4 entries', () => {
    expect(colorLegend('speed')).toHaveLength(4)
  })

  it('oilTemp legend has 3 entries (no sub-zero band in legend)', () => {
    expect(colorLegend('oilTemp')).toHaveLength(3)
  })
})

describe('peakMarkerStyle', () => {
  it('rpm returns warn color, 0 decimals', () => {
    const s = peakMarkerStyle('rpm')
    expect(s.color).toBe(colors.warn)
    expect(s.decimals).toBe(0)
    expect(s.prefix).toBe('RPM')
  })

  it('boost returns accent color, 1 decimal', () => {
    const s = peakMarkerStyle('boost')
    expect(s.color).toBe(colors.accent)
    expect(s.decimals).toBe(1)
  })

  it('latG returns orange color, 2 decimals', () => {
    const s = peakMarkerStyle('latG')
    expect(s.color).toBe(colors.orange)
    expect(s.decimals).toBe(2)
  })

  it('speed returns frost color, 0 decimals', () => {
    const s = peakMarkerStyle('speed')
    expect(s.color).toBe(colors.frost)
    expect(s.decimals).toBe(0)
  })

  it('unknown type returns frost with 1 decimal', () => {
    const s = peakMarkerStyle('unknown')
    expect(s.color).toBe(colors.frost)
    expect(s.decimals).toBe(1)
  })
})
