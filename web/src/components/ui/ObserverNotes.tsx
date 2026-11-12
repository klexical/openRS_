import { SectionLabel } from './SectionLabel'
import {
  NOTE_EVENT_TYPE, NOTE_WINDOW_MS, REQUEST_LOOKBACK_MS, noteContext, noteHeading, observerNotes,
} from '../../lib/observerNotes'
import type { FpsSample, SessionEvent } from '../../types/session'

/** Type column of the session events table: a NOTE is named in words. */
// eslint-disable-next-line react-refresh/only-export-components
export function eventTypeLabel(type: string): string {
  return type === NOTE_EVENT_TYPE ? 'OBSERVER NOTE' : type
}

function relTime(ms: number): string {
  const sec = Math.floor(ms / 1000)
  return `${Math.floor(sec / 60)}:${(sec % 60).toString().padStart(2, '0')}`
}

/** "4.0 s before" / "1.0 s after" / "at the note" for an offset from the note (negative = before). */
function offset(ms: number): string {
  if (ms === 0) return 'at the note'
  return `${(Math.abs(ms) / 1000).toFixed(1)} s ${ms < 0 ? 'before' : 'after'}`
}

/**
 * Observer's notes beside what the session recorded around each one: the last request, the frame rate and any
 * recording gap. Renders nothing when the session has no notes (every export made before the notes existed).
 */
export function ObserverNotesSection({ sessionEvents, fpsTimeline }: { sessionEvents: SessionEvent[]; fpsTimeline: FpsSample[] }) {
  const notes = observerNotes(sessionEvents)
  if (notes.length === 0) return null
  const rows = notes.map((n) => noteContext(n, { sessionEvents, fpsTimeline }))
  const windowS = NOTE_WINDOW_MS / 1000
  return (
    <>
      <SectionLabel>Observer’s notes ({notes.length})</SectionLabel>
      <p className="text-xs text-dim mb-2">
        A note is what a person in the car typed at that moment. It is not a measurement. The other columns are what the
        session recorded near it.
      </p>
      <div className="rounded-lg border border-brd bg-surf2 overflow-x-auto">
        <table className="w-full text-xs font-mono">
          <thead className="bg-surf3">
            <tr className="text-left text-dim uppercase tracking-wider">
              <th className="px-3 py-2 w-16">Time</th>
              <th className="px-3 py-2">Note</th>
              <th className="px-3 py-2">Last request</th>
              <th className="px-3 py-2">Frames received</th>
              <th className="px-3 py-2">Recording gaps</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c, i) => (
              <tr key={i} className="border-t border-brd align-top">
                <td className="px-3 py-1.5 text-dim">{relTime(c.note.ts)}</td>
                <td className="px-3 py-1.5 text-frost">
                  {noteHeading(c.note)}{c.note.onMap ? '' : ' · not on the map (no GPS fix)'}
                </td>
                <td className="px-3 py-1.5 text-frost">
                  {c.lastRequest
                    ? `${c.lastRequest.event.type} ${offset(-c.lastRequest.beforeMs)}: ${c.lastRequest.event.message}`
                    : `none in the ${REQUEST_LOOKBACK_MS / 1000} s before`}
                </td>
                <td className="px-3 py-1.5 text-frost">
                  {c.frames
                    ? `${Math.round(c.frames.fps)} fps (${offset(c.frames.ts - c.note.ts)})`
                    : `no sample within ${windowS} s`}
                </td>
                <td className="px-3 py-1.5 text-frost">
                  {c.gaps.length > 0
                    ? c.gaps.map((g) => `${offset(g.ts - c.note.ts)}: ${g.message}`).join('; ')
                    : `none logged within ${windowS} s`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
