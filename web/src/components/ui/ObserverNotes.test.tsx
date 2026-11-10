import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { ObserverNotesSection, eventTypeLabel } from './ObserverNotes'
import type { FpsSample, SessionEvent } from '../../types/session'

afterEach(cleanup)

// Synthetic session (made-up values).
const EVENTS: SessionEvent[] = [
  { ts: 20_000, type: 'DM_CMD', message: 'ACCEPTED by the adapter (Sport)' },
  { ts: 24_000, type: 'NOTE', message: 'Observer note: Cluster showed SPORT' },
  { ts: 70_000, type: 'REC_GAP', message: 'drive 1: LINK gap 6.0 s (link down)' },
  { ts: 74_000, type: 'NOTE', message: 'Observer note (no text) (no GPS fix, not on the map)' },
]
const FPS: FpsSample[] = [{ ts: 25_000, fps: 1510 }]

describe('observer notes section', () => {
  it('renders nothing for a session without notes (an older export)', () => {
    const { container } = render(<ObserverNotesSection sessionEvents={EVENTS.filter((e) => e.type !== 'NOTE')} fpsTimeline={FPS} />)
    expect(container.textContent).toBe('')
  })

  it('labels each row as an observer’s note and puts the request, frame rate and gaps beside it', () => {
    const { container } = render(<ObserverNotesSection sessionEvents={EVENTS} fpsTimeline={FPS} />)
    const text = container.textContent ?? ''
    expect(text).toContain('Observer’s notes (2)')
    expect(text).toContain('not a measurement')
    const rows = [...container.querySelectorAll('tbody tr')].map((r) => [...r.querySelectorAll('td')].map((c) => c.textContent))
    expect(rows).toHaveLength(2)
    expect(rows[0]).toEqual([
      '0:24', 'Observer’s note: Cluster showed SPORT',
      'DM_CMD 4.0 s before: ACCEPTED by the adapter (Sport)', '1510 fps (1.0 s after)', 'none logged within 15 s',
    ])
    expect(rows[1]).toEqual([
      '1:14', 'Observer’s note (no text) · not on the map (no GPS fix)',
      'DM_CMD 54.0 s before: ACCEPTED by the adapter (Sport)', 'no sample within 15 s', '4.0 s before: drive 1: LINK gap 6.0 s (link down)',
    ])
  })

  it('the events table names the type in words', () => {
    expect(eventTypeLabel('NOTE')).toBe('OBSERVER NOTE')
    expect(eventTypeLabel('DM_CMD')).toBe('DM_CMD')
  })
})
