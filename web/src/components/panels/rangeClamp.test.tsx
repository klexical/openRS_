import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, screen } from '@testing-library/react'
import { useStore } from '../../store'
import { storeWithSessions } from '../../test/storeState'
import { DashboardPanel } from './DashboardPanel'
import type { Session } from '../../types/session'

// QA's FINAL on delivery A, R3 (the spec review's C4): the page stores an address's range as written until the drive is
// shown and the range is clamped; a view drawn in between must never show a range the drive does not have. The views
// read the selection clamped to their drive. Invented data only, dated 2096: five points a second apart (0:00 to 0:04),
// a stored duration of 4 s.

vi.mock('../charts/GpsMap', () => ({ GpsMap: () => null }))

const T0 = 4_000_000_000_000
const drive: Session = {
  id: 'a', name: 'Invented loop', importedAt: T0, tags: [],
  trip: { points: [0, 1, 2, 3, 4].map((i) => ({ ts: T0 + i * 1000, lat: 10, lng: 20 + i / 1000, speedKph: 40, rpm: 3000 + 100 * i, boostPsi: null,
      coolantC: 90, oilTempC: 95 })) as never,
    summary: { distanceKm: 1, distanceSource: 'recorded', durationMs: 4000, fuelUsedL: 0, avgSpeedKph: 40, peakRpm: 3400,
      peakBoostPsi: null, peakLatG: 0, peakLongG: 0, peakSpeedKph: 40, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 3000,
      avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [] } },
  diagnostics: null, meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: new Date(T0).toISOString(), generatedAt: '' },
}
const duration = () => screen.getByText('Duration', { selector: 'span' }).parentElement!.textContent
afterEach(() => { cleanup(); useStore.setState({ selection: { range: null, cursor: null } }) })

describe('#416 ticket 2: a view draws only a range its drive has (QA R3)', () => {
  it('a range past the drive\'s end, still in the selection: the Dashboard draws the clamped range', () => {
    useStore.setState({ ...storeWithSessions([drive], 'a'), selection: { range: { from: 2000, to: 90_000 }, cursor: null } })
    render(<DashboardPanel />)
    expect(duration()).toBe('Duration2s')                     // 0:02 to 0:04, not 88 s
  })

  it('a reversed range in the selection: the Dashboard draws the whole drive', () => {
    useStore.setState({ ...storeWithSessions([drive], 'a'), selection: { range: { from: 3000, to: 1000 }, cursor: null } })
    render(<DashboardPanel />)
    expect(duration()).toBe('Duration4s')                     // the stored summary, never −2 s
  })
})
