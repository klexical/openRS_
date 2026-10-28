import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { Session, TripPoint } from '../../types/session'

// Capture the rows handed to Recharts (jsdom has no layout, so ResponsiveContainer would render nothing).
const seen = vi.hoisted(() => ({ data: [] as Record<string, number | null>[] }))
vi.mock('recharts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('recharts')>()),
  ResponsiveContainer: ({ children }: { children: ReactNode }) => <>{children}</>,
  LineChart: ({ data }: { data: Record<string, number | null>[] }) => { seen.data = data; return null },
}))
const { ComparisonChart } = await import('./ComparisonChart')

afterEach(cleanup)

const pt = (ts: number, coolantC: number, throttlePct: number) => ({ ts, coolantC, oilTempC: 90, throttlePct, rpm: 0 }) as TripPoint
const session = (id: string, points: TripPoint[]) => ({ id, name: id, trip: { points } }) as unknown as Session

describe('ComparisonChart missing readings', () => {
  // Session A: coolant not yet received for the first half, then 0 °C (a real cold reading), then warm.
  const a = session('a', [pt(0, -99, -1), pt(1000, -99, -1), pt(2000, 0, 0), pt(3000, 80, 40)])
  const b = session('b', [pt(0, 20, 10), pt(3000, 90, 50)])

  it('a missing temperature is null in the overlay rows (a gap), never a fabricated 0', () => {
    render(<ComparisonChart sessions={[a, b]} dataKey="coolantC" label="Coolant" />)
    expect(seen.data).toHaveLength(200)
    expect(seen.data[0]).toMatchObject({ s0: null, s1: 20 })
    expect(seen.data[199]).toMatchObject({ s0: 80, s1: 90 })
    const s0 = seen.data.map((r) => r.s0)
    expect(s0.filter((v) => v === null).length).toBeGreaterThan(0)
    expect(s0).toContain(0)                       // the genuine 0 °C reading is kept
  })

  it('missing throttle is null; 0 % stays 0', () => {
    render(<ComparisonChart sessions={[a, b]} dataKey="throttlePct" label="Throttle" />)
    const s0 = seen.data.map((r) => r.s0)
    expect(s0[0]).toBeNull()
    expect(s0).toContain(0)
    expect(s0[199]).toBe(40)
  })

  it('readings that are never missing are unchanged (rpm 0 stays 0)', () => {
    render(<ComparisonChart sessions={[a, b]} dataKey="rpm" label="RPM" />)
    expect(seen.data.every((r) => r.s0 === 0 && r.s1 === 0)).toBe(true)
  })
})
