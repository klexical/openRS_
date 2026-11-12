import type { FpsSample, SessionEvent } from '../types/session'

// An observer's note is a statement typed by a person in the car ("Cluster showed SPORT"), stamped on the diagnostic
// session clock by the app (session event type NOTE). It is evidence of what someone saw, never a measurement — every
// surface that shows one says so. The lines beside it (request, frame rate, gaps) are what the session itself recorded
// near that moment, so the two can be compared.

export const NOTE_EVENT_TYPE = 'NOTE'
/** Frame-rate samples and gap events this close to a note (either side) are shown beside it. */
export const NOTE_WINDOW_MS = 15_000
/** A request older than this is not tied to a note. */
export const REQUEST_LOOKBACK_MS = 60_000

export interface ObserverNote {
  /** ms on the diagnostic session clock */
  ts: number
  /** '' = marked without text */
  text: string
  /** false = the phone had no GPS fix: the note is in the session log only, not on the drive's map */
  onMap: boolean
}

export interface NoteContext {
  note: ObserverNote
  /** last drive-mode / ESC request at or before the note, within the look-back */
  lastRequest: { event: SessionEvent; beforeMs: number } | null
  /** frame-rate sample nearest in time, within the window */
  frames: FpsSample | null
  /** recording gaps logged within the window */
  gaps: SessionEvent[]
}

const NO_FIX = ' (no GPS fix, not on the map)'
const WITH_TEXT = 'Observer note: '
const NO_TEXT = 'Observer note (no text)'

export function observerNotes(events: SessionEvent[]): ObserverNote[] {
  return events.filter((e) => e.type === NOTE_EVENT_TYPE).map((e) => {
    const onMap = !e.message.endsWith(NO_FIX)
    const body = onMap ? e.message : e.message.slice(0, -NO_FIX.length)
    const text = body === NO_TEXT ? '' : body.startsWith(WITH_TEXT) ? body.slice(WITH_TEXT.length) : body
    return { ts: e.ts, text, onMap }
  })
}

export function noteHeading(note: Pick<ObserverNote, 'text'>): string {
  return note.text ? `Observer’s note: ${note.text}` : 'Observer’s note (no text)'
}

const isRequest = (e: SessionEvent) => e.type === 'DM_CMD' || e.type === 'ESC_CMD'
const isGap = (e: SessionEvent) => e.type === 'REC_GAP'

export function noteContext(
  note: ObserverNote,
  diag: { sessionEvents: SessionEvent[]; fpsTimeline: FpsSample[] },
): NoteContext {
  let request: SessionEvent | null = null
  for (const e of diag.sessionEvents) {
    if (isRequest(e) && e.ts <= note.ts && note.ts - e.ts <= REQUEST_LOOKBACK_MS && (!request || e.ts >= request.ts)) request = e
  }
  let frames: FpsSample | null = null
  for (const s of diag.fpsTimeline) {
    const d = Math.abs(s.ts - note.ts)
    if (d <= NOTE_WINDOW_MS && (!frames || d < Math.abs(frames.ts - note.ts))) frames = s
  }
  return {
    note,
    lastRequest: request ? { event: request, beforeMs: note.ts - request.ts } : null,
    frames,
    gaps: diag.sessionEvents.filter((e) => isGap(e) && Math.abs(e.ts - note.ts) <= NOTE_WINDOW_MS),
  }
}
