import { useStore, type PanelId } from '../../store'

interface NavItem {
  id: PanelId
  label: string
  icon: string
}

const navItems: NavItem[] = [
  { id: 'dashboard', label: 'Dashboard', icon: '◈' },
  { id: 'trip', label: 'Trip', icon: '◎' },
  { id: 'diagnostics', label: 'Diagnostics', icon: '⬡' },
  { id: 'compare', label: 'Compare', icon: '⟺' },
  { id: 'sessions', label: 'Sessions', icon: '▤' },
  { id: 'import', label: 'Import', icon: '⊕' },
  { id: 'settings', label: 'Settings', icon: '⚙' },
]

export function NavRail() {
  const activePanel = useStore((s) => s.activePanel)
  const setActivePanel = useStore((s) => s.setActivePanel)
  const expanded = useStore((s) => s.navExpanded)
  const toggleNav = useStore((s) => s.toggleNav)

  return (
    <nav
      aria-label="Panels"
      className={`
        flex flex-row md:flex-col bg-surf border-t md:border-t-0 md:border-r border-brd w-full md:h-full shrink-0
        transition-all duration-200
        ${expanded ? 'md:w-48' : 'md:w-14'}
      `}
    >
      {/* Logo / collapse toggle */}
      <button
        onClick={toggleNav}
        className="hidden md:flex items-center gap-2 px-3 py-4 text-left hover:bg-surf2 transition-colors border-b border-brd"
      >
        <span className="text-accent font-display text-lg font-bold">RS</span>
        {expanded && (
          <span className="text-frost text-xs font-display tracking-[0.15em] uppercase">
            Sapphire
          </span>
        )}
      </button>

      {/* Nav items */}
      <div className="flex flex-row md:flex-col justify-around md:justify-start gap-1 p-2 flex-1">
        {navItems.map((item) => {
          const isActive = activePanel === item.id
          return (
            <button
              key={item.id}
              onClick={() => setActivePanel(item.id)}
              aria-label={item.label}
              aria-current={isActive ? 'page' : undefined}
              className={`
                flex items-center gap-3 px-3 py-2.5 rounded-md text-sm
                transition-all duration-150
                ${isActive
                  ? 'bg-accent/10 text-accent border border-accent/20'
                  : 'text-dim hover:text-frost hover:bg-surf2 border border-transparent'
                }
              `}
            >
              <span className={`text-base ${isActive ? 'text-accent' : ''}`}>
                {item.icon}
              </span>
              {expanded && (
                <span className="hidden md:inline font-mono text-xs uppercase tracking-wider">
                  {item.label}
                </span>
              )}
            </button>
          )
        })}
      </div>

      {/* Footer */}
      {expanded && (
        <div className="hidden md:block px-3 py-3 border-t border-brd">
          <span className="text-[10px] font-mono text-dim/50">
            open<strong className="text-dim">RS_</strong> Sapphire
          </span>
        </div>
      )}
    </nav>
  )
}
