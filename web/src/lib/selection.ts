// The selection of a drive's page (#416 ticket 2; spec #389, "One selection module owns the time range and the cursor").
// A range is milliseconds from the drive's first point, both ends included; null is the whole drive. The cursor is one
// moment, in the same units, or null. This file holds the arithmetic (pure) and the only writers of the selection's state
// in the store; the address reads it and writes it through here (App.tsx, components/drive/).
import { useStore } from '../store'
import { currentAddress, go } from './address'

export interface TimeRange { from: number; to: number }

/** A drive's span: its first point to its last. Null with fewer than two points, or none apart: such a drive has no range. */
export function driveSpanMs(points: readonly { ts: number }[]): number | null {
  if (points.length < 2) return null
  const span = points[points.length - 1].ts - points[0].ts
  return span > 0 ? span : null
}

/**
 * [range] clamped to a drive of [spanMs]. The whole drive (null) when there is no range, no span, nothing of it inside the
 * drive, or when it covers the whole drive: so "whole drive" is always one value.
 */
export function clampRange(range: TimeRange | null, spanMs: number | null): TimeRange | null {
  if (!range || spanMs === null) return null
  const from = Math.max(0, range.from), to = Math.min(spanMs, range.to)
  if (to <= from) return null
  if (from === 0 && to === spanMs) return null
  return { from, to }
}

/**
 * The range of a performance run, clamped to the drive whose first point is at [t0]. The export gives a run no time yet
 * (#416, the integrator's draft issue on the run's window): nothing calls this until a run carries one (ticket 6).
 */
export function runRange(run: { startTs: number; endTs: number }, t0: number, spanMs: number | null): TimeRange | null {
  return clampRange({ from: run.startTs - t0, to: run.endTs - t0 }, spanMs)
}

/** The points inside [range] (both ends included), timed from the first point; the whole drive is every point. */
export function pointsInRange<P extends { ts: number }>(points: P[], range: TimeRange | null): P[] {
  if (!range || points.length === 0) return points
  const t0 = points[0].ts
  return points.filter((p) => p.ts - t0 >= range.from && p.ts - t0 <= range.to)
}

/**
 * A brush's window over a chart's rows ([xs]: each row's time, ascending, in the range's units): the first and last rows
 * inside [range]; every row for the whole drive, or when no row is inside. Null for a chart with no rows.
 */
export function indexWindow(xs: readonly number[], range: TimeRange | null): { startIndex: number; endIndex: number } | null {
  if (xs.length === 0) return null
  const all = { startIndex: 0, endIndex: xs.length - 1 }
  if (!range) return all
  const startIndex = xs.findIndex((x) => x >= range.from)
  let endIndex = -1
  for (let i = xs.length - 1; i >= 0; i--) if (xs[i] <= range.to) { endIndex = i; break }
  return startIndex < 0 || endIndex < startIndex ? all : { startIndex, endIndex }
}
/** The range a brush's window holds, from its rows' times; every row is the whole drive (null). */
export function rangeOfIndices(xs: readonly number[], startIndex: number, endIndex: number): TimeRange | null {
  if (startIndex <= 0 && endIndex >= xs.length - 1) return null
  return { from: xs[Math.max(0, startIndex)], to: xs[Math.min(xs.length - 1, endIndex)] }
}

/** The cursor if it is inside [range], else none. */
export function cursorWithin(cursor: number | null, range: TimeRange | null): number | null {
  if (cursor === null || !range) return cursor
  return cursor >= range.from && cursor <= range.to ? cursor : null
}

// ── The selection's state (the store's `selection`): written only here ──

/** Set the range (null: the whole drive), dropping a cursor that falls outside it. The address is written by the page. */
export function setRange(range: TimeRange | null): void {
  const s = useStore.getState().selection
  if (sameRange(s.range, range)) return
  useStore.setState({ selection: { range, cursor: cursorWithin(s.cursor, range) } })
}
/** Move the cursor (null: none); a moment outside the range is none. */
export function setCursor(cursor: number | null): void {
  const s = useStore.getState().selection
  const next = cursorWithin(cursor, s.range)
  if (next !== s.cursor) useStore.setState({ selection: { ...s, cursor: next } })
}
/**
 * Write the selection's range into the drive's address, in place of the current history entry (a range change adds no
 * Back step; a change of view does). Called once a change settles (a drag's end, "Whole drive"), never per drag move.
 */
export function commitRangeToAddress(): void {
  const a = currentAddress()
  if (a.place !== 'drive') return
  const range = useStore.getState().selection.range
  if (!sameRange(a.range ?? null, range)) go({ ...a, range }, { replace: true })
}
export function sameRange(a: TimeRange | null, b: TimeRange | null): boolean {
  return a === b || (a !== null && b !== null && a.from === b.from && a.to === b.to)
}
