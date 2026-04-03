import { useMemo } from 'react'
import {
  ResponsiveContainer, AreaChart, Area, XAxis, YAxis, Tooltip,
  CartesianGrid, Legend,
} from 'recharts'
import { colors, chartColors } from '../../styles/tokens'
import type { TripPoint } from '../../types/session'

interface Props {
  points: TripPoint[]
  syncId?: string
}

const series = [
  { key: 'coolantC', label: 'Coolant', color: colors.accent },
  { key: 'oilTempC', label: 'Oil', color: colors.orange },
  { key: 'rduTempC', label: 'RDU', color: chartColors[4] },
  { key: 'ptuTempC', label: 'PTU', color: chartColors[3] },
] as const

export function TempStackChart({ points, syncId }: Props) {
  const data = useMemo(() => {
    const t0 = points[0]?.ts ?? 0
    return points.map((p) => ({
      ts: (p.ts - t0) / 1000,
      coolantC: p.coolantC > -90 ? p.coolantC : 0,
      oilTempC: p.oilTempC > -90 ? p.oilTempC : 0,
      rduTempC: p.rduTempC > -90 ? p.rduTempC : 0,
      ptuTempC: p.ptuTempC > -90 ? p.ptuTempC : 0,
    }))
  }, [points])

  if (data.length === 0) {
    return (
      <div className="flex items-center justify-center h-[200px] text-dim text-sm font-mono">
        No temperature data
      </div>
    )
  }

  const fmtTime = (sec: number) => {
    const m = Math.floor(sec / 60)
    const s = Math.round(sec % 60)
    return `${m}:${s.toString().padStart(2, '0')}`
  }

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-3">
      <ResponsiveContainer width="100%" height={240}>
        <AreaChart data={data} syncId={syncId} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={colors.brd} />
          <XAxis
            dataKey="ts"
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            tickFormatter={fmtTime}
            stroke={colors.brd}
          />
          <YAxis
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            tickFormatter={(v) => `${v}°`}
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
            labelFormatter={(label) => fmtTime(Number(label))}
            formatter={(value) => [`${Math.round(Number(value))}°C`]}
          />
          <Legend
            verticalAlign="top"
            height={24}
            iconType="rect"
            wrapperStyle={{ fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
          />
          {series.map((s) => (
            <Area
              key={s.key}
              type="monotone"
              dataKey={s.key}
              name={s.label}
              stroke={s.color}
              fill={s.color}
              fillOpacity={0.15}
              strokeWidth={1.5}
              isAnimationActive={false}
              stackId="temps"
            />
          ))}
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
