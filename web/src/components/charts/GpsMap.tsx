import { useState, useMemo, useEffect, useRef } from 'react'
import { MapContainer, TileLayer, Polyline, CircleMarker, Popup, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import type { TripPoint, PeakEvent } from '../../types/session'
import { colors } from '../../styles/tokens'
import {
  type ColorMode,
  colorModes,
  pointColor,
  colorLegend,
  peakMarkerStyle,
} from '../../lib/mapColors'

interface GpsMapProps {
  points: TripPoint[]
  peakEvents: PeakEvent[]
  height?: string
}

// ── Color-segmented polyline builder ────────────────────────────────

interface ColorSegment {
  positions: [number, number][]
  color: string
}

function buildColorSegments(points: TripPoint[], mode: ColorMode): ColorSegment[] {
  if (points.length === 0) return []
  const segments: ColorSegment[] = []
  let currentColor = pointColor(points[0], mode)
  let currentPositions: [number, number][] = [[points[0].lat, points[0].lng]]

  for (let i = 1; i < points.length; i++) {
    const p = points[i]
    const prev = points[i - 1]
    const gap = p.ts - prev.ts > 5000

    const color = pointColor(p, mode)
    if (color !== currentColor || gap) {
      segments.push({ positions: currentPositions, color: currentColor })
      currentColor = color
      currentPositions = gap
        ? [[p.lat, p.lng]]
        : [[prev.lat, prev.lng]] // overlap for seamless join
    }
    currentPositions.push([p.lat, p.lng])
  }
  if (currentPositions.length >= 2) {
    segments.push({ positions: currentPositions, color: currentColor })
  }
  return segments
}

// ── Zoom-to-fit helper ──────────────────────────────────────────────

function ZoomToFit({ points }: { points: TripPoint[] }) {
  const map = useMap()
  const fitted = useRef(false)

  useEffect(() => {
    if (fitted.current || points.length < 2) return
    const lats = points.map((p) => p.lat).filter((v) => v !== 0)
    const lngs = points.map((p) => p.lng).filter((v) => v !== 0)
    if (lats.length < 2) return

    const bounds: [[number, number], [number, number]] = [
      [Math.min(...lats), Math.min(...lngs)],
      [Math.max(...lats), Math.max(...lngs)],
    ]
    map.fitBounds(bounds, { padding: [40, 40] })
    fitted.current = true
  }, [map, points])

  return null
}

// ── Main component ──────────────────────────────────────────────────

export function GpsMap({ points, peakEvents, height = '55vh' }: GpsMapProps) {
  const [colorMode, setColorMode] = useState<ColorMode>('speed')

  const segments = useMemo(() => buildColorSegments(points, colorMode), [points, colorMode])
  const legend = useMemo(() => colorLegend(colorMode), [colorMode])

  // Detect pause points (>5s gap)
  const pausePoints = useMemo(() => {
    const pauses: { lat: number; lng: number; gapSec: number }[] = []
    for (let i = 1; i < points.length; i++) {
      const gap = points[i].ts - points[i - 1].ts
      if (gap > 5000) {
        pauses.push({ lat: points[i - 1].lat, lng: points[i - 1].lng, gapSec: gap / 1000 })
      }
    }
    return pauses
  }, [points])

  const hasValidGps = points.some((p) => p.lat !== 0 && p.lng !== 0)
  if (!hasValidGps) {
    return (
      <div
        style={{ height, background: colors.surf, borderRadius: 12, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
      >
        <span style={{ color: colors.dim, fontFamily: 'JetBrains Mono, monospace', fontSize: 13 }}>
          No GPS data available
        </span>
      </div>
    )
  }

  const center: [number, number] = [points[0].lat, points[0].lng]

  return (
    <div style={{ position: 'relative', borderRadius: 12, overflow: 'hidden' }}>
      <MapContainer
        center={center}
        zoom={14}
        style={{ height, width: '100%', background: colors.bg }}
        zoomControl={false}
        attributionControl={false}
      >
        <TileLayer
          url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
          maxZoom={19}
        />
        <ZoomToFit points={points} />

        {/* Color-segmented route */}
        {segments.map((seg, i) => (
          <Polyline
            key={i}
            positions={seg.positions}
            pathOptions={{ color: seg.color, weight: 4, opacity: 0.9, lineJoin: 'round', lineCap: 'round' }}
          />
        ))}

        {/* Start marker */}
        <CircleMarker
          center={[points[0].lat, points[0].lng]}
          radius={7}
          pathOptions={{ color: colors.ok, fillColor: colors.ok, fillOpacity: 1, weight: 2 }}
        >
          <Popup><span style={{ fontFamily: 'JetBrains Mono', fontSize: 12 }}>Start</span></Popup>
        </CircleMarker>

        {/* Finish marker */}
        {points.length >= 2 && (
          <CircleMarker
            center={[points[points.length - 1].lat, points[points.length - 1].lng]}
            radius={7}
            pathOptions={{ color: colors.red, fillColor: colors.red, fillOpacity: 1, weight: 2 }}
          >
            <Popup><span style={{ fontFamily: 'JetBrains Mono', fontSize: 12 }}>Finish</span></Popup>
          </CircleMarker>
        )}

        {/* Pause markers */}
        {pausePoints.map((p, i) => (
          <CircleMarker
            key={`pause-${i}`}
            center={[p.lat, p.lng]}
            radius={5}
            pathOptions={{ color: colors.warn, fillColor: colors.warn, fillOpacity: 0.9, weight: 1 }}
          >
            <Popup>
              <span style={{ fontFamily: 'JetBrains Mono', fontSize: 12 }}>
                Paused — {p.gapSec.toFixed(0)}s
              </span>
            </Popup>
          </CircleMarker>
        ))}

        {/* Peak markers */}
        {peakEvents.map((peak, i) => {
          if (peak.lat === 0 && peak.lng === 0) return null
          const style = peakMarkerStyle(peak.type)
          const label = `${style.prefix}: ${peak.value.toFixed(style.decimals)}${style.unit}`
          return (
            <CircleMarker
              key={`peak-${i}`}
              center={[peak.lat, peak.lng]}
              radius={8}
              pathOptions={{ color: style.color, fillColor: style.color, fillOpacity: 1, weight: 3 }}
            >
              <Popup>
                <span style={{ fontFamily: 'JetBrains Mono', fontSize: 12, fontWeight: 600 }}>
                  {label}
                </span>
              </Popup>
            </CircleMarker>
          )
        })}
      </MapContainer>

      {/* Color mode selector */}
      <div
        style={{
          position: 'absolute',
          top: 12,
          right: 12,
          zIndex: 1000,
          display: 'flex',
          gap: 2,
          background: `${colors.bg}cc`,
          borderRadius: 8,
          padding: 3,
          backdropFilter: 'blur(8px)',
        }}
      >
        {colorModes.map((m) => (
          <button
            key={m.id}
            onClick={() => setColorMode(m.id)}
            style={{
              padding: '4px 10px',
              borderRadius: 6,
              border: 'none',
              cursor: 'pointer',
              fontFamily: 'JetBrains Mono, monospace',
              fontSize: 11,
              fontWeight: 600,
              letterSpacing: '0.05em',
              background: colorMode === m.id ? colors.accent : 'transparent',
              color: colorMode === m.id ? colors.bg : colors.mid,
              transition: 'all 150ms ease',
            }}
          >
            {m.label}
          </button>
        ))}
      </div>

      {/* Color legend */}
      <div
        style={{
          position: 'absolute',
          bottom: 12,
          left: 12,
          zIndex: 1000,
          display: 'flex',
          gap: 10,
          background: `${colors.bg}cc`,
          borderRadius: 8,
          padding: '5px 10px',
          backdropFilter: 'blur(8px)',
        }}
      >
        {legend.map((entry) => (
          <div key={entry.label} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <div style={{ width: 10, height: 10, borderRadius: '50%', background: entry.color }} />
            <span
              style={{
                fontFamily: 'JetBrains Mono, monospace',
                fontSize: 10,
                color: colors.frost,
                letterSpacing: '0.03em',
              }}
            >
              {entry.label}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}
