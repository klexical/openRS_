import { useState, useCallback, useMemo } from 'react'
import {
  ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip, CartesianGrid,
  Brush, ReferenceLine, Legend,
} from 'recharts'
import { colors } from '../../styles/tokens'
import type { PeakEvent } from '../../types/session'
import { indexWindow, rangeOfIndices, type TimeRange } from '../../lib/selection'

interface Series {
  key: string
  label: string
  color: string
}

interface TimeSeriesChartProps {
  /** null = no reading at that sample — drawn as a gap in the line, never a dip to 0 */
  data: Record<string, number | null>[]
  series: Series[]
  height?: number
  xKey?: string
  xFormatter?: (value: number) => string
  yFormatter?: (value: number) => string
  syncId?: string
  peakEvents?: PeakEvent[]
  /** Map peak event type → data key to show reference line only on relevant chart */
  peakFilter?: string
  /** Peak line label (default: the value); pressure charts name the source and use the unit's decimals */
  peakLabel?: (peak: PeakEvent) => string
  /**
   * The page's one time range (#416 ticket 2), in [xKey]'s units; null = the whole drive. A chart with a brush shows it as
   * the brush's window; one without shows only its rows.
   */
  range?: TimeRange | null
  /**
   * Given, a chart of more than 100 rows has a brush, and the brush is a writer of the page's selection: each move sets
   * the range ([onRange]), and the drag's end ([onRangeDone]) lets the page write its address once. Not given: no brush,
   * so the chart holds no range of its own (#416 ticket 2: nothing else on the page holds a range).
   */
  onRange?: (range: TimeRange | null) => void
  onRangeDone?: () => void
}

export function TimeSeriesChart({
  data,
  series,
  height = 200,
  xKey = 'ts',
  xFormatter,
  yFormatter,
  syncId,
  peakEvents,
  peakFilter,
  peakLabel,
  range = null,
  onRange,
  onRangeDone,
}: TimeSeriesChartProps) {
  // Track hidden series for legend toggle
  const [hidden, setHidden] = useState<Set<string>>(new Set())

  const handleLegendClick = useCallback((dataKey: string) => {
    setHidden((prev) => {
      const next = new Set(prev)
      if (next.has(dataKey)) next.delete(dataKey)
      else next.add(dataKey)
      return next
    })
  }, [])

  // the rows' times, and the brush's window over them; without a brush, only the rows inside the range are drawn
  const xs = useMemo(() => data.map((r) => Number(r[xKey])), [data, xKey])
  const brushed = onRange !== undefined && data.length > 100
  const brushWindow = brushed ? indexWindow(xs, range) : null
  const rows = useMemo(() => (brushed || !range ? data : data.filter((_, i) => xs[i] >= range.from && xs[i] <= range.to)),
    [brushed, range, data, xs])

  if (data.length === 0) {
    return (
      <div className="flex items-center justify-center h-[200px] text-dim text-sm font-mono">
        No data
      </div>
    )
  }

  // Filter peaks to only those relevant to this chart
  const relevantPeaks = peakEvents?.filter((p) => !peakFilter || p.type === peakFilter) ?? []

  const showBrush = brushed
  const showLegend = series.length > 1

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-3">
      <ResponsiveContainer width="100%" height={height + (showBrush ? 30 : 0)}>
        <LineChart
          data={rows}
          syncId={syncId}
          margin={{ top: 5, right: 10, left: 0, bottom: showBrush ? 0 : 5 }}
        >
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

          {/* Peak event reference lines */}
          {relevantPeaks.map((peak, i) => (
            <ReferenceLine
              key={`peak-${i}`}
              y={peak.value}
              stroke={colors.warn}
              strokeDasharray="4 4"
              strokeWidth={1}
              label={{
                value: peakLabel ? peakLabel(peak) : `▲ ${peak.value.toFixed(peak.type === 'rpm' || peak.type === 'speed' ? 0 : 1)}`,
                position: 'right',
                fill: colors.warn,
                fontSize: 9,
                fontFamily: 'JetBrains Mono, monospace',
              }}
            />
          ))}

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
              hide={hidden.has(s.key)}
            />
          ))}

          {showLegend && (
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
          )}

          {showBrush && (
            <Brush
              dataKey={xKey}
              height={20}
              stroke={colors.accent}
              fill={colors.surf}
              tickFormatter={xFormatter}
              travellerWidth={8}
              startIndex={brushWindow?.startIndex}
              endIndex={brushWindow?.endIndex}
              onChange={(w) => { if (w.startIndex != null && w.endIndex != null) onRange?.(rangeOfIndices(xs, w.startIndex, w.endIndex)) }}
              onDragEnd={() => onRangeDone?.()}
            />
          )}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
