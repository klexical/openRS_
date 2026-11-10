import { describe, it, expect } from 'vitest'
import { observerNotes, noteContext, noteHeading, NOTE_WINDOW_MS, REQUEST_LOOKBACK_MS } from './observerNotes'
import type { FpsSample, SessionEvent } from '../types/session'

// Synthetic session: every value below is made up for the test (no capture behind it).
const ev = (ts: number, type: string, message: string): SessionEvent => ({ ts, type, message })
const EVENTS: SessionEvent[] = [
  ev(1_000, 'STATE', 'Connected'),
  ev(20_000, 'DM_CMD', 'ACCEPTED by the adapter (Sport) — not yet confirmed'),
  ev(24_000, 'NOTE', 'Observer note: Cluster showed SPORT'),
  ev(70_000, 'REC_GAP', 'drive 1: LINK gap 6.0 s (link down)'),
  ev(74_000, 'NOTE', 'Observer note (no text) (no GPS fix, not on the map)'),
  ev(200_000, 'NOTE', 'Observer note: Cluster showed TRACK (no GPS fix, not on the map)'),
]
const FPS: FpsSample[] = [{ ts: 22_000, fps: 1480 }, { ts: 25_000, fps: 1510 }, { ts: 73_000, fps: 0 }]

describe('observer notes from the session events', () => {
  it('only NOTE events are notes; the text and the map flag are read from the app wording', () => {
    expect(observerNotes(EVENTS)).toEqual([
      { ts: 24_000, text: 'Cluster showed SPORT', onMap: true },
      { ts: 74_000, text: '', onMap: false },
      { ts: 200_000, text: 'Cluster showed TRACK', onMap: false },
    ])
  })

  it('an event of another type that happens to use the wording is not a note', () => {
    expect(observerNotes([ev(1, 'STATE', 'Observer note: x')])).toEqual([])
  })

  it('a NOTE event with unknown wording keeps its whole message as the text', () => {
    expect(observerNotes([ev(5, 'NOTE', 'something else')])).toEqual([{ ts: 5, text: 'something else', onMap: true }])
  })

  it('an export without events or with none of this type has no notes', () => {
    expect(observerNotes([])).toEqual([])
    expect(observerNotes(EVENTS.filter((e) => e.type !== 'NOTE'))).toEqual([])
  })

  it('the heading always says whose statement it is', () => {
    expect(noteHeading({ text: 'Cluster showed SPORT' })).toBe('Observer’s note: Cluster showed SPORT')
    expect(noteHeading({ text: '' })).toBe('Observer’s note (no text)')
  })
})

describe('what the session recorded around a note', () => {
  const diag = { sessionEvents: EVENTS, fpsTimeline: FPS }
  const [sport, blank, late] = observerNotes(EVENTS)

  it('names the last request before the note with its distance in time', () => {
    const c = noteContext(sport, diag)
    expect(c.lastRequest?.event.type).toBe('DM_CMD')
    expect(c.lastRequest?.beforeMs).toBe(4_000)
  })

  it('takes the frame-rate sample nearest in time', () => {
    expect(noteContext(sport, diag).frames).toEqual({ ts: 25_000, fps: 1510 })
    expect(noteContext(blank, diag).frames).toEqual({ ts: 73_000, fps: 0 })
  })

  it('lists the gaps logged within the window, before or after', () => {
    expect(noteContext(sport, diag).gaps).toEqual([])
    expect(noteContext(blank, diag).gaps.map((g) => g.ts)).toEqual([70_000])
    const after = noteContext({ ts: 70_000 - NOTE_WINDOW_MS, text: '', onMap: true }, diag)
    expect(after.gaps.map((g) => g.ts)).toEqual([70_000])
  })

  it('a request older than the look-back is not tied to the note', () => {
    expect(noteContext(blank, diag).lastRequest?.beforeMs).toBe(54_000)
    const far = noteContext({ ts: 20_000 + REQUEST_LOOKBACK_MS + 1, text: '', onMap: true }, diag)
    expect(far.lastRequest).toBeNull()
  })

  it('a request after the note is never the request before it', () => {
    expect(noteContext({ ts: 19_999, text: '', onMap: true }, diag).lastRequest).toBeNull()
  })

  it('with no sample or event near, each part is absent, never a guess', () => {
    const c = noteContext(late, diag)
    expect(c).toEqual({ note: late, lastRequest: null, frames: null, gaps: [] })
  })

  it('an ESC request counts as a request', () => {
    const c = noteContext({ ts: 10, text: '', onMap: true }, { sessionEvents: [ev(5, 'ESC_CMD', 'x')], fpsTimeline: [] })
    expect(c.lastRequest?.event.type).toBe('ESC_CMD')
  })
})
