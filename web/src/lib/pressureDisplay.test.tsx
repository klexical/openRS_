import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { Sparkline } from '../components/charts/Sparkline'
import {
  pressureIn, pressureChartRows, pressurePeaks, pressureSeries, pressureTitle, sourceName, primaryBoostSource,
} from './boost'
import { colorLegend } from './mapColors'
import type { TripPoint, PeakEvent } from '../types/session'

// #207 follow-up (2026-10-29): plotted pressure numbers follow the BAR / kPa preference (not only the labels), gaps and
// sample positions survive into the dashboard sparkline, and single-source views name their measurement.

const pt = (ts: number, psi: number | null, src: 'TIP_033E' | 'CAN_0F8' | null) =>
  ({ ts, lat: 0, lng: 0, rpm: 3000, boostPsi: psi, boostSource: src } as unknown as TripPoint)

describe('pressure in the selected unit', () => {
  it('a known pressure: 14.5038 psi = 1.00 bar = 100 kPa', () => {
    expect(pressureIn(14.5038, 'PSI')).toBeCloseTo(14.5038, 4)
    expect(pressureIn(14.5038, 'bar')).toBeCloseTo(1.0, 3)
    expect(pressureIn(14.5038, 'kPa')).toBeCloseTo(100.0, 1)
    expect(pressureIn(null, 'bar')).toBeNull()
  })

  it('chart rows, peak lines and map legend are converted, not just relabelled', () => {
    const rows = pressureChartRows([{ ts: 0, boostTip: 14.5038, boostCan: null, boostLegacy: null, rpm: 3000 }], 'kPa')
    expect(rows[0].boostTip).toBeCloseTo(100, 1)
    expect(rows[0].boostCan).toBeNull()
    expect(rows[0].rpm).toBe(3000)                                      // other series untouched
    const peaks = pressurePeaks([{ type: 'boost', value: 14.5038, ts: 0, lat: 0, lng: 0, source: 'TIP_033E' },
      { type: 'rpm', value: 6000, ts: 0, lat: 0, lng: 0 }] as PeakEvent[], 'bar')
    expect(peaks[0].value).toBeCloseTo(1.0, 3)
    expect(peaks[1].value).toBe(6000)
    expect(colorLegend('boost', 'bar').map((e) => e.label)).toEqual(['Vac', '<0.55', '0.55-1.10', '1.10+'])
    expect(colorLegend('boost', 'kPa').map((e) => e.label)).toEqual(['Vac', '<55', '55-110', '110+'])
  })
})

describe('positions and gaps survive', () => {
  const pts = [pt(0, 1, 'TIP_033E'), pt(1, 2, 'TIP_033E'), pt(2, 0, 'CAN_0F8'), pt(3, null, null), pt(4, 3, 'TIP_033E')]

  it('one value per sample — another source or no reading is null, never dropped', () => {
    expect(pressureSeries(pts, 'TIP_033E', 'PSI')).toEqual([1, 2, null, null, 3])
  })

  it('the sparkline breaks at the gap and keeps x positions (no joined line, no fill across it)', () => {
    const { container } = render(<Sparkline data={pressureSeries(pts, 'TIP_033E', 'PSI')} width={120} height={36} />)
    const paths = [...container.querySelectorAll('path')].map((p) => p.getAttribute('d') ?? '')
    const line = paths.find((d) => d.startsWith('M') && !d.includes('Z'))!
    expect(line.match(/M/g)).toHaveLength(2)                            // two sub-paths: [0,1] and [4]
    expect(line).toContain('M120.0')                                    // the last sample stays at the right edge (x = 4/4)
    expect(paths.some((d) => d.includes('Z') && d.length > 2)).toBe(false)
  })
  afterEach(cleanup)
})

describe('single-source views name the measurement', () => {
  it('TIP, CAN 0x0F8 or source not recorded — never just BOOST for TIP', () => {
    expect(pressureTitle(['boostTip'])).toBe('TIP')
    expect(pressureTitle(['boostCan'])).toBe('Boost 0x0F8')
    expect(pressureTitle(['boostLegacy'])).toBe('Boost — source not recorded')
    expect(pressureTitle(['boostTip', 'boostCan'])).toBe('Pressure')     // several: the chart legend names them
    expect(sourceName(primaryBoostSource([pt(0, 8, 'TIP_033E'), pt(1, 0, 'CAN_0F8')]))).toBe('TIP')
    expect(sourceName(primaryBoostSource([pt(0, 4, null)]))).toBe('BOOST — SOURCE NOT RECORDED')
  })
})
