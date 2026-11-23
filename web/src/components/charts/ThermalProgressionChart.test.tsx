import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import type { ThermalProgression } from '../../types/session'

// #498: the Thermal Progression chart's axis, peak labels and start-to-peak deltas follow the units setting and name the
// unit; a delta converts as a difference (× 1.8, no +32), as the app's `UserPrefs.displayTempDelta`. Invented curves.

const { useSettings } = await import('../../store/settings')
const { ThermalProgressionChart } = await import('./ThermalProgressionChart')

const curve = (startC: number, peakC: number, endC: number) => ({ startC, peakC, endC })
// The oil and coolant curves of the rc.6 TRACK fixture (import.androidExport.test.tsx, '1-track')
const DATA: ThermalProgression = { oil: curve(62, 131, 112), coolant: curve(58, 104, 96) }
const MONO_ADVANCE = 0.6   // JetBrains Mono: every glyph is 600 / 1000 em wide

function chart(unitSystem: 'metric' | 'imperial', data: ThermalProgression = DATA) {
  useSettings.setState({ unitSystem })
  const { container } = render(<ThermalProgressionChart data={data} />)
  const texts = [...container.querySelectorAll('text')]
  const svg = container.querySelector('svg')!
  const [, , width] = svg.getAttribute('viewBox')!.split(' ').map(Number)
  // y-axis tick labels are anchored "end" at the plot's left edge
  const axis = texts.filter((t) => t.getAttribute('text-anchor') === 'end').map((t) => t.textContent ?? '')
  const bold = texts.filter((t) => t.getAttribute('font-weight') === 'bold').map((t) => t.textContent ?? '')
  const deltaEls = texts.filter((t) => /^[+-]?\d+°/.test(t.textContent ?? '') && t.getAttribute('text-anchor') === 'start')
  return { texts, width, axis, peaks: bold, deltas: deltaEls.map((t) => t.textContent ?? ''), deltaEls }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); useSettings.setState({ unitSystem: 'metric' }) })

describe('#498 Thermal Progression follows the units setting', () => {
  it('imperial: peaks in °F, as the Thermal Record card prints them', () => {
    const { peaks } = chart('imperial')
    expect(peaks).toEqual(['268°F', '219°F'])          // 131 °C → 267.8 °F, 104 °C → 219.2 °F
  })

  it('imperial: a delta is a difference, × 1.8 with no +32', () => {
    const { deltas } = chart('imperial')
    expect(deltas).toEqual(['+124°F', '+83°F'])        // Δ 69 °C → 124.2 °F, Δ 46 °C → 82.8 °F
  })

  it('imperial: every axis tick is a °F value, named, on a scale that starts at a round ten of °F', () => {
    const { axis } = chart('imperial')
    expect(axis.length).toBe(5)
    for (const a of axis) expect(a).toMatch(/^-?\d+°F$/)
    const values = axis.map((a) => parseInt(a, 10))
    // the plot spans the converted readings: coolant start 58 °C = 136.4 °F, oil peak 131 °C = 267.8 °F
    expect(Math.min(...values)).toBeLessThanOrEqual(136)
    expect(Math.max(...values)).toBeGreaterThanOrEqual(268)
    expect(values[0] % 10).toBe(0)
  })

  it('metric: the same figures in °C, the unit named', () => {
    const { peaks, deltas, axis } = chart('metric')
    expect(peaks).toEqual(['131°C', '104°C'])
    expect(deltas).toEqual(['+69°C', '+46°C'])
    for (const a of axis) expect(a).toMatch(/^-?\d+°C$/)
  })

  it('a falling curve keeps its minus sign in either unit', () => {
    const cooling: ThermalProgression = { oil: curve(90, 85, 80), coolant: curve(-99, -99, -99) }
    expect(chart('imperial', cooling).deltas).toEqual(['-9°F'])   // Δ −5 °C → −9 °F
    cleanup()
    expect(chart('metric', cooling).deltas).toEqual(['-5°C'])
  })

  it('the widest delta label fits inside the chart, not clipped at its right edge', () => {
    const { deltaEls, width } = chart('imperial', { oil: curve(-20, 150, 140), coolant: curve(-99, -99, -99) })
    expect(deltaEls.map((t) => t.textContent)).toEqual(['+306°F'])
    for (const t of deltaEls) {
      const right = Number(t.getAttribute('x')) + (t.textContent ?? '').length * MONO_ADVANCE * Number(t.getAttribute('font-size'))
      expect(right).toBeLessThanOrEqual(width)
    }
  })
})
