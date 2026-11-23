import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { TripPoint } from '../../types/session'
import { GpsMap } from './GpsMap'

// #392: a stored drive's points do not say why time is missing between them. The app records a point only when a
// position update arrives, so the same spacing follows a pause, a lost adapter connection or a missing position fix.
// The marker at a spacing over 5 s says only that no points were recorded for that long, and names no cause. Leaflet
// needs a real layout engine, so react-leaflet is replaced by stubs that render each marker's popup as a plain element.
// Invented drive (not a real place).

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
  TileLayer: () => null,
  Polyline: () => null,
  CircleMarker: ({ children }: { children?: ReactNode }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }: { children?: ReactNode }) => <div data-testid="popup">{children}</div>,
  useMap: () => ({ fitBounds: () => {} }),
}))

function point(ts: number, i: number): TripPoint {
  return {
    ts, lat: 10 + i * 0.001, lng: 10 + i * 0.001, rpm: 2000, boostPsi: null, speedKph: 50,
    coolantC: 90, oilTempC: 95, rduTempC: 40, ptuTempC: 50, ambientC: 15, latG: 0, longG: 0, fuelPct: 50,
    wheelSpeedFL: 50, wheelSpeedFR: 50, wheelSpeedRL: 50, wheelSpeedRR: 50, driveMode: 'Normal',
    awdTorqueL: 0, awdTorqueR: 0, gear: '3', throttlePct: 20, tirePressLF: 36, tirePressRF: 36, tirePressLR: 36,
    tirePressRR: 36,
  } as TripPoint
}

afterEach(cleanup)

/** Popup texts other than the start and finish markers'. */
function gapPopups(points: TripPoint[]): string[] {
  const { container } = render(<GpsMap points={points} peakEvents={[]} />)
  return [...container.querySelectorAll('[data-testid="popup"]')]
    .map((p) => p.textContent!)
    .filter((t) => t !== 'Start' && t !== 'Finish')
}

describe('#392 the map does not name a cause for a spacing between points', () => {
  it('one spacing over 5 s, no session log: the marker says no points were recorded, and not "Paused"', () => {
    // 1 s steps, then 12 s with no point, then 1 s steps again
    const texts = gapPopups([point(0, 0), point(1000, 1), point(13_000, 2), point(14_000, 3)])
    expect(texts).toHaveLength(1)
    expect(texts[0]).toBe('No points recorded — 12 s')
    expect(texts[0]).not.toMatch(/pause/i)
    expect(texts[0]).not.toMatch(/disconnect|location|fix|adapter/i)        // no other cause named either
  })

  it('a spacing of 5 s or less draws no marker', () => {
    expect(gapPopups([point(0, 0), point(5000, 1), point(10_000, 2)])).toEqual([])
  })
})
