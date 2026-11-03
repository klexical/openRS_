import { useId, useMemo } from 'react'
import { colors } from '../../styles/tokens'

interface SparklineProps {
  /** One value per sample; null / NaN = no reading — the line breaks there, positions are kept (never joined). */
  data: (number | null)[]
  color?: string
  width?: number
  height?: number
}

/**
 * Lightweight inline trend chart — SVG glow line + gradient fill.
 * Matches the Android app's Sparkline.kt aesthetic.
 */
export function Sparkline({ data, color = colors.accent, width = 120, height = 36 }: SparklineProps) {
  // Gradient / filter ids, unique per instance (useId — stable across renders, no hook after the early return)
  const id = `spark${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const { path, areaPath } = useMemo(() => {
    if (data.length < 2) return { path: '', areaPath: '' }

    const finite = data.filter((v): v is number => v != null && isFinite(v))
    if (finite.length < 2) return { path: '', areaPath: '' }

    const min = Math.min(...finite)
    const max = Math.max(...finite)
    const range = max - min || 1
    const padY = 2

    // x from the sample's own position in the full series; a missing sample starts a new sub-path (a gap)
    let linePath = ''
    let penDown = false
    let gaps = false
    data.forEach((v, i) => {
      if (v == null || !isFinite(v)) { if (penDown) gaps = true; penDown = false; return }
      const x = (i / (data.length - 1)) * width
      const y = padY + (1 - (v - min) / range) * (height - padY * 2)
      linePath += `${penDown ? ' L' : (linePath ? ' M' : 'M')}${x.toFixed(1)},${y.toFixed(1)}`
      penDown = true
    })
    // The fill would bridge gaps — only drawn for an unbroken series
    const area = gaps ? '' : `${linePath} L${width},${height} L0,${height} Z`

    return { path: linePath, areaPath: area }
  }, [data, width, height])

  if (!path) return null

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
