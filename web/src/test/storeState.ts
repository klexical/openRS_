import { toDriveRecord } from '../lib/storage'
import type { Session } from '../types/session'

/**
 * Store state for panel tests that start from whole sessions (#384): the list holds each one's small record, the open
 * drive and the compared drives hold the sessions themselves, as App loads them from storage.
 */
export function storeWithSessions(sessions: Session[], activeSessionId: string | null = null, compareSessionIds: string[] = []) {
  return {
    drives: sessions.map(toDriveRecord),
    activeSessionId,
    activeSession: sessions.find((s) => s.id === activeSessionId) ?? null,
    compareSessionIds,
    compareSessions: sessions.filter((s) => compareSessionIds.includes(s.id)),
  }
}
