// The Drive library's state, in the one store (spec SPEC-drive-library.md; plan PLAN-drive-library.md, section 1).
// The drives Sapphire keeps in this browser (and its session-only entries), the Current drive, and the drives picked
// for Compare. These actions only mirror what the database holds: outside tests, lib/driveLibrary.ts is their only
// caller; it writes the database first, then calls them. Pages read the fields freely. The names are today's (tests
// set and call them), so "active session" here means the Current drive.
import type { Session } from '../types/session'
import type { DriveRecord } from '../lib/storage'

export interface DriveLibraryState {
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

  /** True once the list's records were read: the empty state is shown only after a read that found none. */
  drivesLoaded: boolean
  setDrivesLoaded: (loaded: boolean) => void

  /** The Current drive, with its points and log, loaded from storage while it is named; null when none is shown. */
  activeSession: Session | null
  setActiveSession: (session: Session | null) => void

  /** The drive the address names (the app shell asks for it); null on the list and in Settings. */
  activeSessionId: string | null
  setActiveSessionId: (id: string | null) => void

  /** The drive whose read failed (storage did not answer): its page says so (fix round); null when none. */
  openFailedId: string | null
  setOpenFailedId: (id: string | null) => void

  /** The drives being compared, loaded from storage while selected. */
  compareSessions: Session[]
  setCompareSessions: (sessions: Session[]) => void

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

type Set = (partial: Partial<DriveLibraryState> | ((s: DriveLibraryState) => Partial<DriveLibraryState> | DriveLibraryState)) => void

/** The slice, composed into the store by store/index.ts. */
export const driveLibrarySlice = (set: Set): DriveLibraryState => ({
  drives: [],
  setDrives: (drives) => set({ drives }),
  addDrives: (drives) => set((s) => ({ drives: [...s.drives, ...drives] })),
  updateDrive: (drive) => set((s) => ({
    drives: s.drives.map((d) => (d.id === drive.id ? drive : d)),
    // the Current drive shows the new name, tags and note at once
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

  drivesLoaded: false,
  setDrivesLoaded: (loaded) => set({ drivesLoaded: loaded }),

  activeSession: null,
  setActiveSession: (session) => set({ activeSession: session }),

  activeSessionId: null,
  setActiveSessionId: (id) => set({ activeSessionId: id }),

  openFailedId: null,
  setOpenFailedId: (id) => set({ openFailedId: id }),

  compareSessions: [],
  setCompareSessions: (sessions) => set({ compareSessions: sessions }),

  compareSessionIds: [],
  toggleCompareSession: (id) => set((s) => {
    const current = s.compareSessionIds
    if (current.includes(id)) return { compareSessionIds: current.filter((c) => c !== id) }
    if (current.length >= 4) return s // max 4
    return { compareSessionIds: [...current, id] }
  }),
  clearCompare: () => set({ compareSessionIds: [] }),
  setCompare: (ids) => set({ compareSessionIds: ids.slice(0, 4) }),
})
