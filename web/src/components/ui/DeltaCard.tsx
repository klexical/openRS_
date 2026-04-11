import type { KpiDelta } from '../../lib/compare'
import { fmtNumber, fmtDuration } from '../../lib/format'
import { colors } from '../../styles/tokens'

// Per-unit threshold below which a delta is considered "no meaningful change".
// 0.01 makes sense for L/100km or G but is invisible at RPM/ms scale, so each
// unit gets its own floor that roughly matches what the displayed precision
// would round to.
function neutralThreshold(unit: string): number {
  switch (unit) {
    case 'ms':       return 50         // 50 ms ≈ frame-level noise
    case 'RPM':      return 10
    case 'kph':      return 0.5
    case 'PSI':      return 0.1
    case '°C':       return 0.5
    case 'km':       return 0.05
    case 'L/100km':  return 0.05
    case 'G':        return 0.01
    default:         return 0.01
  }
}

export function DeltaCard({ delta }: { delta: KpiDelta }) {
  const improved = delta.higherIsBetter ? delta.diff > 0 : delta.diff < 0
  const neutral = Math.abs(delta.diff) < neutralThreshold(delta.unit)
  const arrowColor = neutral ? colors.dim : improved ? colors.ok : colors.orange
  const arrow = neutral ? '—' : delta.diff > 0 ? '▲' : '▼'

  const fmtValue = (v: number) => {
    if (delta.unit === 'ms') return fmtDuration(v)
    if (delta.unit === 'G') return fmtNumber(v, 2)
    if (delta.unit === 'L/100km') return fmtNumber(v, 1)
    return fmtNumber(v, delta.unit === 'RPM' ? 0 : 1)
  }

  const fmtDiff = (v: number) => {
    if (delta.unit === 'ms') return (v > 0 ? '+' : '') + fmtDuration(Math.abs(v))
    const prefix = v > 0 ? '+' : ''
    if (delta.unit === 'G') return prefix + fmtNumber(v, 2)
    return prefix + fmtNumber(v, delta.unit === 'RPM' ? 0 : 1)
  }

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-3 flex flex-col gap-1 min-w-[140px]">
      <span className="text-[10px] font-mono uppercase tracking-widest text-dim">{delta.label}</span>
      <div className="flex items-baseline gap-2">
        <span className="text-lg font-display font-bold text-frost">
          {fmtValue(delta.compValue)}
        </span>
        <span className="text-xs font-mono text-dim">{delta.unit !== 'ms' ? delta.unit : ''}</span>
      </div>
      <div className="flex items-center gap-1.5 mt-0.5">
        <span className="text-xs font-mono font-semibold" style={{ color: arrowColor }}>
          {arrow} {fmtDiff(delta.diff)}
        </span>
        {!neutral && (
          <span className="text-[10px] font-mono" style={{ color: arrowColor }}>
            ({delta.pctChange > 0 ? '+' : ''}{fmtNumber(delta.pctChange, 1)}%)
          </span>
        )}
      </div>
      <span className="text-[10px] font-mono text-dim/60">
        base: {fmtValue(delta.baseValue)}
      </span>
    </div>
  )
}
