import { create } from 'zustand'
import type { Session } from '../types/session'

export type PanelId = 'dashboard' | 'trip' | 'diagnostics' | 'sessions' | 'compare' | 'import' | 'settings'

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

export const useStore = create<SapphireState>((set) => ({
  activePanel: 'dashboard',
  setActivePanel: (panel) => set({ activePanel: panel }),

  sessions: [],
  setSessions: (sessions) => set({ sessions }),
  addSession: (session) => set((s) => ({ sessions: [...s.sessions, session] })),
  removeSession: (id) => set((s) => ({
    sessions: s.sessions.filter((sess) => sess.id !== id),
    activeSessionId: s.activeSessionId === id ? null : s.activeSessionId,
    compareSessionIds: s.compareSessionIds.filter((cid) => cid !== id),
  })),
  removeSessions: (ids) => set((s) => {
    const idSet = new Set(ids)
    return {
      sessions: s.sessions.filter((sess) => !idSet.has(sess.id)),
      activeSessionId: s.activeSessionId && idSet.has(s.activeSessionId) ? null : s.activeSessionId,
      compareSessionIds: s.compareSessionIds.filter((cid) => !idSet.has(cid)),
    }
  }),

  renameSession: (id, name) => set((s) => ({
    sessions: s.sessions.map((sess) => sess.id === id ? { ...sess, name } : sess),
  })),

  setSessionTags: (id, tags) => set((s) => ({
    sessions: s.sessions.map((sess) => sess.id === id ? { ...sess, tags } : sess),
  })),

  activeSessionId: null,
  setActiveSessionId: (id) => set({ activeSessionId: id }),

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
