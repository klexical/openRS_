import type { DidInventory, DidInventoryRow } from '../types/session'

// The DID inventory of a diagnostic export (#253): per module and DID, what the session's pollers asked and what came back.
// Written by the app from rc.8 on as `didInventory` in the detail JSON. An export without the key recorded none: that is
// "not recorded" (undefined), never an empty inventory and never zero counts.

const count = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v) && v >= 0

function parseRow(r: unknown): DidInventoryRow | null {
  if (!r || typeof r !== 'object') return null
  const o = r as Record<string, unknown>
  if (typeof o.did !== 'string' || !/^[0-9A-Fa-f]{1,4}$/.test(o.did)) return null
  if (!count(o.sent) || !count(o.positive) || !count(o.nrcAttributed)) return null
  const row: DidInventoryRow = {
    module: typeof o.module === 'string' ? o.module : '',
    responseId: typeof o.responseId === 'string' ? o.responseId : '',
    did: o.did.toUpperCase().padStart(4, '0'),
    sent: o.sent,
    positive: o.positive,
    nrcAttributed: o.nrcAttributed,
  }
  // The app writes the time only for a DID that answered; 0 is a time (the session start)
  if (typeof o.lastPositiveRelMs === 'number' && Number.isFinite(o.lastPositiveRelMs) && o.lastPositiveRelMs >= 0) {
    row.lastPositiveRelMs = o.lastPositiveRelMs
  }
  return row
}

/** The export's inventory, or undefined when the export has none it can read as a list (not recorded). */
export function parseDidInventory(data: Record<string, unknown>): DidInventory | undefined {
  const inv = data.didInventory
  if (!inv || typeof inv !== 'object') return undefined
  const { rows, overflow } = inv as Record<string, unknown>
  if (!Array.isArray(rows)) return undefined
  const parsed = rows.map(parseRow)
  const kept = parsed.filter((r): r is DidInventoryRow => r !== null)
  return { rows: kept, overflow: count(overflow) ? overflow : 0, unreadable: parsed.length - kept.length }
}

export type DidStatus = 'answered' | 'rejected' | 'never-answered' | 'not-sent'

/** One status per row, by the export summary's rule: "never answered" = sent, no positive reply, no rejection charged to it. */
export function didStatus(r: DidInventoryRow): DidStatus {
  if (r.positive > 0) return 'answered'
  if (r.nrcAttributed > 0) return 'rejected'
  return r.sent > 0 ? 'never-answered' : 'not-sent'
}

export const DID_STATUS_LABEL: Record<DidStatus, string> = {
  'answered': 'answered',
  'rejected': 'rejected',
  'never-answered': 'never answered',
  'not-sent': 'not sent',
}

/** Time of the last positive reply since the session start, as the export summary writes it; a dash where there is none. */
export function lastAnswerText(r: DidInventoryRow): string {
  if (r.lastPositiveRelMs !== undefined) return `+${(r.lastPositiveRelMs / 1000).toFixed(1)} s`
  return didStatus(r) === 'never-answered' ? 'never answered' : '—'
}

export interface DidInventorySummary {
  total: number
  answered: number
  rejected: number
  neverAnswered: number
  notSent: number
}

export function didInventorySummary(inv: DidInventory): DidInventorySummary {
  const n = (s: DidStatus) => inv.rows.filter((r) => didStatus(r) === s).length
  return { total: inv.rows.length, answered: n('answered'), rejected: n('rejected'), neverAnswered: n('never-answered'), notSent: n('not-sent') }
}
