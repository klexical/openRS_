import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { ModeTimeline } from './ModeTimeline'
import type { TripPoint } from '../../types/session'

afterEach(cleanup)

const pt = (ts: number, driveMode: string) => ({ ts, driveMode }) as TripPoint
const legend = (c: HTMLElement) => [...c.querySelectorAll('.flex.gap-4 > span')].map((s) => s.textContent)

describe('ModeTimeline drive-mode casing', () => {
  it('"Normal" / "NORMAL" / "normal" are one segment and one legend entry', () => {
    const { container } = render(<ModeTimeline points={[pt(0, 'Normal'), pt(10_000, 'NORMAL'), pt(20_000, 'Sport'), pt(30_000, 'sport'), pt(40_000, 'normal')]} />)
    expect(legend(container)).toEqual(['NORMAL 20s', 'SPORT 20s'])
    expect(container.textContent).toContain('2 transitions')
  })

  it('the profile timeline is normalised the same way', () => {
    const { container } = render(<ModeTimeline points={[]} canonicalTimeline={[
      { mode: 'Normal', startMs: 0, endMs: 5000 }, { mode: 'TRACK', startMs: 5000, endMs: 9000 }, { mode: 'NORMAL', startMs: 9000, endMs: 10_000 },
    ]} />)
    expect(legend(container)).toEqual(['NORMAL 6s', 'TRACK 4s'])
  })
})
