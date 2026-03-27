interface SectionLabelProps {
  children: React.ReactNode
}

export function SectionLabel({ children }: SectionLabelProps) {
  return (
    <div className="flex items-center gap-3 mb-3 mt-6">
      <h3 className="text-xs font-mono uppercase tracking-[0.2em] text-dim whitespace-nowrap">
        {children}
      </h3>
      <div className="h-px flex-1 bg-brd" />
    </div>
  )
}
