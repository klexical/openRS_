import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { DeltaCard } from './DeltaCard'
import type { KpiDelta } from '../../lib/compare'

afterEach(cleanup)

const card = (unit: string, diff: number, higherIsBetter = true) => render(<DeltaCard delta={{
  label: 'X', unit, baseValue: 100, compValue: 100 + diff, diff, pctChange: diff, higherIsBetter,
} satisfies KpiDelta} />).container.textContent

describe('DeltaCard neutral threshold', () => {
  it('differences below the unit\'s precision read as no change', () => {
    expect(card('RPM', 8)).toContain('—')
    expect(card('ms', -20, false)).toContain('—')
    expect(card('kph', 0.3)).toContain('—')
    expect(card('/100', 0.2)).toContain('—')
  })

  it('real differences still show direction and percentage', () => {
    expect(card('RPM', 250)).toContain('▲')
    expect(card('ms', -5000, false)).toContain('▼')
    expect(card('G', 0.02)).toContain('▲')
    expect(card('/100', -3)).toContain('▼')
  })
})
