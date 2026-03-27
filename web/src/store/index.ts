import { create } from 'zustand'
import type { Session } from '../types/session'

export type PanelId = 'dashboard' | 'trip' | 'diagnostics' | 'sessions' | 'import'

interface SapphireState {
  /** Currently active nav panel. */
  activePanel: PanelId
  setActivePanel: (panel: PanelId) => void

  /** All imported sessions. */
  sessions: Session[]
  setSessions: (sessions: Session[]) => void
  addSession: (session: Session) => void
  removeSession: (id: string) => void

  /** Currently selected session for viewing. */
  activeSessionId: string | null
  setActiveSessionId: (id: string | null) => void

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
  })),

  activeSessionId: null,
  setActiveSessionId: (id) => set({ activeSessionId: id }),

  navExpanded: true,
  toggleNav: () => set((s) => ({ navExpanded: !s.navExpanded })),
}))

/** Convenience selector for the active session object. */
export const useActiveSession = () =>
  useStore((s) => s.sessions.find((sess) => sess.id === s.activeSessionId) ?? null)
