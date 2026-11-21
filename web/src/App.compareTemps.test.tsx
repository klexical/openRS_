import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, act, screen, waitFor, within } from '@testing-library/react'
import JSZip from 'jszip'
import type { Session } from './types/session'

// #414 at the whole app: Compare over the database stand-in (fake-indexeddb) and the real storage module; only the map is
// stubbed. A drive without coolant or oil readings is stored with the import's −99 "no reading" marker as its peak, and
// Compare shows no Peak Coolant or Peak Oil figure for it (since #416 ticket 3 the cards stay, with "—" for it). Invented data only, dated 2096.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

const T0 = 4_000_000_000_000
function drive(id: string, n: number, peakCoolantC: number, peakOilTempC: number): Session {
  return {
    id, name: `Invented ${id}`, importedAt: T0 + n, tags: [],
    trip: { points: [0, 1, 2].map((i) => ({ ts: T0 + n * 1e7 + i * 1000, lat: 10 + n / 100, lng: 20 + i / 1000, speedKph: 40 + i, rpm: 3000 + i })) as never,
      summary: { distanceKm: 1, durationMs: 2000, fuelUsedL: 0, avgSpeedKph: 41, peakRpm: 3002 + n, peakBoostPsi: null, peakLatG: 0,
        peakLongG: 0, peakSpeedKph: 42, peakCoolantC, peakOilTempC, avgRpm: 3001, avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [] } },
    diagnostics: null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: new Date(T0 + n * 1e7).toISOString(), generatedAt: '' },
  }
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
  localStorage.setItem('sapphire_whats_new_seen', 'slice-2')
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
  window.history.replaceState(null, '', '/')
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

/** The whole app, Compare open on [ids] (the first is the Base drive), its figures drawn. */
async function comparing(drives: Session[], ids: string[]) {
  vi.resetModules()
  const storage = await import('./lib/storage')
  await storage.storeImported(drives)
  const { useStore } = await import('./store')
  const { default: App } = await import('./App')
  render(<App />)
  await act(async () => { useStore.getState().setCompare(ids); useStore.getState().setCompareOpen(true) })
  await screen.findByText(`Invented ${ids[1]} vs Invented ${ids[0]}`, {}, { timeout: 5000 })
  await screen.findByText('Peak RPM')
}

/** An invented single-drive export: three points, with coolant and oil columns or without them. */
async function exportZip(n: number, temps: boolean): Promise<File> {
  const t0 = T0 + n * 1e7
  const head = temps ? 'timestamp_ms,lat,lng,speed_kph,rpm,coolant_c,oil_c' : 'timestamp_ms,lat,lng,speed_kph,rpm'
  const rows = [0, 1, 2].map((i) => `${t0 + i * 1000},${10 + n / 100},${20 + i / 1000},${40 + i},${3000 + i}${temps ? `,${88 + i},${100 + i}` : ''}`)
  const zip = new JSZip()
  zip.file(`drive_invented_${n}.csv`, ['# openrs_csv_v2', head, ...rows].join('\n'))
  return new File([await zip.generateAsync({ type: 'blob' })], `temps-${n}.zip`, { type: 'application/zip' })
}

describe('#414: Compare and a drive without coolant or oil readings', () => {
  it('imported from an export without coolant or oil columns, it shows no Peak Coolant or Peak Oil figure', async () => {
    vi.resetModules()
    const { useStore } = await import('./store')
    const { startImport } = await import('./components/importing/startImport')
    const { default: App } = await import('./App')
    render(<App />)
    await act(async () => { await startImport([await exportZip(1, true), await exportZip(2, false)]) })
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2), { timeout: 5000 })
    const byName = (part: string) => useStore.getState().drives.find((d) => d.name.includes(part))!
    const [withTemps, without] = [byName('temps-1'), byName('temps-2')]
    expect(without.summary?.peakCoolantC).toBe(-99)                       // the import's marker, stored as it is
    expect(without.summary?.peakOilTempC).toBe(-99)
    await act(async () => { useStore.getState().setCompare([withTemps.id, without.id]); useStore.getState().setCompareOpen(true) })
    await screen.findByText(`${without.name} vs ${withTemps.name}`, {}, { timeout: 5000 })
    await screen.findByText('Peak RPM')
    // #416 ticket 3: the cards stay, with no figure for the drive without the reading
    expect(within(screen.getByRole('group', { name: 'Peak Coolant' })).getByTestId('figure').textContent).toBe('—')
    expect(within(screen.getByRole('group', { name: 'Peak Oil' })).getByTestId('figure').textContent).toBe('—')
  })


  it('compared with a drive that has them, it shows no Peak Coolant or Peak Oil figure (the cards stay: #416 ticket 3)', async () => {
    await comparing([drive('a', 1, -99, -99), drive('b', 2, 92, 104)], ['b', 'a'])
    expect(within(screen.getByRole('group', { name: 'Peak Coolant' })).getByTestId('figure').textContent).toBe('—')
    expect(within(screen.getByRole('group', { name: 'Peak Oil' })).getByTestId('figure').textContent).toBe('—')
    expect(document.body.textContent).not.toMatch(/-99/)
  })

  it('two drives with real peaks keep both figures', async () => {
    await comparing([drive('a', 1, 90, 101), drive('b', 2, 92, 104)], ['b', 'a'])
    expect(screen.getByText('Peak Coolant')).toBeInTheDocument()
    expect(screen.getByText('Peak Oil')).toBeInTheDocument()
  })
})
