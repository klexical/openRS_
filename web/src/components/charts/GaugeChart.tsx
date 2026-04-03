import { colors } from '../../styles/tokens'

interface GaugeChartProps {
  value: number
  max: number
  label: string
  unit?: string
  color?: string
  size?: number
}

/**
 * Semi-circular SVG gauge for peak values.
 * Shows value as a percentage of max (e.g., peak RPM as % of redline).
 */
export function GaugeChart({ value, max, label, unit = '', color = colors.accent, size = 100 }: GaugeChartProps) {
  const pct = Math.min(Math.max(value / max, 0), 1)
  const r = (size - 12) / 2
  const cx = size / 2
  const cy = size / 2 + 4
  // Semi-circle from 180° to 0° (left to right)
  const startAngle = Math.PI
  const endAngle = Math.PI * (1 - pct)

  const arcX1 = cx + r * Math.cos(startAngle)
  const arcY1 = cy + r * Math.sin(startAngle)
  const arcX2 = cx + r * Math.cos(endAngle)
  const arcY2 = cy + r * Math.sin(endAngle)

  const bgX2 = cx + r * Math.cos(0)
  const bgY2 = cy + r * Math.sin(0)

  const largeArc = pct > 0.5 ? 1 : 0

  const bgPath = `M ${arcX1},${arcY1} A ${r},${r} 0 1 1 ${bgX2},${bgY2}`
  const valuePath = pct > 0
    ? `M ${arcX1},${arcY1} A ${r},${r} 0 ${largeArc} 1 ${arcX2},${arcY2}`
    : ''

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0 }}>
      <svg width={size} height={size / 2 + 16} viewBox={`0 0 ${size} ${size / 2 + 16}`}>
        <defs>
          <filter id={`gauge-glow-${label}`}>
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>
        {/* Background arc */}
        <path d={bgPath} fill="none" stroke={colors.brd} strokeWidth={6} strokeLinecap="round" />
        {/* Value arc */}
        {valuePath && (
          <path
            d={valuePath}
            fill="none"
            stroke={color}
            strokeWidth={6}
            strokeLinecap="round"
            filter={`url(#gauge-glow-${label})`}
          />
        )}
        {/* Center value */}
        <text
          x={cx}
          y={cy - 4}
          textAnchor="middle"
          style={{ fill: colors.frost, fontSize: 14, fontFamily: 'Orbitron, sans-serif', fontWeight: 700 }}
        >
          {value >= 1000 ? `${(value / 1000).toFixed(1)}k` : value.toFixed(value < 10 ? 1 : 0)}
        </text>
        <text
          x={cx}
          y={cy + 10}
          textAnchor="middle"
          style={{ fill: colors.dim, fontSize: 8, fontFamily: 'JetBrains Mono, monospace', textTransform: 'uppercase', letterSpacing: '0.1em' }}
        >
          {unit}
        </text>
      </svg>
      <span style={{
        color: colors.dim,
        fontSize: 9,
        fontFamily: 'JetBrains Mono, monospace',
        textTransform: 'uppercase',
        letterSpacing: '0.1em',
        marginTop: -4,
      }}>
        {label}
      </span>
    </div>
  )
}
