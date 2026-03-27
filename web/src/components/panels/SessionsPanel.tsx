import { useStore } from '../../store'
import { EmptyState } from '../ui/EmptyState'
import { fmtDateShort } from '../../lib/format'
import { deleteSession as dbDelete } from '../../lib/db'
import { colors } from '../../styles/tokens'

export function SessionsPanel() {
  const sessions = useStore((s) => s.sessions)
  const activeSessionId = useStore((s) => s.activeSessionId)
  const setActiveSessionId = useStore((s) => s.setActiveSessionId)
  const removeSession = useStore((s) => s.removeSession)
  const setActivePanel = useStore((s) => s.setActivePanel)

  if (sessions.length === 0) {
    return (
      <EmptyState
        icon="▤"
        title="No Sessions"
        description="Import a session to get started."
        action={
          <button
            onClick={() => setActivePanel('import')}
            className="px-4 py-2 rounded-md bg-accent/10 text-accent border border-accent/20
                       text-sm font-mono uppercase tracking-wider hover:bg-accent/20 transition-colors"
          >
            Import
          </button>
        }
      />
    )
  }

  const handleDelete = async (id: string) => {
    await dbDelete(id)
    removeSession(id)
  }

  const handleSelect = (id: string) => {
    setActiveSessionId(id)
    setActivePanel('dashboard')
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="space-y-2">
        {sessions
          .sort((a, b) => b.importedAt - a.importedAt)
          .map((sess) => {
            const isActive = sess.id === activeSessionId
            return (
              <div
                key={sess.id}
                className={`
                  flex items-center justify-between p-4 rounded-lg border transition-all cursor-pointer
                  ${isActive
                    ? 'bg-accent/5 border-accent/30'
                    : 'bg-surf2 border-brd hover:border-dim'
                  }
                `}
                onClick={() => handleSelect(sess.id)}
              >
                <div className="flex flex-col gap-1">
                  <span className="text-sm font-mono text-frost">{sess.name}</span>
                  <div className="flex gap-3 text-[10px] font-mono text-dim uppercase">
                    {sess.trip && <span style={{ color: colors.ok }}>Trip</span>}
                    {sess.diagnostics && <span style={{ color: colors.accent }}>Diag</span>}
                    <span>Imported {fmtDateShort(sess.importedAt)}</span>
                    <span>v{sess.meta.appVersion}</span>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  {isActive && (
                    <span className="text-[10px] font-mono text-accent uppercase tracking-wider">Active</span>
                  )}
                  <button
                    onClick={(e) => { e.stopPropagation(); handleDelete(sess.id) }}
                    className="px-2 py-1 rounded text-[10px] font-mono text-dim hover:text-orange
                               hover:bg-orange/10 transition-colors uppercase tracking-wider"
                    title="Delete session"
                  >
                    Delete
                  </button>
                </div>
              </div>
            )
          })}
      </div>
    </div>
  )
}
