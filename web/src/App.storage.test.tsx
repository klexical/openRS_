import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen } from '@testing-library/react'
import { openDB } from 'idb'
import JSZip from 'jszip'
import type { Session } from './types/session'

// #384 ticket 01 at the page: today's screens on the new storage. The real storage module over an in-memory IndexedDB
// (fake-indexeddb); the shell is not under test, the panels are rendered on their own. Invented data only.

vi.mock('./components/layout/Shell', () => ({ Shell: () => null }))
vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))   // Leaflet needs a layout engine

const T0 = 4_000_000_000_000   // an invented time (2096)
const session = (id: string): Session => ({
  id, name: `Invented ${id}`, importedAt: T0, tags: [],
  // content differs per id: two drives with the same content are one drive ("already imported")
  trip: { points: [0, 1, 2].map((i) => ({ ts: T0 + i * 1000, lat: 10 + id.charCodeAt(0) / 1000, lng: 20, speedKph: 50, rpm: 2000, driveMode: 'Normal' })) as never,
    summary: { distanceKm: 1, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 50, peakRpm: 2000, peakBoostPsi: null, peakLatG: 0,
      peakLongG: 0, peakSpeedKph: 50, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 2000, avgFuelEconomy: 0, modeBreakdown: {},
      peakEvents: [] } },
  diagnostics: null,
  meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' },
})

async function writeV1(sessions: unknown[]) {
  const db = await openDB('sapphire', 1, { upgrade(d) { d.createObjectStore('sessions', { keyPath: 'id' }) } })
  for (const s of sessions) await db.put('sessions', s)
  db.close()
}

/** Fresh store, storage and App modules (the store reads the remembered view at load). */
async function modules() {
  vi.resetModules()
  const { useStore } = await import('./store')
  const storage = await import('./lib/storage')
  const { default: App } = await import('./App')
  return { useStore, storage, App }
}

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('#384 the storage messages, in plain words', () => {
  it('a failed upgrade says so, and that the drives are still there as they were', async () => {
    await writeV1([{ ...session('bad'), trip: { ...session('bad').trip!, points: 'not an array' } }])
    const { App } = await modules()
    render(<App />)
    await waitFor(() => expect(document.body.textContent).toMatch(/Sapphire couldn't update its storage/))
    expect(document.body.textContent).toMatch(/drives are still stored as they were/)
  })

  it('a blocked upgrade asks to close the other tabs, and continues by itself once they close', async () => {
    await writeV1([session('held')])
    const oldTab = await openDB('sapphire', 1)
    const { App, useStore } = await modules()
    render(<App />)
    await waitFor(() => expect(document.body.textContent).toContain("Close Sapphire's other tabs to finish the update"))
    oldTab.close()
    await waitFor(() => expect(useStore.getState().drives.map((d) => d.id)).toEqual(['held']))
    expect(document.body.textContent).not.toContain("Close Sapphire's other tabs")
  })

  it('an old tab told that Sapphire was updated says "reload"', async () => {
    const { App, useStore, storage } = await modules()
    await storage.storeImported([session('t')])
    render(<App />)
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(1))
    const newer = await openDB('sapphire', 3, { upgrade() {} })
    await waitFor(() => expect(document.body.textContent).toContain('Sapphire was updated: reload'))
    newer.close()
  })
})

describe('#384 ticket 02: backup and restore on the page', () => {
  // The controls load on demand (first-visit budget), hence findBy with a longer wait: the first transform of that
  // chunk can take over a second in a full run. jsdom has no object URLs: the download link's address is invented here; its file name is the app's
  beforeEach(() => { URL.createObjectURL = () => 'blob:invented'; URL.revokeObjectURL = () => {} })

  it('Settings backs up all drives as a download, and restores a backup with its count', async () => {
    const { useStore, storage } = await modules()
    const { SettingsPanel } = await import('./components/panels/SettingsPanel')
    await storage.storeImported([session('a'), session('b')])
    useStore.setState({ drives: await storage.listDrives() })
    const { container } = render(<SettingsPanel />)
    const button = await screen.findByText('Back up all drives', {}, { timeout: 5000 })   // outside act: act holds the lazy render
    await act(async () => { fireEvent.click(button) })
    const link = await waitFor(() => { const a = container.querySelector('a[download]'); expect(a).not.toBeNull(); return a! })
    expect(link.getAttribute('download')).toMatch(/^sapphire-backup-\d{4}-\d{2}-\d{2}\.zip$/)
    expect(container.textContent).toMatch(/2 drives/)

    const { makeBackup } = await import('./lib/backup')
    const file = new File([(await makeBackup()).blob], 'invented-backup.zip')
    await storage.clearAllDrives()
    useStore.setState({ drives: [] })                          // the list must come back from the restore itself
    const input = await screen.findByLabelText('Restore from a backup') as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [file] })
    await act(async () => { fireEvent.change(input) })
    await waitFor(() => expect(container.textContent).toMatch(/Restored 2 drives/))
    expect(useStore.getState().drives).toHaveLength(2)
  }, 15_000)

  it('after a failed upgrade, the notice offers a backup of the old drives first', async () => {
    await writeV1([{ ...session('bad'), trip: { ...session('bad').trip!, points: 'not an array' } }, session('good')])
    const { App } = await modules()
    render(<App />)
    await waitFor(() => expect(document.body.textContent).toMatch(/Sapphire couldn't update its storage/))
    const button = await screen.findByText('Back up the old drives', {}, { timeout: 5000 })   // outside act: act holds the lazy render
    await act(async () => { fireEvent.click(button) })
    const link = await waitFor(() => { const a = document.querySelector('a[download]'); expect(a).not.toBeNull(); return a! })
    expect(link.getAttribute('download')).toMatch(/^sapphire-backup-/)
  }, 15_000)
})

describe('#384 ticket 02: the controls\' chunk cannot load (a tab left open across a deploy)', () => {
  afterEach(() => { vi.doUnmock('./components/ui/BackupControls') })
  it('Settings says to reload, and the rest of the page stays', async () => {
    await modules()
    vi.doMock('./components/ui/BackupControls', () => { throw new Error('invented: chunk gone') })
    const { SettingsPanel } = await import('./components/panels/SettingsPanel')
    const { container } = render(<SettingsPanel />)
    await screen.findAllByText(/reload this tab/, {}, { timeout: 5000 })
    expect(container.textContent).toContain('Clear All Sessions')
  }, 15_000)
})

describe('#384 today\'s screens on the new storage', () => {
  it('opening a drive reads its points; leaving it frees them', async () => {
    const { App, useStore, storage } = await modules()
    await storage.storeImported([session('a'), session('b')])
    render(<App />)
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    expect(useStore.getState().drives.every((d) => !('trip' in d))).toBe(true)   // the list holds no points
    act(() => useStore.getState().setActiveSessionId('a'))
    await waitFor(() => expect(useStore.getState().activeSession?.trip?.points).toHaveLength(3))
    act(() => useStore.getState().setActiveSessionId(null))
    await waitFor(() => expect(useStore.getState().activeSession).toBeNull())
  })

  it('a drive that is selected but still opening never reads "No Session Selected" (Dashboard, Trip, Diagnostics)', async () => {
    const { useStore } = await modules()
    const { DashboardPanel } = await import('./components/panels/DashboardPanel')
    const { TripPanel } = await import('./components/panels/TripPanel')
    const { DiagnosticsPanel } = await import('./components/panels/DiagnosticsPanel')
    const { toDriveRecord } = await import('./lib/storage')
    useStore.setState({ drives: [toDriveRecord(session('a')), toDriveRecord(session('b'))], activeSessionId: 'b', activeSession: session('a') })
    for (const Panel of [DashboardPanel, TripPanel, DiagnosticsPanel]) {
      const { container, unmount } = render(<Panel />)
      expect(container.textContent).not.toContain('No Session Selected')
      expect(container.textContent).not.toContain('Invented a')                  // nor the drive that was open before
      unmount()
    }
  })

  it('importing a drive that is already stored says "already imported", stores nothing and never throws', async () => {
    const { useStore, storage } = await modules()
    const { ImportPanel } = await import('./components/panels/ImportPanel')
    const zip = new JSZip()
    zip.file('drive_20960101_000000.csv', '# openrs_csv_v2\ntimestamp_ms,lat,lng,speed_kph,rpm\n' +
      `${T0},10,20,40,3000\n${T0 + 1000},10.001,20,42,3100\n`)
    const file = new File([await zip.generateAsync({ type: 'blob' })], 'invented.zip')
    const drop = async () => {
      const { container, unmount } = render(<ImportPanel />)
      const input = container.querySelector('#file-input') as HTMLInputElement
      Object.defineProperty(input, 'files', { value: [file] })
      await act(async () => { fireEvent.change(input) })
      return { container, unmount }
    }
    const first = await drop()
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(1))
    first.unmount()
    const second = await drop()
    await waitFor(() => expect(second.container.textContent).toMatch(/already imported/i))
    expect(await storage.listDrives()).toHaveLength(1)
    expect(useStore.getState().drives).toHaveLength(1)
  })

  it('a rename shows at once in Compare (review H4: Compare reads its own copy of the drives)', async () => {
    const { useStore } = await modules()
    const { ComparePanel } = await import('./components/panels/ComparePanel')
    const { storeWithSessions } = await import('./test/storeState')
    const { toDriveRecord } = await import('./lib/storage')
    useStore.setState(storeWithSessions([session('a'), session('b')], null, ['a', 'b']))
    const { container } = render(<ComparePanel />)
    expect(container.textContent).toContain('Invented b vs Invented a')
    act(() => useStore.getState().updateDrive({ ...toDriveRecord(session('b')), name: 'Renamed b' }))
    expect(container.textContent).toContain('Renamed b vs Invented a')
  })

  it('"Clear all" empties all three stores', async () => {
    const { useStore, storage } = await modules()
    const { SettingsPanel } = await import('./components/panels/SettingsPanel')
    await storage.storeImported([session('p'), session('q')])
    useStore.setState({ drives: await storage.listDrives() })
    render(<SettingsPanel />)
    fireEvent.click(screen.getByText('Clear All Sessions'))
    await act(async () => { fireEvent.click(screen.getByText('Confirm')) })
    await waitFor(() => expect(useStore.getState().drives).toEqual([]))
    storage.closeStorage()
    const raw = await openDB('sapphire')
    for (const store of ['drives', 'points', 'diagnostics'] as const) expect(await raw.count(store)).toBe(0)
    raw.close()
  })
})
