import { useStore } from '../../store'

const panelTitles: Record<string, string> = {
  dashboard: 'Dashboard',
  trip: 'Trip Analysis',
  diagnostics: 'Diagnostics',
  sessions: 'Session Library',
  import: 'Import Data',
}

export function Header() {
  const activePanel = useStore((s) => s.activePanel)
  const sessionCount = useStore((s) => s.sessions.length)

  return (
    <header className="flex items-center justify-between px-6 py-3 bg-surf border-b border-brd">
      <div className="flex items-center gap-4">
        <h1 className="text-sm font-display uppercase tracking-[0.2em] text-frost">
          {panelTitles[activePanel] ?? activePanel}
        </h1>
      </div>

      <div className="flex items-center gap-4">
        <span className="text-xs font-mono text-dim">
          {sessionCount} session{sessionCount !== 1 ? 's' : ''}
        </span>
        <div className="w-2 h-2 rounded-full bg-ok animate-pulse" title="Ready" />
      </div>
    </header>
  )
}
