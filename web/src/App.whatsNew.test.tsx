import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import { openDB } from 'idb'
import type { Session } from './types/session'

// #384 ticket 09, "What's new": shown once to someone who had Sapphire before this update (their storage was there
// already, or was upgraded from version 1), never on a fresh install, never twice, not again after Clear all, and
// always reachable from Settings. The whole App over an in-memory IndexedDB. Invented data only, dated 2096.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

const T0 = 4_000_000_000_000
const session = (id: string): Session => ({
  id, name: `Invented ${id}`, importedAt: T0, tags: [],
  trip: { points: [0, 1, 2].map((i) => ({ ts: T0 + i * 1000, lat: 10, lng: 20 + i / 1000, speedKph: 40, rpm: 3000 })) as never,
    summary: { distanceKm: 1, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 41, peakRpm: 3000, peakBoostPsi: null, peakLatG: 0,
      peakLongG: 0, peakSpeedKph: 42, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 3000, avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [] } },
  diagnostics: null, meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: new Date(T0).toISOString(), generatedAt: '' },
})

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}
async function boot(hash = '') {
  cleanup()
  window.history.replaceState(null, '', `/${hash}`)
  vi.resetModules()
  const { useStore } = await import('./store')
  const storage = await import('./lib/storage')
  const { default: App } = await import('./App')
  render(<App />)
  return { useStore, storage }
}
/** A browser that had Sapphire before this update: its storage holds drives (stored by an earlier visit). */
async function earlierVisit() {
  vi.resetModules()
  const storage = await import('./lib/storage')
  await storage.storeImported([session('a')])
  storage.closeStorage()
}
async function list() {
  const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
  await waitFor(() => expect(root).toHaveAttribute('aria-busy', 'false'), { timeout: 3000 })
}
const whatsNew = () => screen.queryByRole('dialog', { name: "What's new" })
beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

describe('#384 ticket 09: "What\'s new" is shown once after the update, not on a fresh install', () => {
  it('a fresh install: not shown, and not after a reload either', async () => {
    await boot()
    await list()
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    expect(whatsNew()).toBeNull()
    await boot()
    await list()
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    expect(whatsNew()).toBeNull()
  })

  it('someone who had Sapphire before: shown once, over the list, with this slice\'s changes; not a second time', async () => {
    await earlierVisit()
    await boot()
    const dialog = await screen.findByRole('dialog', { name: "What's new" }, { timeout: 3000 })
    expect(within(dialog).getByText('Home is the list of drives, with addresses')).toBeInTheDocument()     // web/CHANGELOG.md
    expect(screen.getByRole('region', { name: 'Drives' })).toBeInTheDocument()                             // the list is behind it
    fireEvent.click(within(dialog).getByRole('button', { name: 'Got it' }))
    expect(whatsNew()).toBeNull()
    await boot()
    await list()
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    expect(whatsNew()).toBeNull()
  })

  it('a browser upgraded from storage version 1 is shown it too', async () => {
    const db = await openDB('sapphire', 1, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
    await db.put('sessions', session('v1'))
    db.close()
    await boot()
    expect(await screen.findByRole('dialog', { name: "What's new" }, { timeout: 3000 })).toBeInTheDocument()
  })

  it('not over a storage upgrade that failed: the page says so instead, and nothing is marked seen', async () => {
    const db = await openDB('sapphire', 1, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
    await db.put('sessions', { ...session('bad'), trip: { ...session('bad').trip!, points: 'not an array' } })   // cannot convert
    db.close()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await boot()
    await waitFor(() => expect(document.body.textContent).toMatch(/Sapphire couldn't update its storage/), { timeout: 3000 })
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    warn.mockRestore()
    expect(whatsNew()).toBeNull()
    expect(localStorage.getItem('sapphire_whats_new_seen')).toBeNull()
  })

  it('always reachable from Settings, also after it was dismissed', async () => {
    await boot('#/settings')
    fireEvent.click(await screen.findByRole('button', { name: "What's new" }, { timeout: 3000 }))
    const dialog = await screen.findByRole('dialog', { name: "What's new" }, { timeout: 3000 })
    expect(within(dialog).getByText('Home is the list of drives, with addresses')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Got it' }))
    expect(whatsNew()).toBeNull()
  })

  it('not shown again after Clear all: its remembered answer is a setting', async () => {
    await earlierVisit()
    await boot()
    fireEvent.click(within(await screen.findByRole('dialog', { name: "What's new" }, { timeout: 3000 })).getByRole('button', { name: 'Got it' }))
    await act(async () => { window.location.hash = '#/settings' })
    const panel = await screen.findByRole('region', { name: 'Your drives' }, { timeout: 3000 })
    fireEvent.click(within(panel).getByRole('button', { name: 'Clear all' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Skip the backup' }))
    await act(async () => { fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Clear all' })) })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await boot()
    await list()
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    expect(whatsNew()).toBeNull()
  })
})
