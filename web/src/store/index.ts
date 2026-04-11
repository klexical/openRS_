import { create } from 'zustand'
import type { Session } from '../types/session'

export type PanelId = 'dashboard' | 'trip' | 'diagnostics' | 'sessions' | 'compare' | 'import' | 'settings'

// Persistence: keep nav location across page refreshes. Settings already
// persist via localStorage in store/settings.ts; this mirrors that pattern
// for the main store's view-state fields without pulling in zustand/middleware.
const VIEW_KEY = 'sapphire_view'
const VALID_PANELS: ReadonlySet<PanelId> = new Set([
  'dashboard', 'trip', 'diagnostics', 'sessions', 'compare', 'import', 'settings',
])

interface PersistedView {
  activePanel: PanelId
  activeSessionId: string | null
}

function loadView(): PersistedView {
  try {
    const raw = localStorage.getItem(VIEW_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<PersistedView>
      const panel = parsed.activePanel && VALID_PANELS.has(parsed.activePanel)
        ? parsed.activePanel
        : 'dashboard'
      const sid = typeof parsed.activeSessionId === 'string' ? parsed.activeSessionId : null
      return { activePanel: panel, activeSessionId: sid }
    }
  } catch { /* ignore */ }
  return { activePanel: 'dashboard', activeSessionId: null }
}

function persistView(partial: Partial<PersistedView>) {
  try {
    const current = loadView()
    localStorage.setItem(VIEW_KEY, JSON.stringify({ ...current, ...partial }))
  } catch { /* ignore */ }
}

interface SapphireState {
  /** Currently active nav panel. */
  activePanel: PanelId
  setActivePanel: (panel: PanelId) => void

  /** All imported sessions. */
  sessions: Session[]
  setSessions: (sessions: Session[]) => void
  addSession: (session: Session) => void
  removeSession: (id: string) => void
  removeSessions: (ids: string[]) => void

  /** Rename a session. */
  renameSession: (id: string, name: string) => void

  /** Update tags for a session. */
  setSessionTags: (id: string, tags: string[]) => void

  /** Currently selected session for viewing. */
  activeSessionId: string | null
  setActiveSessionId: (id: string | null) => void

  /** Compare mode — selected session IDs (2-4). */
  compareSessionIds: string[]
  toggleCompareSession: (id: string) => void
  clearCompare: () => void

  /** Nav rail expanded state. */
  navExpanded: boolean
  toggleNav: () => void
}

const initialView = loadView()

export const useStore = create<SapphireState>((set) => ({
  activePanel: initialView.activePanel,
  setActivePanel: (panel) => { persistView({ activePanel: panel }); set({ activePanel: panel }) },

  sessions: [],
  setSessions: (sessions) => set({ sessions }),
  addSession: (session) => set((s) => ({ sessions: [...s.sessions, session] })),
  removeSession: (id) => set((s) => {
    const nextActive = s.activeSessionId === id ? null : s.activeSessionId
    if (nextActive !== s.activeSessionId) persistView({ activeSessionId: nextActive })
    return {
      sessions: s.sessions.filter((sess) => sess.id !== id),
      activeSessionId: nextActive,
      compareSessionIds: s.compareSessionIds.filter((cid) => cid !== id),
    }
  }),
  removeSessions: (ids) => set((s) => {
    const idSet = new Set(ids)
    const nextActive = s.activeSessionId && idSet.has(s.activeSessionId) ? null : s.activeSessionId
    if (nextActive !== s.activeSessionId) persistView({ activeSessionId: nextActive })
    return {
      sessions: s.sessions.filter((sess) => !idSet.has(sess.id)),
      activeSessionId: nextActive,
      compareSessionIds: s.compareSessionIds.filter((cid) => !idSet.has(cid)),
    }
  }),

  renameSession: (id, name) => set((s) => ({
    sessions: s.sessions.map((sess) => sess.id === id ? { ...sess, name } : sess),
  })),

  setSessionTags: (id, tags) => set((s) => ({
    sessions: s.sessions.map((sess) => sess.id === id ? { ...sess, tags } : sess),
  })),

  activeSessionId: initialView.activeSessionId,
  setActiveSessionId: (id) => { persistView({ activeSessionId: id }); set({ activeSessionId: id }) },

  compareSessionIds: [],
  toggleCompareSession: (id) => set((s) => {
    const current = s.compareSessionIds
    if (current.includes(id)) return { compareSessionIds: current.filter((c) => c !== id) }
    if (current.length >= 4) return s // max 4
    return { compareSessionIds: [...current, id] }
  }),
  clearCompare: () => set({ compareSessionIds: [] }),

  navExpanded: true,
  toggleNav: () => set((s) => ({ navExpanded: !s.navExpanded })),
}))

/** Convenience selector for the active session object. */
export const useActiveSession = () =>
  useStore((s) => s.sessions.find((sess) => sess.id === s.activeSessionId) ?? null)

/** Convenience selector for compare sessions. */
export const useCompareSessions = () =>
  useStore((s) => s.compareSessionIds.map((id) => s.sessions.find((sess) => sess.id === id)).filter(Boolean) as Session[])
