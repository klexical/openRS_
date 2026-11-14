import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { useStore } from '../../store'
import type { DiagnosticData, DidInventory, Session } from '../../types/session'
import { DiagnosticsPanel } from './DiagnosticsPanel'
import { storeWithSessions } from '../../test/storeState'


// #253: the DID inventory on the Diagnostics panel. Synthetic rows only.
const INV: DidInventory = {
  rows: [
    { module: 'BCM', responseId: '72E', did: '4028', sent: 1, positive: 1, nrcAttributed: 0, lastPositiveRelMs: 0 },
    { module: 'PCM', responseId: '7E8', did: '033E', sent: 3, positive: 3, nrcAttributed: 0, lastPositiveRelMs: 12300 },
    { module: 'PCM', responseId: '7E8', did: '03ED', sent: 2, positive: 0, nrcAttributed: 2 },
    { module: 'PCM', responseId: '7E8', did: '0466', sent: 2, positive: 0, nrcAttributed: 0 },
  ],
  overflow: 0,
  unreadable: 0,
}

function show(didInventory: DidInventory | undefined) {
  const diagnostics: DiagnosticData = {
    canInventory: [], fpsTimeline: [], sessionEvents: [], decodeTrace: [], probeResults: [], dtcResults: [], didInventory,
  }
  const session = { id: 'a', name: 'A', importedAt: 1, tags: [], trip: null, diagnostics,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', exportedAt: '' } } as unknown as Session
  useStore.setState(storeWithSessions([session], 'a'))
  return render(<DiagnosticsPanel />).container
}
const cells = (c: HTMLElement) => [...c.querySelectorAll('[data-testid="did-inventory"] tbody tr')]
  .map((tr) => [...tr.querySelectorAll('td')].map((td) => td.textContent))

beforeEach(() => useStore.setState(storeWithSessions([])))
afterEach(cleanup)

describe('Diagnostics panel: DID inventory (#253)', () => {
  it('lists each DID with its counts, last answer and status', () => {
    const c = show(INV)
    expect(cells(c)).toEqual([
      ['BCM', '0x4028', '1', '1', '0', '+0.0 s', 'answered'],
      ['PCM', '0x033E', '3', '3', '0', '+12.3 s', 'answered'],
      ['PCM', '0x03ED', '2', '0', '2', '—', 'rejected'],
      ['PCM', '0x0466', '2', '0', '0', 'never answered', 'never answered'],
    ])
    expect(c.textContent).toContain('4 DIDs · 2 answered · 1 rejected · 1 never answered')
    expect(c.textContent).not.toContain('not listed')
    expect(c.textContent).not.toContain('could not be read')
  })

  it('a session without an inventory says so and shows no table and no counts', () => {
    const c = show(undefined)
    expect(c.textContent).toContain('DID Inventory')
    expect(c.textContent).toContain('Not recorded for this session')
    expect(c.querySelector('[data-testid="did-inventory"] table')).toBeNull()
    expect(c.textContent).not.toContain('0 DIDs')
  })

  it('a recorded inventory with no rows reads as recorded: no Mode 22 request counted', () => {
    const c = show({ rows: [], overflow: 0, unreadable: 0 })
    expect(c.textContent).toContain('No Mode 22 request was counted in this session')
    expect(c.textContent).not.toContain('Not recorded')
    expect(c.querySelector('[data-testid="did-inventory"] table')).toBeNull()
  })

  it('says what the list leaves out: counts past the row limit and rows it could not read', () => {
    const c = show({ ...INV, overflow: 3, unreadable: 2 })
    expect(c.textContent).toContain('3 further counts of DIDs past the row limit: not listed')
    expect(c.textContent).toContain('2 rows could not be read and are not shown')
    expect(show({ ...INV, overflow: 1, unreadable: 1 }).textContent).toContain('1 further count of DIDs past the row limit: not listed')
  })
})
