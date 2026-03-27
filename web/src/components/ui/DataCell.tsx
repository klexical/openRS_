interface DataCellProps {
  label: string
  value: string
  color?: string
}

export function DataCell({ label, value, color }: DataCellProps) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[10px] font-mono uppercase tracking-widest text-dim">{label}</span>
      <span className="text-sm font-mono text-frost" style={color ? { color } : undefined}>
        {value}
      </span>
    </div>
  )
}
