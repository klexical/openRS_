import { describe, it, expect, beforeEach } from 'vitest'
import { useStore } from './index'
import type { Session } from '../types/session'
import { toDriveRecord } from '../lib/storage'

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
    drives: [],
    activeSession: null,
    compareSessions: [],
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

describe('drive records (#384: the store mirrors what lib/storage stored)', () => {
  const rec = (id: string, name = `Session ${id}`) => toDriveRecord(makeSession(id, name))

  it('addDrives appends to the list', () => {
    useStore.getState().addDrives([rec('1')])
    expect(useStore.getState().drives.map((d) => d.id)).toEqual(['1'])
  })

  it('setDrives replaces the entire list', () => {
    useStore.getState().addDrives([rec('1')])
    useStore.getState().setDrives([rec('2'), rec('3')])
    expect(useStore.getState().drives.map((d) => d.id)).toEqual(['2', '3'])
  })

  it('removeDrives removes by id and clears the selection, the open drive and compare picks that were removed', () => {
    useStore.getState().setDrives([rec('1'), rec('2'), rec('3'), rec('4')])
    useStore.getState().setActiveSessionId('2')
    useStore.setState({ activeSession: makeSession('2'), compareSessionIds: ['2', '3'], compareSessions: [makeSession('2'), makeSession('3')] })
    useStore.getState().removeDrives(['2', '4'])
    const st = useStore.getState()
    expect(st.drives.map((d) => d.id)).toEqual(['1', '3'])
    expect([st.activeSessionId, st.activeSession]).toEqual([null, null])
    expect(st.compareSessionIds).toEqual(['3'])
    expect(st.compareSessions.map((c) => c.id)).toEqual(['3'])
  })

  it('removeDrives keeps the selection when another drive is removed', () => {
    useStore.getState().setDrives([rec('1'), rec('2')])
    useStore.getState().setActiveSessionId('1')
    useStore.getState().removeDrives(['2'])
    expect(useStore.getState().activeSessionId).toBe('1')
  })

  it('updateDrive replaces one record, and the open drive shows its new name and tags', () => {
    useStore.getState().setDrives([rec('1', 'Old Name'), rec('2')])
    useStore.getState().setActiveSessionId('1')
    useStore.setState({ activeSession: makeSession('1', 'Old Name') })
    useStore.getState().updateDrive({ ...rec('1', 'New Name'), tags: ['Track Day', 'Rain'] })
    const st = useStore.getState()
    expect([st.drives[0].name, st.drives[0].tags]).toEqual(['New Name', ['Track Day', 'Rain']])
    expect([st.activeSession?.name, st.activeSession?.tags]).toEqual(['New Name', ['Track Day', 'Rain']])
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

  it('removeDrives also removes from compareSessionIds', () => {
    useStore.getState().setDrives([toDriveRecord(makeSession('1')), toDriveRecord(makeSession('2'))])
    useStore.getState().toggleCompareSession('1')
    useStore.getState().toggleCompareSession('2')
    useStore.getState().removeDrives(['1'])
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
