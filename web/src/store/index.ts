import { create } from 'zustand'
import { useShallow } from 'zustand/react/shallow'
import type { Session } from '../types/session'
import type { StorageStatus } from '../lib/storage'
import { currentDriveState, driveLibrarySlice, type DriveLibraryState } from './driveLibrarySlice'
import type { TimeRange } from '../lib/selection'

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

/** The views of one drive, reached from the drive (#384 ticket 03); the address names the view (#416 ticket 2). */
export type { DriveView } from '../lib/address'

/** The page's state, and the Drive library's (store/driveLibrarySlice.ts), in one store. */
interface SapphireState extends DriveLibraryState {
  /**
   * The drive page's selection (#416 ticket 2): its time range (null: the whole drive) and its cursor (null: none), in
   * milliseconds from the drive's first point. Written only by lib/selection.ts; the address reads and writes it there.
   */
  selection: { range: TimeRange | null; cursor: number | null }

  /** The running or last import (ticket 06): progress while it runs, each file's outcome after; null when none. */
  importJob: ImportJob | null
  setImportJob: (job: ImportJob | null) => void
  /** The drives the last import stored: marked "New" in the list until the next import. */
  newIds: string[]
  setNewIds: (ids: string[]) => void

  /** The list shows today's Compare for the ticked drives (Compare is an action, not a place; ticket 05). */
  compareOpen: boolean
  setCompareOpen: (open: boolean) => void

  /** "What's new" is open over the page (ticket 09): once after the update, or from Settings. */
  whatsNewOpen: boolean
  setWhatsNewOpen: (open: boolean) => void

  /** The list's search text and quick filters (ticket 04): kept here, so they are still there after opening a drive. */
  listSearch: string
  setListSearch: (text: string) => void
  filterWarnings: boolean
  filterLaps: boolean
  setFilters: (f: { warnings?: boolean; laps?: boolean }) => void

  /** A plain message on the list (an address naming a drive that is not stored); null when none. */
  listNotice: string | null
  setListNotice: (text: string | null) => void

  /** Storage state for the page's messages (a blocked or failed upgrade, a newer Sapphire in another tab). */
  storage: StorageStatus
  setStorage: (status: StorageStatus) => void
}

export const useStore = create<SapphireState>((set) => ({
  ...driveLibrarySlice(set),
  selection: { range: null, cursor: null },
  importJob: null,
  setImportJob: (job) => set({ importJob: job }),
  newIds: [],
  setNewIds: (ids) => set({ newIds: ids }),
  compareOpen: false,
  setCompareOpen: (open) => set({ compareOpen: open }),
  whatsNewOpen: false,
  setWhatsNewOpen: (open) => set({ whatsNewOpen: open }),
  listSearch: '',
  setListSearch: (text) => set({ listSearch: text }),
  filterWarnings: false,
  filterLaps: false,
  setFilters: (f) => set((s) => ({ filterWarnings: f.warnings ?? s.filterWarnings, filterLaps: f.laps ?? s.filterLaps })),
  listNotice: null,
  setListNotice: (text) => set({ listNotice: text }),

  storage: { state: 'opening' },
  setStorage: (status) => set({ storage: status }),
}))

/**
 * The Current drive as the Drive library holds it (#416 ticket 1): the drive the address names (`id`), its
 * state (lib/driveLibrary.ts `currentDriveState`), and the drive itself, with its points and log, once it is shown (null
 * otherwise). The one reading of it: the drive's page and its views all use this, and none keeps its own.
 */
export const useCurrentDrive = () => useStore(useShallow((s) => {
  const state = currentDriveState(s)
  return { id: s.activeSessionId, state, session: state === 'shown' ? s.activeSession : null }
}))

/**
 * The drives being compared, in the order they were picked, loaded from storage. `useShallow` keeps the array
 * identity while its members are unchanged — a fresh array per store read makes React re-render forever
 * ("Maximum update depth exceeded" as soon as ComparePanel mounts).
 */
export const useCompareSessions = () =>
  useStore(useShallow((s) => s.compareSessionIds.map((id) => s.compareSessions.find((c) => c.id === id)).filter(Boolean) as Session[]))
