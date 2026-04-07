import { useMemo } from 'react'
import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, CartesianGrid,
} from 'recharts'
import { colors } from '../../styles/tokens'

interface Props {
  values: number[]
  /** Number of equal-width bins. */
  bins?: number
  /** Color of bars. */
  color?: string
  /** X-axis label. */
  xLabel?: string
  /** Y-axis label (default: "Time (s)"). */
  yLabel?: string
  height?: number
  syncId?: string
}

interface Bin {
  label: string
  rangeMin: number
  rangeMax: number
  count: number
}

export function Histogram({
  values,
  bins = 12,
  color = colors.accent,
  xLabel,
  yLabel = 'Time (s)',
  height = 220,
  syncId,
}: Props) {
  const data = useMemo(() => {
    if (values.length === 0) return []
    const filtered = values.filter((v) => isFinite(v))
    if (filtered.length === 0) return []

    const min = Math.min(...filtered)
    const max = Math.max(...filtered)
    if (max === min) return [{ label: `${Math.round(min)}`, rangeMin: min, rangeMax: max, count: filtered.length }]

    const binWidth = (max - min) / bins
    const result: Bin[] = []
    for (let i = 0; i < bins; i++) {
      const lo = min + i * binWidth
      const hi = lo + binWidth
      result.push({
        label: `${Math.round(lo)}`,
        rangeMin: lo,
        rangeMax: hi,
        count: 0,
      })
    }

    for (const v of filtered) {
      const idx = Math.min(Math.floor((v - min) / binWidth), bins - 1)
      result[idx].count++
    }
    return result
  }, [values, bins])

  if (data.length === 0) {
    return (
      <div className="flex items-center justify-center h-[200px] text-dim text-sm font-mono">
        No data
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-3">
      <ResponsiveContainer width="100%" height={height}>
        <BarChart data={data} syncId={syncId} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={colors.brd} vertical={false} />
          <XAxis
            dataKey="label"
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            stroke={colors.brd}
            label={xLabel ? { value: xLabel, position: 'insideBottom', offset: -2, fill: colors.dim, fontSize: 10 } : undefined}
          />
          <YAxis
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            stroke={colors.brd}
            width={40}
            label={yLabel ? { value: yLabel, position: 'insideLeft', angle: -90, fill: colors.dim, fontSize: 10 } : undefined}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: colors.surf,
              border: `1px solid ${colors.brd}`,
              borderRadius: 6,
              fontSize: 11,
              fontFamily: 'JetBrains Mono, monospace',
              color: colors.frost,
            }}
            formatter={(value) => [`${value} samples`, 'Count']}
            labelFormatter={(label, payload) => {
              const d = payload?.[0]?.payload as Bin | undefined
              if (d) return `${Math.round(d.rangeMin)} – ${Math.round(d.rangeMax)}`
              return String(label)
            }}
          />
          <Bar
            dataKey="count"
            fill={color}
            fillOpacity={0.7}
            radius={[2, 2, 0, 0]}
            isAnimationActive={false}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
