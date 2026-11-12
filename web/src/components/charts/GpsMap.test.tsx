import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import type { ReactNode } from 'react'
import type { TripPoint } from '../../types/session'
import { GpsMap } from './GpsMap'

// #240: the basemap. The keyless CARTO dark_all endpoint answers every tile with one placeholder image; the owner's
// decision is OpenStreetMap's standard tiles, darkened by a display filter, with OpenStreetMap's attribution shown.
// Leaflet needs a real layout engine, so react-leaflet is replaced by stubs that keep the props GpsMap passes; the
// rendered map (tiles, the attribution control) is checked in the browser smoke.

const seen = vi.hoisted(() => ({ map: [] as Record<string, unknown>[], tiles: [] as Record<string, unknown>[] }))

vi.mock('react-leaflet', () => ({
  MapContainer: (p: Record<string, unknown> & { children?: ReactNode }) => { seen.map.push(p); return <div>{p.children}</div> },
  TileLayer: (p: Record<string, unknown>) => { seen.tiles.push(p); return null },
  Polyline: () => null,
  CircleMarker: () => null,
  Popup: () => null,
  useMap: () => ({ fitBounds: () => {} }),
}))

/** A synthetic two-point drive (not a real place): enough for GpsMap to draw the map. */
function point(i: number): TripPoint {
  return {
    ts: i * 1000, lat: 10 + i * 0.001, lng: 10 + i * 0.001, rpm: 2000, boostPsi: null, speedKph: 50,
    coolantC: 90, oilTempC: 95, rduTempC: 40, ptuTempC: 50, ambientC: 15, latG: 0, longG: 0, fuelPct: 50,
    wheelSpeedFL: 50, wheelSpeedFR: 50, wheelSpeedRL: 50, wheelSpeedRR: 50, driveMode: 'Normal',
    awdTorqueL: 0, awdTorqueR: 0, gear: '3', throttlePct: 20, tirePressLF: 36, tirePressRF: 36, tirePressLR: 36,
    tirePressRR: 36,
  } as TripPoint
}

beforeEach(() => { seen.map.length = 0; seen.tiles.length = 0 })

describe('GpsMap basemap (#240)', () => {
  it('draws OpenStreetMap standard tiles, and nothing from the old provider', () => {
    render(<GpsMap points={[point(0), point(1)]} peakEvents={[]} />)
    expect(seen.tiles).toHaveLength(1)
    const tiles = seen.tiles[0]
    expect(tiles.url).toBe('https://tile.openstreetmap.org/{z}/{x}/{y}.png')
    expect(String(tiles.url)).not.toContain('cartocdn')
    expect(Number(tiles.maxZoom)).toBeLessThanOrEqual(19)          // OpenStreetMap's standard layer stops at 19
  })

  it("shows OpenStreetMap's attribution, linking to its copyright page", () => {
    render(<GpsMap points={[point(0), point(1)]} peakEvents={[]} />)
    expect(seen.map).not.toHaveLength(0)
    expect(seen.map.every((p) => p.attributionControl !== false)).toBe(true)
    const holder = document.createElement('div')
    holder.innerHTML = String(seen.tiles[0].attribution ?? '')
    expect(holder.textContent).toBe('© OpenStreetMap contributors')
    const link = holder.querySelector('a')
    expect(link?.getAttribute('href')).toBe('https://www.openstreetmap.org/copyright')
    expect(link?.textContent).toBe('© OpenStreetMap contributors')
  })

  it('darkens the tiles through a class the stylesheet filters', () => {
    render(<GpsMap points={[point(0), point(1)]} peakEvents={[]} />)
    const cls = String(seen.tiles[0].className ?? '')
    expect(cls).not.toBe('')
    const css = readFileSync(join(process.cwd(), 'src/index.css'), 'utf8')
    const rule = new RegExp(`\\.${cls}\\s*\\{[^}]*filter\\s*:`)
    expect(css).toMatch(rule)
  })
})
