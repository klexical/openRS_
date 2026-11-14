import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor } from '@testing-library/react'

// The App's load of the drives with the storage module mocked; the shell isn't under test.
const db = vi.hoisted(() => ({ getAllSessions: vi.fn() }))
vi.mock('./lib/storage', () => ({
  // a stored drive opens; one that is not stored would be dropped from the selection (#384)
  listDrives: db.getAllSessions, loadDrive: async (id: string) => ({ id, name: id }),
  storageStatus: () => ({ state: 'ready' }), onStorageStatus: () => () => {},
}))
vi.mock('./components/layout/Shell', () => ({ Shell: () => null }))

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
  db.getAllSessions.mockReset()
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

// #384 ticket 03 retired the remembered view (localStorage `sapphire_view`): the address is the memory. Its cases moved
// to App.drives.test.tsx ("the remembered-view key is retired", the address cases).
describe('loading the drives', () => {
  it('an IndexedDB failure is logged, not thrown', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    db.getAllSessions.mockRejectedValue(new Error('blocked'))
    vi.resetModules()
    const { default: App } = await import('./App')
    render(<App />)
    await waitFor(() => expect(err).toHaveBeenCalledWith('[Sapphire] Failed to load drives from IndexedDB:', expect.any(Error)))
    err.mockRestore()
  })
})
