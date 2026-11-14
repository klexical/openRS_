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
