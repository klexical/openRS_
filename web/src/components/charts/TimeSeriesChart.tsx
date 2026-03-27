import {
  ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip, CartesianGrid,
} from 'recharts'
import { colors } from '../../styles/tokens'

interface Series {
  key: string
  label: string
  color: string
}

interface TimeSeriesChartProps {
  data: Record<string, number>[]
  series: Series[]
  height?: number
  xKey?: string
  xFormatter?: (value: number) => string
  yFormatter?: (value: number) => string
}

export function TimeSeriesChart({
  data,
  series,
  height = 200,
  xKey = 'ts',
  xFormatter,
  yFormatter,
}: TimeSeriesChartProps) {
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
        <LineChart data={data} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={colors.brd} />
          <XAxis
            dataKey={xKey}
            tick={{ fill: colors.dim, fontSize: 10, fontFamily: 'JetBrains Mono, monospace' }}
            tickFormatter={xFormatter}
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
            labelFormatter={xFormatter ? (label) => xFormatter(Number(label)) : undefined}
          />
          {series.map((s) => (
            <Line
              key={s.key}
              type="monotone"
              dataKey={s.key}
              name={s.label}
              stroke={s.color}
              strokeWidth={1.5}
              dot={false}
              isAnimationActive={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
