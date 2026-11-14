// The list of drives' order and grouping (#384 ticket 03): newest first by the drive's start, grouped by month in the
// browser's local time.
import type { DriveRecord } from '../../lib/storage'

/**
 * When the drive started, for the list. The record's start, except for a start of 0 or less: the first Sapphire builds
 * stored every point at time 0 (spec draft 10, known limit), which would sort into January 1970. Those fall back to the
 * session's stated start, then the import time. Nothing stored changes (owner question 1 in REPORT-03).
 */
export function listStart(d: DriveRecord): number {
  if (d.driveStart > 0) return d.driveStart
  const stated = Date.parse(d.meta?.sessionStart ?? '')
  return Number.isFinite(stated) && stated > 0 ? stated : d.importedAt
}

export interface MonthGroup { key: string; label: string; drives: DriveRecord[] }

export function groupByMonth(drives: DriveRecord[]): MonthGroup[] {
  const sorted = drives.map((d) => ({ d, t: listStart(d) })).sort((a, b) => b.t - a.t)
  const groups: MonthGroup[] = []
  for (const { d, t } of sorted) {
    const when = new Date(t)
    const key = `${when.getFullYear()}-${when.getMonth()}`
    let g = groups[groups.length - 1]
    if (!g || g.key !== key) {
      g = { key, label: when.toLocaleDateString(undefined, { month: 'long', year: 'numeric' }), drives: [] }
      groups.push(g)
    }
    g.drives.push(d)
  }
  return groups
}

/** The dates a drive's row and month heading show, and the date in full (yyyy-mm-dd), local time: what a search matches. */
function dateTexts(t: number): string[] {
  const d = new Date(t)
  const pad = (n: number) => String(n).padStart(2, '0')
  return [
    d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' }),
    d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' }),
    d.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' }),
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
  ]
}

export interface ListFilter { search: string; warnings: boolean; laps: boolean }

/**
 * The list's search and quick filters (#384 ticket 04). Search: every word of the text, in any case, must be found in the
 * drive's name, its tags or its dates as the list writes them. "With warnings": a warning found (fault codes and gaps,
 * the causes checked in this delivery). "With laps": the drive has laps.
 */
export function matches(d: DriveRecord, f: ListFilter): boolean {
  if (f.warnings && !d.hasWarnings) return false
  if (f.laps && !d.hasLaps) return false
  const words = f.search.toLowerCase().split(/\s+/).filter(Boolean)
  if (words.length === 0) return true
  const hay = [d.name, ...(d.tags ?? []), ...dateTexts(listStart(d))].join('\n').toLowerCase()
  return words.every((w) => hay.includes(w))
}

/** A lap time as minutes, seconds and tenths: m:ss.t (1:52.4). */
export function lapTime(ms: number): string {
  const tenths = Math.round(ms / 100)
  const m = Math.floor(tenths / 600)
  const s = (tenths % 600) / 10
  return `${m}:${s.toFixed(1).padStart(4, '0')}`
}
