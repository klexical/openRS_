import { colors } from '../../styles/tokens'

interface MetricCardProps {
  label: string
  value: string
  sub?: string
  color?: string
}

export function MetricCard({ label, value, sub, color = colors.accent }: MetricCardProps) {
  return (
    <div className="rounded-lg border border-brd bg-surf2 p-4 flex flex-col gap-1 min-w-[140px]">
      <span className="text-xs font-mono uppercase tracking-widest text-dim">{label}</span>
      <span
        className="text-2xl font-display font-bold tracking-wide"
        style={{ color }}
      >
        {value}
      </span>
      {sub && <span className="text-xs text-mid">{sub}</span>}
    </div>
  )
}
