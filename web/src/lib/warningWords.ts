// What a drive's own page says when a warning cause could not be checked (#384 ticket 04). The words are the owner's to
// pick (REPORT-04, owner question 2): every string shown is in this file. Only the two causes this delivery checks are
// named (fault codes, recording gaps); knock events and critical temperatures wait for their parsing, and the list's
// filter says so ("fault codes and gaps").
import type { DriveRecord } from './storage'

export const NOT_CHECKED_WORDS = {
  prefix: 'Warnings not checked: ',
  /** No scan in the import, and no session log. */
  noScanNoLog: 'this import has no fault-code scan or session log.',
  noScan: 'this import has no fault-code scan.',
  noLog: 'this import has no session log.',
  /** A scan whose status is not COMPLETE (partial, failed, cancelled, interrupted, running, none stated, text file only). */
  scanIncomplete: "this import's fault-code scan is not marked complete.",
  scanIncompleteNoLog: "this import's fault-code scan is not marked complete, and it has no session log.",
  /** A drive stored before Sapphire recorded whether its import held a scan. */
  scanUnrecorded: 'this drive was stored before Sapphire recorded its fault-code scan.',
  scanUnrecordedNoLog: 'this drive was stored before Sapphire recorded its fault-code scan, and its import has no session log.',
} as const

/**
 * The line for a drive's page, or null: shown only when no warning was found and a checked-in-this-delivery cause is not
 * checked. A found warning needs no such line (the marker says it).
 */
export function notCheckedLine(d: Pick<DriveRecord, 'hasWarnings' | 'warnings' | 'dtcScan' | 'hasSession'>): string | null {
  if (d.hasWarnings) return null
  const codes = d.warnings?.faultCodes === 'not-checked'
  const log = d.warnings?.recordingGaps === 'not-checked'
  if (!codes && !log) return null
  const w = NOT_CHECKED_WORDS
  if (!codes) return w.prefix + w.noLog
  // why the fault codes were not checked: a scan without COMPLETE, no scan, or not recorded (stored before ticket 01
  // recorded it; a record with no diagnostics at all had no scan either way)
  const why = d.dtcScan === true ? 'incomplete' : d.dtcScan === undefined && d.hasSession ? 'unrecorded' : 'none'
  if (why === 'incomplete') return w.prefix + (log ? w.scanIncompleteNoLog : w.scanIncomplete)
  if (why === 'unrecorded') return w.prefix + (log ? w.scanUnrecordedNoLog : w.scanUnrecorded)
  return w.prefix + (log ? w.noScanNoLog : w.noScan)
}
