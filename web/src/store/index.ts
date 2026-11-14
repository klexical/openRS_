import { create } from 'zustand'
import { useShallow } from 'zustand/react/shallow'
import type { Session } from '../types/session'
import type { DriveRecord, StorageStatus } from '../lib/storage'

/** An import as the page shows it (ticket 06). */
export interface ImportJob {
  running: boolean
  progress: { done: number; total: number; file: string; stage: 'reading' | 'parsing' | 'storing' } | null
  results: import('../lib/importFiles').FileResult[]
  /** Drives stored by this import, in total. */
  storedCount: number
  /** Lines about files not taken while it ran (a drop during an import; ticket 06 fix round). */
  notes?: string[]
}

/** The views of one drive, reached from the drive (#384 ticket 03). Kept in memory: the address names the drive only. */
export type DriveView = 'dashboard' | 'trip' | 'diagnostics'

interface SapphireState {
  /** Which of today's views the open drive shows (in memory; ticket 03). */
  driveView: DriveView
  setDriveView: (view: DriveView) => void

  /** The running or last import (ticket 06): progress while it runs, each file's outcome after; null when none. */
  importJob: ImportJob | null
  setImportJob: (job: ImportJob | null) => void
  /** The drives the last import stored: marked "New" in the list until the next import. */
  newIds: string[]
  setNewIds: (ids: string[]) => void

  /** True once the list's records were read: the empty state is shown only after a read that found none. */
  drivesLoaded: boolean
  setDrivesLoaded: (loaded: boolean) => void

  /** The list shows today's Compare for the ticked drives (Compare is an action, not a place; ticket 05). */
  compareOpen: boolean
  setCompareOpen: (open: boolean) => void

  /** The list's search text and quick filters (ticket 04): kept here, so they are still there after opening a drive. */
  listSearch: string
  setListSearch: (text: string) => void
  filterWarnings: boolean
  filterLaps: boolean
  setFilters: (f: { warnings?: boolean; laps?: boolean }) => void

  /** The drive whose read failed (storage did not answer): its page says so (fix round); null when none. */
  openFailedId: string | null
  setOpenFailedId: (id: string | null) => void

  /** A plain message on the list (an address naming a drive that is not stored); null when none. */
  listNotice: string | null
  setListNotice: (text: string | null) => void

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

  /** The drive the address names (App sets it from the address); null on the list and in Settings. */
  activeSessionId: string | null
  setActiveSessionId: (id: string | null) => void

  /** Compare mode — selected session IDs (2-4). */
  compareSessionIds: string[]
  toggleCompareSession: (id: string) => void
  clearCompare: () => void
  /** Compare exactly these drives, in this order (the first is the base): the ticked rows (ticket 05). */
  setCompare: (ids: string[]) => void

}

/** A loaded session with the owner's own fields (name, tags, note) taken from its updated small record. */
function withOwnFields(session: Session, drive: DriveRecord): Session {
  const { note: _old, ...rest } = session
  void _old
  return { ...rest, name: drive.name, tags: drive.tags, ...(drive.note !== undefined ? { note: drive.note } : {}) }
}

export const useStore = create<SapphireState>((set) => ({
  driveView: 'dashboard',
  setDriveView: (view) => set({ driveView: view }),
  importJob: null,
  setImportJob: (job) => set({ importJob: job }),
  newIds: [],
  setNewIds: (ids) => set({ newIds: ids }),
  drivesLoaded: false,
  setDrivesLoaded: (loaded) => set({ drivesLoaded: loaded }),
  compareOpen: false,
  setCompareOpen: (open) => set({ compareOpen: open }),
  listSearch: '',
  setListSearch: (text) => set({ listSearch: text }),
  filterWarnings: false,
  filterLaps: false,
  setFilters: (f) => set((s) => ({ filterWarnings: f.warnings ?? s.filterWarnings, filterLaps: f.laps ?? s.filterLaps })),
  openFailedId: null,
  setOpenFailedId: (id) => set({ openFailedId: id }),
  listNotice: null,
  setListNotice: (text) => set({ listNotice: text }),

  drives: [],
  setDrives: (drives) => set({ drives }),
  addDrives: (drives) => set((s) => ({ drives: [...s.drives, ...drives] })),
  updateDrive: (drive) => set((s) => ({
    drives: s.drives.map((d) => (d.id === drive.id ? drive : d)),
    // the open drive shows the new name, tags and note at once
    activeSession: s.activeSession?.id === drive.id ? withOwnFields(s.activeSession, drive) : s.activeSession,
    // and Compare, which holds its own copy of the compared drives (review H4)
    compareSessions: s.compareSessions.map((c) => (c.id === drive.id ? withOwnFields(c, drive) : c)),
  })),
  removeDrives: (ids) => set((s) => {
    const idSet = new Set(ids)
    const nextActive = s.activeSessionId && idSet.has(s.activeSessionId) ? null : s.activeSessionId
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
  setCompare: (ids) => set({ compareSessionIds: ids.slice(0, 4) }),
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
