import { describe, it, expect } from 'vitest'
import { parseDidInventory, didStatus, didInventorySummary, lastAnswerText } from './didInventory'

// #253: the DID inventory of an rc.8 diagnostic export, as Sapphire reads it. Synthetic rows only.
const row = (over: Record<string, unknown> = {}) =>
  ({ module: 'PCM', responseId: '7E8', did: '033E', sent: 3, positive: 3, nrcAttributed: 0, lastPositiveRelMs: 12300, ...over })

describe('parseDidInventory (#253)', () => {
  it('an export without the key was not recorded: undefined, never an empty inventory', () => {
    expect(parseDidInventory({})).toBeUndefined()
    expect(parseDidInventory({ didInventory: null })).toBeUndefined()
    expect(parseDidInventory({ didInventory: 'x' })).toBeUndefined()
    expect(parseDidInventory({ didInventory: { overflow: 0 } })).toBeUndefined()         // no row list to read
  })

  it('a recorded inventory with no rows stays a recorded, empty inventory', () => {
    expect(parseDidInventory({ didInventory: { rows: [], overflow: 0 } })).toEqual({ rows: [], overflow: 0, unreadable: 0 })
  })

  it('keeps counts as written, zero included, and a time only where the app wrote one', () => {
    const inv = parseDidInventory({ didInventory: { rows: [
      row({ lastPositiveRelMs: 0 }),                                                      // answered at the session start: a time
      (() => { const r = row({ did: '0466', positive: 0 }); delete (r as Record<string, unknown>).lastPositiveRelMs; return r })(),
    ], overflow: 2 } })!
    expect(inv.rows[0]).toEqual({ module: 'PCM', responseId: '7E8', did: '033E', sent: 3, positive: 3, nrcAttributed: 0, lastPositiveRelMs: 0 })
    expect(inv.rows[1]).toEqual({ module: 'PCM', responseId: '7E8', did: '0466', sent: 3, positive: 0, nrcAttributed: 0 })
    expect('lastPositiveRelMs' in inv.rows[1]).toBe(false)
    expect(inv.overflow).toBe(2)
  })

  it('a row it cannot read is counted, not shown as zeros', () => {
    const inv = parseDidInventory({ didInventory: { rows: [
      row(), row({ sent: '3' }), row({ did: 12 }), row({ positive: -1 }), row({ nrcAttributed: NaN }), null, 'x', row({ did: 'ZZZZ' }),
    ], overflow: 0 } })!
    expect(inv.rows).toHaveLength(1)
    expect(inv.unreadable).toBe(7)
  })

  it('an invalid time is no time; an invalid overflow is not a count', () => {
    const inv = parseDidInventory({ didInventory: { rows: [row({ lastPositiveRelMs: -1 }), row({ lastPositiveRelMs: 'soon' })], overflow: 'many' } })!
    expect(inv.rows.every((r) => !('lastPositiveRelMs' in r))).toBe(true)
    expect(inv.overflow).toBe(0)
  })
})

describe('DID status (#253): the export summary’s rule', () => {
  const r = (sent: number, positive: number, nrcAttributed: number, lastPositiveRelMs?: number) =>
    ({ module: 'PCM', responseId: '7E8', did: '033E', sent, positive, nrcAttributed, ...(lastPositiveRelMs === undefined ? {} : { lastPositiveRelMs }) })

  it('answered, rejected, never answered, not sent', () => {
    expect(didStatus(r(3, 3, 0, 0))).toBe('answered')
    expect(didStatus(r(3, 1, 2, 500))).toBe('answered')          // answered at least once; the rejections stay in their column
    expect(didStatus(r(2, 0, 2))).toBe('rejected')
    expect(didStatus(r(2, 0, 0))).toBe('never-answered')
    expect(didStatus(r(0, 0, 0))).toBe('not-sent')
  })

  it('last answer: the time since the session start, "never answered" only for a silent DID, else a dash', () => {
    expect(lastAnswerText(r(3, 3, 0, 0))).toBe('+0.0 s')
    expect(lastAnswerText(r(3, 3, 0, 12349))).toBe('+12.3 s')
    expect(lastAnswerText(r(2, 0, 0))).toBe('never answered')
    expect(lastAnswerText(r(2, 0, 2))).toBe('—')                 // rejected: it did answer, with a rejection
    expect(lastAnswerText(r(0, 0, 0))).toBe('—')
  })

  it('summary counts each row once', () => {
    expect(didInventorySummary({ rows: [r(3, 3, 0, 0), r(3, 1, 2, 5), r(2, 0, 2), r(2, 0, 0), r(0, 0, 0)], overflow: 0, unreadable: 0 }))
      .toEqual({ total: 5, answered: 2, rejected: 1, neverAnswered: 1, notSent: 1 })
  })
})
