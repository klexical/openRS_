import { create } from 'zustand'
import { useShallow } from 'zustand/react/shallow'
import type { Session } from '../types/session'
import type { StorageStatus } from '../lib/storage'
import { driveLibrarySlice, type DriveLibraryState } from './driveLibrarySlice'

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

/** The page's state, and the Drive library's (store/driveLibrarySlice.ts), in one store. */
interface SapphireState extends DriveLibraryState {
  /** Which of today's views the open drive shows (in memory; ticket 03). */
  driveView: DriveView
  setDriveView: (view: DriveView) => void

  /** The running or last import (ticket 06): progress while it runs, each file's outcome after; null when none. */
  importJob: ImportJob | null
  setImportJob: (job: ImportJob | null) => void
  /** The drives the last import stored: marked "New" in the list until the next import. */
  newIds: string[]
  setNewIds: (ids: string[]) => void

  /** The list shows today's Compare for the ticked drives (Compare is an action, not a place; ticket 05). */
  compareOpen: boolean
  setCompareOpen: (open: boolean) => void

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
  driveView: 'dashboard',
  setDriveView: (view) => set({ driveView: view }),
  importJob: null,
  setImportJob: (job) => set({ importJob: job }),
  newIds: [],
  setNewIds: (ids) => set({ newIds: ids }),
  compareOpen: false,
  setCompareOpen: (open) => set({ compareOpen: open }),
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
