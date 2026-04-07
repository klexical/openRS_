import { useMemo, useState, useCallback } from 'react'
import {
  ResponsiveContainer, ScatterChart, Scatter, XAxis, YAxis, Tooltip,
  CartesianGrid, ZAxis, Cell,
} from 'recharts'
import { colors, modeColors } from '../../styles/tokens'
import type { TripPoint } from '../../types/session'

type ColorBy = 'speed' | 'mode'

const speedBands: { min: number; max: number; color: string; label: string }[] = [
  { min: 0, max: 60, color: colors.accent, label: '<60 kph' },
  { min: 60, max: 100, color: colors.ok, label: '60–100' },
  { min: 100, max: 140, color: colors.warn, label: '100–140' },
  { min: 140, max: Infinity, color: colors.orange, label: '140+' },
]

function dotColor(point: { speedKph: number; driveMode: string }, colorBy: ColorBy): string {
  if (colorBy === 'mode') {
    const m = point.driveMode.toLowerCase()
    if (m === 'sport') return modeColors.sport
    if (m === 'track') return modeColors.track
    if (m === 'drift') return modeColors.drift
    return modeColors.normal
  }
  for (const b of speedBands) {
    if (point.speedKph < b.max) return b.color
  }
  return colors.orange
}

interface Props {
  points: TripPoint[]
}

export function GForceScatter({ points }: Props) {
  const [colorBy, setColorBy] = useState<ColorBy>('speed')

  const data = useMemo(() => {
    // Downsample for performance: take every Nth point to cap at ~2000 dots
    const step = Math.max(1, Math.floor(points.length / 2000))
    const out: { latG: number; speedKph: number; driveMode: string }[] = []
    for (let i = 0; i < points.length; i += step) {
      const p = points[i]
      if (Math.abs(p.latG) > 0.01 || p.speedKph > 5) {
        out.push({ latG: p.latG, speedKph: p.speedKph, driveMode: p.driveMode })
      }
    }
    return out
  }, [points])

  const legend = colorBy === 'speed'
    ? speedBands.map((b) => ({ label: b.label, color: b.color }))
    : [
        { label: 'Normal', color: modeColors.normal },
        { label: 'Sport', color: modeColors.sport },
        { label: 'Track', color: modeColors.track },
        { label: 'Drift', color: modeColors.drift },
      ]

  const handleToggle = useCallback(() => {
    setColorBy((c) => (c === 'speed' ? 'mode' : 'speed'))
  }, [])

  if (data.length === 0) {
    return (
      <div className="flex items-center justify-center h-[200px] text-dim text-sm font-mono">
        No G-force data
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-3">
      {/* Legend + toggle */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-3">
          {legend.map((l) => (
            <div key={l.label} className="flex items-center gap-1">
              <span className="w-2 h-2 rounded-full" style={{ backgroundColor: l.color }} />
              <span className="text-[10px] font-mono text-dim">{l.label}</span>
            </div>
          ))}
        </div>
        <button
          onClick={handleToggle}
          className="px-2 py-0.5 rounded text-[10px] font-mono text-dim border border-brd
                     hover:text-frost hover:border-dim transition-colors"
        >
          Color: {colorBy === 'speed' ? 'SPD' : 'MODE'}
        </button>
      </div>

      <ResponsiveContainer width="100%" height={280}>
        <ScatterChart margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={colors.brd} />
          <XAxis
            dataKey="latG"
            type="number"
            name="Lat G"
            domain={['auto', 'auto']}
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            stroke={colors.brd}
            label={{ value: 'Lateral G', position: 'insideBottom', offset: -2, fill: colors.dim, fontSize: 10 }}
          />
          <YAxis
            dataKey="speedKph"
            type="number"
            name="Speed"
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            stroke={colors.brd}
            width={45}
            label={{ value: 'KPH', position: 'insideLeft', angle: -90, fill: colors.dim, fontSize: 10 }}
          />
          <ZAxis range={[8, 8]} />
          <Tooltip
            contentStyle={{
              backgroundColor: colors.surf,
              border: `1px solid ${colors.brd}`,
              borderRadius: 6,
              fontSize: 11,
              fontFamily: 'JetBrains Mono, monospace',
              color: colors.frost,
            }}
            formatter={(value, name) => [
              name === 'Lat G' ? `${Number(value).toFixed(2)}G` : `${Math.round(Number(value))} kph`,
              String(name),
            ]}
          />
          <Scatter data={data} isAnimationActive={false}>
            {data.map((d, i) => (
              <Cell key={i} fill={dotColor(d, colorBy)} fillOpacity={0.6} />
            ))}
          </Scatter>
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  )
}
