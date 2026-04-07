import { describe, it, expect, beforeEach } from 'vitest'
import { useStore } from './index'
import type { Session } from '../types/session'

function makeSession(id: string, name = `Session ${id}`): Session {
  return {
    id,
    name,
    importedAt: Date.now(),
    tags: [],
    trip: null,
    diagnostics: null,
    meta: { appVersion: 'test', firmwareVersion: 'test', sessionStart: '', generatedAt: '' },
  }
}

// Reset store between tests
beforeEach(() => {
  useStore.setState({
    activePanel: 'dashboard',
    sessions: [],
    activeSessionId: null,
    compareSessionIds: [],
    navExpanded: true,
  })
})

describe('panel navigation', () => {
  it('defaults to dashboard', () => {
    expect(useStore.getState().activePanel).toBe('dashboard')
  })

  it('setActivePanel changes panel', () => {
    useStore.getState().setActivePanel('trip')
    expect(useStore.getState().activePanel).toBe('trip')
  })
})

describe('session management', () => {
  it('addSession appends to sessions list', () => {
    const s = makeSession('1')
    useStore.getState().addSession(s)
    expect(useStore.getState().sessions).toHaveLength(1)
    expect(useStore.getState().sessions[0].id).toBe('1')
  })

  it('setSessions replaces entire list', () => {
    useStore.getState().addSession(makeSession('1'))
    useStore.getState().setSessions([makeSession('2'), makeSession('3')])
    expect(useStore.getState().sessions).toHaveLength(2)
    expect(useStore.getState().sessions[0].id).toBe('2')
  })

  it('removeSession removes by id', () => {
    useStore.getState().setSessions([makeSession('1'), makeSession('2'), makeSession('3')])
    useStore.getState().removeSession('2')
    expect(useStore.getState().sessions.map((s) => s.id)).toEqual(['1', '3'])
  })

  it('removeSession clears activeSessionId if it was the removed session', () => {
    useStore.getState().setSessions([makeSession('1'), makeSession('2')])
    useStore.getState().setActiveSessionId('1')
    useStore.getState().removeSession('1')
    expect(useStore.getState().activeSessionId).toBeNull()
  })

  it('removeSession preserves activeSessionId if different session removed', () => {
    useStore.getState().setSessions([makeSession('1'), makeSession('2')])
    useStore.getState().setActiveSessionId('1')
    useStore.getState().removeSession('2')
    expect(useStore.getState().activeSessionId).toBe('1')
  })

  it('removeSessions removes multiple by id', () => {
    useStore.getState().setSessions([makeSession('1'), makeSession('2'), makeSession('3'), makeSession('4')])
    useStore.getState().removeSessions(['2', '4'])
    expect(useStore.getState().sessions.map((s) => s.id)).toEqual(['1', '3'])
  })

  it('renameSession updates session name', () => {
    useStore.getState().addSession(makeSession('1', 'Old Name'))
    useStore.getState().renameSession('1', 'New Name')
    expect(useStore.getState().sessions[0].name).toBe('New Name')
  })

  it('setSessionTags updates tags', () => {
    useStore.getState().addSession(makeSession('1'))
    useStore.getState().setSessionTags('1', ['Track Day', 'Rain'])
    expect(useStore.getState().sessions[0].tags).toEqual(['Track Day', 'Rain'])
  })
})

describe('compare mode', () => {
  it('toggleCompareSession adds session id', () => {
    useStore.getState().toggleCompareSession('1')
    expect(useStore.getState().compareSessionIds).toEqual(['1'])
  })

  it('toggleCompareSession removes if already present', () => {
    useStore.getState().toggleCompareSession('1')
    useStore.getState().toggleCompareSession('1')
    expect(useStore.getState().compareSessionIds).toEqual([])
  })

  it('caps at 4 compare sessions', () => {
    useStore.getState().toggleCompareSession('1')
    useStore.getState().toggleCompareSession('2')
    useStore.getState().toggleCompareSession('3')
    useStore.getState().toggleCompareSession('4')
    useStore.getState().toggleCompareSession('5') // should not add
    expect(useStore.getState().compareSessionIds).toHaveLength(4)
    expect(useStore.getState().compareSessionIds).not.toContain('5')
  })

  it('clearCompare empties the list', () => {
    useStore.getState().toggleCompareSession('1')
    useStore.getState().toggleCompareSession('2')
    useStore.getState().clearCompare()
    expect(useStore.getState().compareSessionIds).toEqual([])
  })

  it('removeSession also removes from compareSessionIds', () => {
    useStore.getState().setSessions([makeSession('1'), makeSession('2')])
    useStore.getState().toggleCompareSession('1')
    useStore.getState().toggleCompareSession('2')
    useStore.getState().removeSession('1')
    expect(useStore.getState().compareSessionIds).toEqual(['2'])
  })
})

describe('nav', () => {
  it('toggleNav flips expanded state', () => {
    expect(useStore.getState().navExpanded).toBe(true)
    useStore.getState().toggleNav()
    expect(useStore.getState().navExpanded).toBe(false)
    useStore.getState().toggleNav()
    expect(useStore.getState().navExpanded).toBe(true)
  })
})
