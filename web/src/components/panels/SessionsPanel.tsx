import { useState, useMemo } from 'react'
import { useStore } from '../../store'
import { EmptyState } from '../ui/EmptyState'
import { SearchBar } from '../ui/SearchBar'
import { fmtDateShort, fmtNumber, fmtDuration } from '../../lib/format'
import { deleteSession as dbDelete, putSession as dbPut } from '../../lib/db'
import { colors } from '../../styles/tokens'

type SortKey = 'date' | 'name' | 'distance' | 'speed'

const TAG_PRESETS = ['Track Day', 'Street', 'Commute', 'Tuning', 'Rain', 'Winter']

export function SessionsPanel() {
  const sessions = useStore((s) => s.sessions)
  const activeSessionId = useStore((s) => s.activeSessionId)
  const setActiveSessionId = useStore((s) => s.setActiveSessionId)
  const removeSession = useStore((s) => s.removeSession)
  const removeSessions = useStore((s) => s.removeSessions)
  const renameSession = useStore((s) => s.renameSession)
  const setSessionTags = useStore((s) => s.setSessionTags)
  const setActivePanel = useStore((s) => s.setActivePanel)

  const [search, setSearch] = useState('')
  const [sortKey, setSortKey] = useState<SortKey>('date')
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [tagEditId, setTagEditId] = useState<string | null>(null)

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

  // Filter + sort
  const filtered = useMemo(() => {
    let list = sessions
    if (search) {
      const q = search.toLowerCase()
      list = list.filter((s) =>
        s.name.toLowerCase().includes(q) ||
        (s.tags ?? []).some((t) => t.toLowerCase().includes(q))
      )
    }
    return [...list].sort((a, b) => {
      switch (sortKey) {
        case 'name': return a.name.localeCompare(b.name)
        case 'distance': return (b.trip?.summary.distanceKm ?? 0) - (a.trip?.summary.distanceKm ?? 0)
        case 'speed': return (b.trip?.summary.peakSpeedKph ?? 0) - (a.trip?.summary.peakSpeedKph ?? 0)
        default: return b.importedAt - a.importedAt
      }
    })
  }, [sessions, search, sortKey])

  const handleDelete = async (id: string) => {
    await dbDelete(id)
    removeSession(id)
    setSelectedIds((prev) => { const next = new Set(prev); next.delete(id); return next })
  }

  const handleBulkDelete = async () => {
    const ids = [...selectedIds]
    await Promise.all(ids.map(dbDelete))
    removeSessions(ids)
    setSelectedIds(new Set())
  }

  const handleSelect = (id: string) => {
    setActiveSessionId(id)
    setActivePanel('dashboard')
  }

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const startRename = (id: string, currentName: string) => {
    setEditingId(id)
    setEditName(currentName)
  }

  const commitRename = async (id: string) => {
    const trimmed = editName.trim()
    if (trimmed) {
      renameSession(id, trimmed)
      const sess = sessions.find((s) => s.id === id)
      if (sess) await dbPut({ ...sess, name: trimmed })
    }
    setEditingId(null)
  }

  const toggleTag = async (sessionId: string, tag: string) => {
    const sess = sessions.find((s) => s.id === sessionId)
    if (!sess) return
    const current = sess.tags ?? []
    const next = current.includes(tag) ? current.filter((t) => t !== tag) : [...current, tag]
    setSessionTags(sessionId, next)
    await dbPut({ ...sess, tags: next })
  }

  const allSelected = filtered.length > 0 && filtered.every((s) => selectedIds.has(s.id))

  return (
    <div className="max-w-4xl mx-auto space-y-3">
      {/* Toolbar */}
      <div className="flex items-center gap-3">
        <div className="flex-1">
          <SearchBar value={search} onChange={setSearch} placeholder="Search sessions or tags..." />
        </div>
        <select
          value={sortKey}
          onChange={(e) => setSortKey(e.target.value as SortKey)}
          className="px-2 py-2 rounded-md bg-surf3 border border-brd text-xs font-mono text-frost
                     focus:outline-none focus:border-accent/40"
        >
          <option value="date">Date ↓</option>
          <option value="name">Name A-Z</option>
          <option value="distance">Distance ↓</option>
          <option value="speed">Peak Speed ↓</option>
        </select>
      </div>

      {/* Bulk actions */}
      <div className="flex items-center gap-3 text-xs font-mono">
        <label className="flex items-center gap-1.5 text-dim cursor-pointer">
          <input
            type="checkbox"
            checked={allSelected}
            onChange={() => {
              if (allSelected) setSelectedIds(new Set())
              else setSelectedIds(new Set(filtered.map((s) => s.id)))
            }}
            className="accent-accent"
          />
          <span className="uppercase tracking-wider">
            {selectedIds.size > 0 ? `${selectedIds.size} selected` : 'Select All'}
          </span>
        </label>
        {selectedIds.size > 0 && (
          <button
            onClick={handleBulkDelete}
            className="px-2 py-1 rounded text-orange hover:bg-orange/10 border border-orange/20 transition-colors uppercase tracking-wider"
          >
            Delete Selected
          </button>
        )}
        <span className="text-dim ml-auto">{filtered.length} session{filtered.length !== 1 ? 's' : ''}</span>
      </div>

      {/* Session list */}
      <div className="space-y-2">
        {filtered.map((sess) => {
          const isActive = sess.id === activeSessionId
          const isSelected = selectedIds.has(sess.id)
          const isEditing = editingId === sess.id
          const isTagEdit = tagEditId === sess.id
          const tags = sess.tags ?? []

          return (
            <div
              key={sess.id}
              className={`
                rounded-lg border transition-all
                ${isActive
                  ? 'bg-accent/5 border-accent/30'
                  : 'bg-surf2 border-brd hover:border-dim'
                }
              `}
            >
              <div className="flex items-center gap-3 p-4">
                {/* Checkbox */}
                <input
                  type="checkbox"
                  checked={isSelected}
                  onChange={() => toggleSelect(sess.id)}
                  onClick={(e) => e.stopPropagation()}
                  className="accent-accent shrink-0"
                />

                {/* Main content — clickable */}
                <div className="flex-1 min-w-0 cursor-pointer" onClick={() => handleSelect(sess.id)}>
                  {isEditing ? (
                    <input
                      autoFocus
                      value={editName}
                      onChange={(e) => setEditName(e.target.value)}
                      onBlur={() => commitRename(sess.id)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') commitRename(sess.id)
                        if (e.key === 'Escape') setEditingId(null)
                      }}
                      onClick={(e) => e.stopPropagation()}
                      className="bg-surf3 border border-accent/30 rounded px-2 py-0.5 text-sm font-mono text-frost
                                 focus:outline-none w-full max-w-sm"
                    />
                  ) : (
                    <span className="text-sm font-mono text-frost">{sess.name}</span>
                  )}

                  {/* Meta row */}
                  <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[10px] font-mono text-dim uppercase mt-1">
                    {sess.trip && <span style={{ color: colors.ok }}>Trip</span>}
                    {sess.diagnostics && <span style={{ color: colors.accent }}>Diag</span>}
                    <span>Imported {fmtDateShort(sess.importedAt)}</span>
                    <span>v{sess.meta.appVersion}</span>
                    {sess.trip && (
                      <>
                        <span>{fmtNumber(sess.trip.summary.distanceKm)} km</span>
                        <span>{fmtDuration(sess.trip.summary.durationMs)}</span>
                        <span>Peak {fmtNumber(sess.trip.summary.peakSpeedKph, 0)} kph</span>
                      </>
                    )}
                  </div>

                  {/* Tags */}
                  {tags.length > 0 && (
                    <div className="flex gap-1.5 mt-1.5">
                      {tags.map((tag) => (
                        <span
                          key={tag}
                          className="inline-block px-1.5 py-0.5 rounded text-[10px] font-mono
                                     bg-accent/10 text-accent border border-accent/15"
                        >
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                {/* Actions */}
                <div className="flex items-center gap-1.5 shrink-0">
                  {isActive && (
                    <span className="text-[10px] font-mono text-accent uppercase tracking-wider mr-1">Active</span>
                  )}
                  <button
                    onClick={(e) => { e.stopPropagation(); startRename(sess.id, sess.name) }}
                    className="px-2 py-1 rounded text-[10px] font-mono text-dim hover:text-frost
                               hover:bg-surf3 transition-colors uppercase tracking-wider"
                    title="Rename"
                  >
                    Rename
                  </button>
                  <button
                    onClick={(e) => { e.stopPropagation(); setTagEditId(isTagEdit ? null : sess.id) }}
                    className="px-2 py-1 rounded text-[10px] font-mono text-dim hover:text-frost
                               hover:bg-surf3 transition-colors uppercase tracking-wider"
                    title="Tags"
                  >
                    Tags
                  </button>
                  <button
                    onClick={(e) => { e.stopPropagation(); handleDelete(sess.id) }}
                    className="px-2 py-1 rounded text-[10px] font-mono text-dim hover:text-orange
                               hover:bg-orange/10 transition-colors uppercase tracking-wider"
                    title="Delete"
                  >
                    Delete
                  </button>
                </div>
              </div>

              {/* Tag editor dropdown */}
              {isTagEdit && (
                <div className="px-4 pb-3 pt-0 flex flex-wrap gap-1.5 border-t border-brd mt-0">
                  {TAG_PRESETS.map((tag) => {
                    const active = tags.includes(tag)
                    return (
                      <button
                        key={tag}
                        onClick={() => toggleTag(sess.id, tag)}
                        className={`px-2 py-1 rounded text-[10px] font-mono uppercase tracking-wider
                                    border transition-colors
                          ${active
                            ? 'bg-accent/15 text-accent border-accent/30'
                            : 'bg-surf3 text-dim border-brd hover:text-frost hover:border-dim'
                          }
                        `}
                      >
                        {tag}
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
