import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, act } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { Bookmark, TripPoint } from '../../types/session'
import { useSettings } from '../../store/settings'
import { GpsMap } from './GpsMap'

// #381: an observer's note reads one unit everywhere. The map's note popup prints the note's speed in the selected
// units, as the Dashboard's notes table does. Leaflet needs a real layout engine, so react-leaflet is replaced by stubs
// that render the markers' popups as plain elements. Invented drive and note (not a real place).

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
  TileLayer: () => null,
  Polyline: () => null,
  CircleMarker: ({ children }: { children?: ReactNode }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }: { children?: ReactNode }) => <div data-testid="popup">{children}</div>,
  useMap: () => ({ fitBounds: () => {} }),
}))

function point(i: number): TripPoint {
  return {
    ts: i * 1000, lat: 10 + i * 0.001, lng: 10 + i * 0.001, rpm: 2000, boostPsi: null, speedKph: 50,
    coolantC: 90, oilTempC: 95, rduTempC: 40, ptuTempC: 50, ambientC: 15, latG: 0, longG: 0, fuelPct: 50,
    wheelSpeedFL: 50, wheelSpeedFR: 50, wheelSpeedRL: 50, wheelSpeedRR: 50, driveMode: 'Normal',
    awdTorqueL: 0, awdTorqueR: 0, gear: '3', throttlePct: 20, tirePressLF: 36, tirePressRF: 36, tirePressLR: 36,
    tirePressRR: 36,
  } as TripPoint
}
const NOTE = { timestamp: 1000, relMs: 1000, label: 'invented note', speedKph: 100, rpm: 3000, lat: 10.0005, lng: 10.0005 } as Bookmark

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

function notePopup(): string {
  vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
  const { container } = render(<GpsMap points={[point(0), point(1)]} peakEvents={[]} bookmarks={[NOTE]} />)
  const popup = [...container.querySelectorAll('[data-testid="popup"]')].find((p) => p.textContent!.includes('invented note'))
  if (!popup) throw new Error('no popup for the note')
  return popup.textContent!
}

describe('#381 the map\'s note popup follows the units setting', () => {
  it('imperial: 100 km/h reads 62 MPH, never kph', () => {
    act(() => { useSettings.setState({ unitSystem: 'imperial' }) })
    const text = notePopup()
    expect(text).toContain('62 MPH')
    expect(text).not.toMatch(/kph/i)
  })

  it('metric: 100 km/h reads 100 KPH, as the Dashboard table does', () => {
    act(() => { useSettings.setState({ unitSystem: 'metric' }) })
    expect(notePopup()).toContain('100 KPH')
  })
})
