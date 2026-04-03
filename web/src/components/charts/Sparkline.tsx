import { useMemo } from 'react'
import { colors } from '../../styles/tokens'

interface SparklineProps {
  data: number[]
  color?: string
  width?: number
  height?: number
}

/**
 * Lightweight inline trend chart — SVG glow line + gradient fill.
 * Matches the Android app's Sparkline.kt aesthetic.
 */
export function Sparkline({ data, color = colors.accent, width = 120, height = 36 }: SparklineProps) {
  const { path, areaPath } = useMemo(() => {
    if (data.length < 2) return { path: '', areaPath: '' }

    const filtered = data.filter((v) => isFinite(v))
    if (filtered.length < 2) return { path: '', areaPath: '' }

    const min = Math.min(...filtered)
    const max = Math.max(...filtered)
    const range = max - min || 1
    const padY = 2

    const points = filtered.map((v, i) => {
      const x = (i / (filtered.length - 1)) * width
      const y = padY + (1 - (v - min) / range) * (height - padY * 2)
      return { x, y }
    })

    const linePath = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
    const area = `${linePath} L${width},${height} L0,${height} Z`

    return { path: linePath, areaPath: area }
  }, [data, width, height])

  if (!path) return null

  const id = useMemo(() => `spark-${Math.random().toString(36).slice(2, 8)}`, [])

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} style={{ display: 'block' }}>
      <defs>
        <linearGradient id={`${id}-fill`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity={0.3} />
          <stop offset="100%" stopColor={color} stopOpacity={0} />
        </linearGradient>
        <filter id={`${id}-glow`}>
          <feGaussianBlur stdDeviation="2" result="blur" />
          <feMerge>
            <feMergeNode in="blur" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>
      <path d={areaPath} fill={`url(#${id}-fill)`} />
      <path d={path} fill="none" stroke={color} strokeWidth={1.5} filter={`url(#${id}-glow)`} />
    </svg>
  )
}
