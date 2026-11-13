interface SectionLabelProps {
  children: React.ReactNode
}

export function SectionLabel({ children }: SectionLabelProps) {
  return (
    <div className="flex items-center gap-3 mb-3 mt-6">
      {/* Below md a long label (Compare's "A vs B") wraps instead of widening the panel (#344); md and up as before */}
      <h3 className="min-w-0 md:min-w-auto text-xs font-mono uppercase tracking-[0.2em] text-dim whitespace-normal md:whitespace-nowrap">
        {children}
      </h3>
      <div className="h-px flex-1 bg-brd" />
    </div>
  )
}
