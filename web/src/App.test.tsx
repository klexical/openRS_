import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor } from '@testing-library/react'
import type { Session } from './types/session'

// The remembered view (localStorage `sapphire_view`) is read when the store module loads, so each case
// seeds storage, then imports a fresh store + App. IndexedDB is mocked; the shell isn't under test.
const db = vi.hoisted(() => ({ getAllSessions: vi.fn() }))
vi.mock('./lib/db', () => ({ getAllSessions: db.getAllSessions }))
vi.mock('./components/layout/Shell', () => ({ Shell: () => null }))

const session = (id: string, importedAt: number) =>
  ({ id, name: id, importedAt, tags: [], trip: null, diagnostics: null }) as unknown as Session
const STORED = [session('older', 1000), session('newest', 3000), session('middle', 2000)]

async function boot(saved: unknown, stored = 3) {
  if (saved !== undefined) localStorage.setItem('sapphire_view', typeof saved === 'string' ? saved : JSON.stringify(saved))
  vi.resetModules()
  const { useStore } = await import('./store')
  const { default: App } = await import('./App')
  const initial = useStore.getState()
  render(<App />)
  await waitFor(() => expect(db.getAllSessions).toHaveBeenCalled())
  await waitFor(() => expect(useStore.getState().sessions).toHaveLength(stored))   // IndexedDB load applied
  return { useStore, initial }
}

// Node 25's own (file-less, non-functional) localStorage global shadows jsdom's — give each case a working one.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size },
    clear: () => m.clear(),
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) },
    removeItem: (k) => { m.delete(k) },
    key: (i) => [...m.keys()][i] ?? null,
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  db.getAllSessions.mockReset().mockResolvedValue([...STORED])
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('remembered panel and session', () => {
  it('restores the saved panel and session when the session still exists', async () => {
    const { useStore, initial } = await boot({ activePanel: 'trip', activeSessionId: 'older' })
    expect(initial.activePanel).toBe('trip')
    expect(useStore.getState().activeSessionId).toBe('older')
    expect(useStore.getState().activePanel).toBe('trip')
  })

  it('a saved session that is no longer stored falls back to the most recently imported one', async () => {
    const { useStore } = await boot({ activePanel: 'trip', activeSessionId: 'deleted-elsewhere' })
    await waitFor(() => expect(useStore.getState().activeSessionId).toBe('newest'))
    expect(useStore.getState().activePanel).toBe('trip')
    expect(JSON.parse(localStorage.getItem('sapphire_view')!)).toEqual({ activePanel: 'trip', activeSessionId: 'newest' })
  })

  it('nothing saved → dashboard and the most recent session', async () => {
    const { useStore } = await boot(undefined)
    await waitFor(() => expect(useStore.getState().activeSessionId).toBe('newest'))
    expect(useStore.getState().activePanel).toBe('dashboard')
  })

  it('an empty database clears a stale saved session', async () => {
    db.getAllSessions.mockResolvedValue([])
    const { useStore } = await boot({ activePanel: 'compare', activeSessionId: 'gone' }, 0)
    await waitFor(() => expect(useStore.getState().activeSessionId).toBeNull())
    expect(useStore.getState().activePanel).toBe('compare')
  })

  it('an unknown panel or unreadable storage starts from the defaults', async () => {
    let { useStore } = await boot({ activePanel: 'telemetry', activeSessionId: 42 })
    expect(useStore.getState().activePanel).toBe('dashboard')
    await waitFor(() => expect(useStore.getState().activeSessionId).toBe('newest'))
    cleanup()
    ;({ useStore } = await boot('{not json'))
    expect(useStore.getState().activePanel).toBe('dashboard')
  })

  it('changing panel / session is remembered; deleting the selected session forgets it', async () => {
    const { useStore } = await boot(undefined)
    await waitFor(() => expect(useStore.getState().activeSessionId).toBe('newest'))
    useStore.getState().setActivePanel('diagnostics')
    useStore.getState().setActiveSessionId('middle')
    expect(JSON.parse(localStorage.getItem('sapphire_view')!)).toEqual({ activePanel: 'diagnostics', activeSessionId: 'middle' })
    useStore.getState().removeSessions(['middle', 'older'])
    expect(JSON.parse(localStorage.getItem('sapphire_view')!)).toEqual({ activePanel: 'diagnostics', activeSessionId: null })
  })

  it('an IndexedDB failure is logged, not thrown', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    db.getAllSessions.mockRejectedValue(new Error('blocked'))
    vi.resetModules()
    const { default: App } = await import('./App')
    render(<App />)
    await waitFor(() => expect(err).toHaveBeenCalledWith('[Sapphire] Failed to load sessions from IndexedDB:', expect.any(Error)))
    err.mockRestore()
  })
})
