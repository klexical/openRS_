import { create } from 'zustand'
import { useShallow } from 'zustand/react/shallow'
import type { Session } from '../types/session'
import type { DriveRecord, StorageStatus } from '../lib/storage'

export type PanelId = 'dashboard' | 'trip' | 'diagnostics' | 'sessions' | 'compare' | 'import' | 'settings'

// Remembered view (main e9cd565): the panel and selected session survive a reload, like the settings in
// store/settings.ts. App.tsx checks the saved session against IndexedDB once sessions load.
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
      const panel = parsed.activePanel && VALID_PANELS.has(parsed.activePanel) ? parsed.activePanel : 'dashboard'
      const sid = typeof parsed.activeSessionId === 'string' ? parsed.activeSessionId : null
      return { activePanel: panel, activeSessionId: sid }
    }
  } catch { /* unreadable or blocked storage — start from the defaults */ }
  return { activePanel: 'dashboard', activeSessionId: null }
}

function persistView(partial: Partial<PersistedView>) {
  try {
    localStorage.setItem(VIEW_KEY, JSON.stringify({ ...loadView(), ...partial }))
  } catch { /* storage unavailable — the view just isn't remembered */ }
}

const initialView = loadView()

interface SapphireState {
  /** Currently active nav panel. */
  activePanel: PanelId
  setActivePanel: (panel: PanelId) => void

  /**
   * Every stored drive's small record (#384): no points, no diagnostic log. Writes go through lib/storage; these
   * actions only mirror what it stored.
   */
  drives: DriveRecord[]
  setDrives: (drives: DriveRecord[]) => void
  addDrives: (drives: DriveRecord[]) => void
  /** Replace one record (after a rename or a tag edit). */
  updateDrive: (drive: DriveRecord) => void
  removeDrives: (ids: string[]) => void

  /** The open drive, with its points and log, loaded from storage while it is selected; null when none is open. */
  activeSession: Session | null
  setActiveSession: (session: Session | null) => void

  /** The drives being compared, loaded from storage while selected. */
  compareSessions: Session[]
  setCompareSessions: (sessions: Session[]) => void

  /** Storage state for the page's messages (a blocked or failed upgrade, a newer Sapphire in another tab). */
  storage: StorageStatus
  setStorage: (status: StorageStatus) => void

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
  activePanel: initialView.activePanel,
  setActivePanel: (panel) => { persistView({ activePanel: panel }); set({ activePanel: panel }) },

  drives: [],
  setDrives: (drives) => set({ drives }),
  addDrives: (drives) => set((s) => ({ drives: [...s.drives, ...drives] })),
  updateDrive: (drive) => set((s) => ({
    drives: s.drives.map((d) => (d.id === drive.id ? drive : d)),
    // the open drive shows the new name and tags at once
    activeSession: s.activeSession?.id === drive.id ? { ...s.activeSession, name: drive.name, tags: drive.tags } : s.activeSession,
    // and Compare, which holds its own copy of the compared drives (review H4)
    compareSessions: s.compareSessions.map((c) => (c.id === drive.id ? { ...c, name: drive.name, tags: drive.tags } : c)),
  })),
  removeDrives: (ids) => set((s) => {
    const idSet = new Set(ids)
    const nextActive = s.activeSessionId && idSet.has(s.activeSessionId) ? null : s.activeSessionId
    if (nextActive !== s.activeSessionId) persistView({ activeSessionId: nextActive })
    return {
      drives: s.drives.filter((d) => !idSet.has(d.id)),
      activeSessionId: nextActive,
      activeSession: s.activeSession && idSet.has(s.activeSession.id) ? null : s.activeSession,
      compareSessionIds: s.compareSessionIds.filter((cid) => !idSet.has(cid)),
      compareSessions: s.compareSessions.filter((c) => !idSet.has(c.id)),
    }
  }),

  activeSession: null,
  setActiveSession: (session) => set({ activeSession: session }),

  compareSessions: [],
  setCompareSessions: (sessions) => set({ compareSessions: sessions }),

  storage: { state: 'opening' },
  setStorage: (status) => set({ storage: status }),

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

/**
 * True while the selected drive is still being read from storage (#384): its panels show nothing then, rather than
 * "No Session Selected" or the drive that was open before.
 */
export const useOpeningDrive = () =>
  useStore((s) => s.activeSessionId !== null && s.activeSession?.id !== s.activeSessionId)

/** The open drive (points and log loaded); null while none is selected or it is still loading. */
export const useActiveSession = () =>
  useStore((s) => (s.activeSession && s.activeSession.id === s.activeSessionId ? s.activeSession : null))

/**
 * The drives being compared, in the order they were picked, loaded from storage. `useShallow` keeps the array
 * identity while its members are unchanged — a fresh array per store read makes React re-render forever
 * ("Maximum update depth exceeded" as soon as ComparePanel mounts).
 */
export const useCompareSessions = () =>
  useStore(useShallow((s) => s.compareSessionIds.map((id) => s.compareSessions.find((c) => c.id === id)).filter(Boolean) as Session[]))
