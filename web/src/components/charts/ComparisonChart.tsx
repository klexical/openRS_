import { useMemo, useState, useCallback } from 'react'
import {
  ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip, CartesianGrid, Legend,
} from 'recharts'
import { colors, chartColors } from '../../styles/tokens'
import type { Session } from '../../types/session'
import { normalizePoints, resampleNormalized, sessionShortName } from '../../lib/compare'

/** Colors assigned to each session in the overlay. */
const SESSION_COLORS = [chartColors[0], chartColors[1], chartColors[2], chartColors[4]]

interface ComparisonChartProps {
  sessions: Session[]
  dataKey: 'rpm' | 'boostPsi' | 'speedKph' | 'coolantC' | 'latG' | 'throttlePct'
  label: string
  yFormatter?: (v: number) => string
  height?: number
}

export function ComparisonChart({ sessions, dataKey, label, yFormatter, height = 200 }: ComparisonChartProps) {
  const [hidden, setHidden] = useState<Set<string>>(new Set())

  const handleLegendClick = useCallback((key: string) => {
    setHidden((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])

  // Build merged data: each row has { t, s0_rpm, s1_rpm, ... }
  const { chartData, seriesInfo } = useMemo(() => {
    const bins = 200
    const resampled = sessions.map((s) => {
      const pts = s.trip?.points
      if (!pts || pts.length < 2) return []
      return resampleNormalized(normalizePoints(pts), bins)
    })

    // Build merged rows
    const rows: Record<string, number>[] = []
    for (let i = 0; i < bins; i++) {
      const row: Record<string, number> = { t: i / (bins - 1) * 100 } // 0..100%
      resampled.forEach((pts, si) => {
        if (pts[i]) {
          row[`s${si}`] = pts[i][dataKey]
        }
      })
      rows.push(row)
    }

    const info = sessions.map((s, i) => ({
      key: `s${i}`,
      label: sessionShortName(s),
      color: SESSION_COLORS[i % SESSION_COLORS.length],
    }))

    return { chartData: rows, seriesInfo: info }
  }, [sessions, dataKey])

  if (chartData.length === 0) return null

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-3">
      <div className="text-[10px] font-mono uppercase tracking-widest text-dim mb-2">{label}</div>
      <ResponsiveContainer width="100%" height={height}>
        <LineChart data={chartData} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={colors.brd} />
          <XAxis
            dataKey="t"
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            tickFormatter={(v) => `${Math.round(v)}%`}
            stroke={colors.brd}
          />
          <YAxis
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            tickFormatter={yFormatter}
            stroke={colors.brd}
            width={45}
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
            labelFormatter={(v) => `${Math.round(Number(v))}% through session`}
          />
          <Legend
            verticalAlign="top"
            height={24}
            iconType="line"
            wrapperStyle={{ fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            onClick={(e) => {
              if (e && typeof e.dataKey === 'string') handleLegendClick(e.dataKey)
            }}
            formatter={(value, entry) => (
              <span style={{
                color: hidden.has(entry.dataKey as string) ? colors.dim : colors.frost,
                cursor: 'pointer',
                textDecoration: hidden.has(entry.dataKey as string) ? 'line-through' : 'none',
              }}>
                {value}
              </span>
            )}
          />
          {seriesInfo.map((s) => (
            <Line
              key={s.key}
              type="monotone"
              dataKey={s.key}
              name={s.label}
              stroke={s.color}
              strokeWidth={1.5}
              dot={false}
              isAnimationActive={false}
              hide={hidden.has(s.key)}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
