import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import JSZip from 'jszip'

// #384 delivery B, the fix round after the whole-delivery spec review (QA's rulings in REVIEW-06): lists announced as
// lists, a drive that cannot be read, storage failures in plain words, focus after cancelling "Delete selected", a drop
// while an import runs. The whole App over an in-memory IndexedDB (fake-indexeddb). Invented data only.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

const T0 = 4_000_000_000_000
const HEADER = '# openrs_csv_v2\ntimestamp_ms,lat,lng,speed_kph,rpm'
const csv = (n: number) => `${HEADER}\n${T0 + n * 1e6},${10 + n / 100},20,40,3000\n${T0 + n * 1e6 + 1000},${10 + n / 100 + 0.001},20,42,3100`
async function single(n: number, name = `drive-${n}.zip`): Promise<File> {
  const zip = new JSZip()
  zip.file(`drive_invented_${n}.csv`, csv(n))
  return new File([await zip.generateAsync({ type: 'blob' })], name, { type: 'application/zip' })
}

async function boot(hash = '') {
  window.history.replaceState(null, '', `/${hash}`)
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
const proto = (globalThis as unknown as { IDBObjectStore: { prototype: Record<string, (...a: unknown[]) => unknown> } }).IDBObjectStore.prototype
const originals: [string, (...a: unknown[]) => unknown][] = []
/** Make one IndexedDB call on one store throw, as the browser would. */
function failOn(method: 'get' | 'put', store: string, name: string, message: string) {
  const orig = proto[method]
  originals.push([method, orig])
  proto[method] = function (this: { name: string }, ...a: unknown[]) {
    if (this.name === store) throw new DOMException(message, name)
    return orig.apply(this, a)
  }
}
beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
})
afterEach(() => {
  for (const [m, orig] of originals.splice(0).reverse()) proto[m] = orig
  cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/')
})

async function list() {
  const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
  await waitFor(() => expect(root).toHaveAttribute('aria-busy', 'false'), { timeout: 3000 })
  return root
}
async function choose(files: File[]) {
  const input = document.querySelector('#file-input') as HTMLInputElement
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  await act(async () => { fireEvent.change(input) })
}
const status = () => screen.getByRole('region', { name: 'Import' })
const done = () => waitFor(() => expect(status().textContent).toMatch(/Done/), { timeout: 5000 })

describe('#384 fix round: lists styled without bullets still say they are lists (Safari / VoiceOver)', () => {
  it('each month of drives and the import outcome state role="list"', async () => {
    const { App } = await boot()
    render(<App />)
    await list()
    await choose([await single(1), await single(2), new File(['x'], 'notes.txt')])
    await done()
    const root = await list()
    const months = root.querySelectorAll('ul.a-rows')
    expect(months.length).toBeGreaterThan(0)
    for (const ul of months) expect(ul).toHaveAttribute('role', 'list')
    expect(status().querySelector('ul.a-import-lines')).toHaveAttribute('role', 'list')
  })
})

describe('#384 fix round: a drive that cannot be read', () => {
  it('says so with the way back, instead of opening for ever', async () => {
    const { storage } = await boot()
    const stored = await storage.storeImported([{ id: 'r', name: 'Readable record', importedAt: T0, tags: [],
      trip: { points: [{ ts: T0, lat: 10, lng: 20, speedKph: 40, rpm: 3000 }] as never, summary: { distanceKm: 0, durationMs: 0 } as never },
      diagnostics: null, meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' } }])
    storage.closeStorage()
    expect(stored.stored).toHaveLength(1)
    failOn('get', 'points', 'UnknownError', 'ENGINE TEXT: the read failed')
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { App } = await boot('#/drive/r')
    render(<App />)
    await waitFor(() => expect(document.querySelector('main')!.textContent).toContain("Couldn't open this drive. Try again."), { timeout: 3000 })
    const main = document.querySelector('main')!
    expect(main.textContent).not.toMatch(/ENGINE TEXT/)
    expect(within(main as HTMLElement).getByRole('link', { name: 'Back to the list' })).toHaveAttribute('href', '#/')
    expect(err).toHaveBeenCalled()                                             // the console keeps the detail
    err.mockRestore()
  })
})

describe('#384 fix round: a storage failure in plain words', () => {
  it('a full disk is named in plain words, never with the engine\'s own text, and the other files still import', async () => {
    const { App, useStore } = await boot()
    render(<App />)
    await list()
    const good = await single(3, 'good.zip')
    const full = await single(4, 'full.zip')
    // the second file's store fails as a browser out of room would
    const input = document.querySelector('#file-input') as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [good], configurable: true })
    await act(async () => { fireEvent.change(input) })
    await done()
    failOn('put', 'drives', 'QuotaExceededError', 'ENGINE TEXT: QuotaExceededError quota 0x8')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await act(async () => { window.location.hash = '#/' })
    await list()
    await choose([full])
    await done()
    warn.mockRestore()
    expect(status().textContent).toContain('full.zip: the browser has no room left to store it; nothing of this file was stored')
    expect(status().textContent).not.toMatch(/ENGINE TEXT|0x8/)
    expect(useStore.getState().drives).toHaveLength(1)
  })

  it('any other storage failure is named in plain words too', async () => {
    const { App } = await boot()
    render(<App />)
    await list()
    failOn('put', 'drives', 'UnknownError', 'ENGINE TEXT: Internal error opening backing store')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await choose([await single(5, 'other.zip')])
    await done()
    warn.mockRestore()
    expect(status().textContent).toContain('other.zip: the browser could not store it; nothing of this file was stored')
    expect(status().textContent).not.toMatch(/ENGINE TEXT|backing store/)
  })
})

describe('#384 fix round: focus after cancelling "Delete selected"', () => {
  it('returns to the "Delete selected" button', async () => {
    const { App } = await boot()
    render(<App />)
    await list()
    await choose([await single(6), await single(7)])
    await done()
    const root = await list()
    for (const box of within(root).getAllByRole('checkbox')) fireEvent.click(box)
    const del = within(root).getByRole('button', { name: 'Delete selected (2)' })
    fireEvent.click(del)
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(document.activeElement).toBe(within(root).getByRole('button', { name: 'Delete selected (2)' })))
  })
})

describe('#384 fix round: a drop while an import runs', () => {
  it('says so in one line and imports nothing more', async () => {
    const { App, useStore } = await boot()
    render(<App />)
    await list()
    const first = await single(8), second = await single(9, 'second.zip')
    const dt = (f: File) => ({ dataTransfer: { files: [f], types: ['Files'], items: [], dropEffect: 'copy' } })
    const main = document.querySelector('main')!
    await act(async () => {
      fireEvent.drop(main, dt(first))
      fireEvent.drop(main, dt(second))                       // the first is still running
    })
    await done()
    expect(status().textContent).toContain('An import was running: second.zip was not imported. Drop it again.')
    expect(useStore.getState().drives).toHaveLength(1)
  })
})

describe('#384 fix round, QA N1 (REVIEW-07): the Import button during an import, and the notes\' keys', () => {
  it('the Import button is disabled while an import runs, and enabled again after', async () => {
    const { App } = await boot()
    render(<App />)
    const root = await list()
    const input = document.querySelector('#file-input') as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [await single(10)], configurable: true })
    fireEvent.change(input)                                         // the import starts; it is still running here
    expect(within(root).getByRole('button', { name: 'Import' })).toBeDisabled()
    await done()
    await act(async () => { window.location.hash = '#/' })
    expect(within(await list()).getByRole('button', { name: 'Import' })).toBeEnabled()
  })

  it('the same file dropped twice during an import gives two lines without a duplicate key', async () => {
    const { App } = await boot()
    render(<App />)
    await list()
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const busy = await single(11, 'busy.zip')
    const dt = (f: File) => ({ dataTransfer: { files: [f], types: ['Files'], items: [], dropEffect: 'copy' } })
    const main = document.querySelector('main')!
    await act(async () => {
      fireEvent.drop(main, dt(await single(12)))
      fireEvent.drop(main, dt(busy))
      fireEvent.drop(main, dt(busy))
    })
    await done()
    const keyErrors = err.mock.calls.filter((c) => /same key/.test(String(c[0])))
    err.mockRestore()
    expect(keyErrors).toEqual([])
    expect(status().textContent!.match(/busy\.zip was not imported/g)).toHaveLength(2)
  })
})
