import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import { openDB } from 'idb'
import JSZip from 'jszip'

// #384 ticket 07, "Keep my data, and Clear all": the whole App over an in-memory IndexedDB (fake-indexeddb), with the
// browser's storage manager (navigator.storage: persist, persisted, estimate) replaced by an invented one per test.
// Invented data only, dated 2096.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

const T0 = 4_000_000_000_000
const HEADER = '# openrs_csv_v2\ntimestamp_ms,lat,lng,speed_kph,rpm'
const csv = (n: number) => `${HEADER}\n${T0 + n * 1e6},${10 + n / 100},20,40,3000\n${T0 + n * 1e6 + 1000},${10 + n / 100 + 0.001},20,42,3100`
async function single(n: number): Promise<File> {
  const zip = new JSZip()
  zip.file(`drive_invented_${n}.csv`, csv(n))
  return new File([await zip.generateAsync({ type: 'blob' })], `drive-${n}.zip`, { type: 'application/zip' })
}
const MAP_SENTENCE = 'The map background comes from a public map server (OpenStreetMap). It sees the area shown, which for a drive is where it went, and your internet address. No readings from a drive are sent.'
const CLEAR_SENTENCE = 'Removes every drive and session from this browser. Your settings stay.'

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
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}
/** The browser's storage manager, invented: what it answers, and each call counted. */
function storageManager(answers: { persisted?: boolean; persistAnswer?: boolean; usage?: number; quota?: number } = {}) {
  const sm = {
    persist: vi.fn(async () => answers.persistAnswer ?? true),
    persisted: vi.fn(async () => answers.persisted ?? false),
    estimate: vi.fn(async () => ({ usage: answers.usage ?? 0, quota: answers.quota ?? 0 })),
  }
  Object.defineProperty(window.navigator, 'storage', { value: sm, configurable: true })
  return sm
}
const proto = (globalThis as unknown as { IDBObjectStore: { prototype: Record<string, (...a: unknown[]) => unknown> } }).IDBObjectStore.prototype
const originals: [string, (...a: unknown[]) => unknown][] = []
function failOn(method: 'clear', store: string) {
  const orig = proto[method]
  originals.push([method, orig])
  proto[method] = function (this: { name: string }, ...a: unknown[]) {
    if (this.name === store) throw new DOMException('invented failure', 'UnknownError')
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
  delete (window.navigator as unknown as { storage?: unknown }).storage
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
  await waitFor(() => expect(screen.getByRole('region', { name: 'Import' }).textContent).toMatch(/Done/), { timeout: 3000 })
}
async function seed(n: number[]) {
  vi.resetModules()
  const storage = await import('./lib/storage')
  const { importZip } = await import('./lib/import')
  storage.closeStorage()
  for (const k of n) await storage.storeImported([await importZip(await single(k))])
  storage.closeStorage()
}
async function counts() {
  const raw = await openDB('sapphire')
  try { return Promise.all((['drives', 'points', 'diagnostics'] as const).map((s) => raw.count(s))) } finally { raw.close() }
}
const dataPanel = () => screen.findByRole('region', { name: 'Your drives' }, { timeout: 3000 })
async function openClearAll() {
  fireEvent.click(within(await dataPanel()).getByRole('button', { name: 'Clear all' }))
  return screen.getByRole('dialog')
}

describe('#384 ticket 07: the browser is asked to keep the data once, after the first import that stores a drive', () => {
  it('asked once: not again on a later import, nor after a reload', async () => {
    const sm = storageManager()
    await boot()
    await list()
    expect(sm.persist).not.toHaveBeenCalled()                                    // not on a first visit
    await choose([await single(1)])
    await waitFor(() => expect(sm.persist).toHaveBeenCalledTimes(1))
    await act(async () => { window.location.hash = '#/' })
    await list()
    await choose([await single(2)])
    await boot()                                                                  // a reload: the answer is remembered
    await list()
    await choose([await single(3)])
    expect(sm.persist).toHaveBeenCalledTimes(1)
  })

  it('a second import while the browser has not answered yet does not ask again', async () => {
    const sm = storageManager()
    let answer: (kept: boolean) => void = () => {}
    sm.persist.mockImplementation(() => new Promise<boolean>((r) => { answer = r }))     // the browser takes its time
    await boot()
    await list()
    await choose([await single(1)])
    await waitFor(() => expect(sm.persist).toHaveBeenCalledTimes(1))
    await act(async () => { window.location.hash = '#/' })
    await list()
    await choose([await single(2)])                                              // the first answer is still pending
    answer(true)
    expect(sm.persist).toHaveBeenCalledTimes(1)
  })

  it('an import that stores no drive does not ask', async () => {
    const sm = storageManager()
    await boot()
    await list()
    await choose([new File(['not a zip'], 'notes.txt')])
    expect(sm.persist).not.toHaveBeenCalled()
  })
})

describe('#384 ticket 07: Settings says, in words, whether the browser keeps the data, and about how much space is used', () => {
  it('opening Settings reads the answer without asking: kept, and the space figure labelled "about"', async () => {
    const sm = storageManager({ persisted: true, usage: 14_200_000, quota: 2_000_000_000 })
    await boot('#/settings')
    const panel = await dataPanel()
    await waitFor(() => expect(panel.textContent).toContain('Kept: this browser keeps Sapphire\'s drives and will not clear them to free space.'))
    expect(panel.textContent).toContain('About 14 MB used, of about 2 GB this browser allows.')
    expect(sm.persisted).toHaveBeenCalled()
    expect(sm.persist).not.toHaveBeenCalled()
  })

  it('a browser that said no is reported as no, in words', async () => {
    storageManager({ persisted: false, usage: 500_000, quota: 1_500_000_000 })
    await boot('#/settings')
    const panel = await dataPanel()
    await waitFor(() => expect(panel.textContent).toContain('Not kept: this browser may clear Sapphire\'s drives when space runs low. A backup keeps a copy.'))
    expect(panel.textContent).toContain('About 0.5 MB used, of about 1.5 GB this browser allows.')
  })

  it('a browser without a storage manager is not guessed at', async () => {
    await boot('#/settings')
    const panel = await dataPanel()
    // the words exactly, and the summary says "not said": nothing is guessed
    expect(await within(panel).findByText('This browser does not say whether it keeps Sapphire\'s drives, or how much space they use.')).toBeInTheDocument()
    expect(within(panel).getByText('Kept').nextElementSibling?.textContent).toBe('Not said')
  })

  it('almost no space used is said as such, not rounded up to a figure', async () => {
    storageManager({ persisted: true, usage: 0, quota: 2_000_000_000 })
    await boot('#/settings')
    const panel = await dataPanel()
    await waitFor(() => expect(panel.textContent).toContain('Almost no space used, of about 2 GB this browser allows.'))
  })

  it('Settings says where the map background comes from, word for word', async () => {
    await boot('#/settings')
    expect(await screen.findByText(MAP_SENTENCE, {}, { timeout: 3000 })).toBeInTheDocument()
  })
})

describe('#384 ticket 07: Clear all asks, offers the backup first, and empties all three stores in one transaction', () => {
  it('step one offers the backup (or skip); step two says the sentence; Clear all empties the three stores', async () => {
    await seed([1, 2])
    const { useStore } = await boot('#/settings')
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    const dialog = await openClearAll()
    expect(within(dialog).getByRole('button', { name: 'Back up all drives' })).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Clear all' })).toBeNull()          // the backup comes first
    expect(await counts()).toEqual([2, 2, 2])
    fireEvent.click(within(dialog).getByRole('button', { name: 'Skip the backup' }))
    expect(within(dialog).getByText(CLEAR_SENTENCE)).toBeInTheDocument()
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Clear all' })) })
    await waitFor(() => expect(useStore.getState().drives).toEqual([]))
    expect(await counts()).toEqual([0, 0, 0])
    expect(screen.queryByRole('dialog')).toBeNull()
    expect((await dataPanel()).textContent).toContain('Every drive and session was removed from this browser.')
  })

  it('when one store cannot be cleared, nothing is cleared, and the dialog says so', async () => {
    await seed([1, 2])
    const { useStore } = await boot('#/settings')
    await waitFor(() => expect(useStore.getState().drives).toHaveLength(2))
    const dialog = await openClearAll()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Skip the backup' }))
    failOn('clear', 'diagnostics')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Clear all' })) })
    await waitFor(() => expect(within(dialog).getByRole('alert').textContent).toBe('Nothing was cleared: this browser\'s storage did not answer. Try again.'))
    warn.mockRestore()
    originals.splice(0).reverse().forEach(([m, orig]) => { proto[m] = orig })
    expect(await counts()).toEqual([2, 2, 2])
    expect(useStore.getState().drives).toHaveLength(2)
  })

  it('a backup made in step one counts only once its file is downloaded: until then the way on still says "Skip"', async () => {
    URL.createObjectURL = () => 'blob:invented'; URL.revokeObjectURL = () => {}
    await seed([1, 2])
    await boot('#/settings')
    const dialog = await openClearAll()
    await act(async () => { fireEvent.click(await within(dialog).findByRole('button', { name: 'Back up all drives' }, { timeout: 3000 })) })
    const link = await waitFor(() => { const a = dialog.querySelector('a[download]'); expect(a).not.toBeNull(); return a as HTMLAnchorElement })
    expect(within(dialog).getByRole('button', { name: 'Skip the backup' })).toBeInTheDocument()   // the file is not saved yet
    expect(within(dialog).queryByRole('button', { name: 'Continue' })).toBeNull()
    link.addEventListener('click', (e) => e.preventDefault())                     // jsdom cannot follow a download
    fireEvent.click(link)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Continue' }))
    expect(within(dialog).getByText('The backup file was downloaded.')).toBeInTheDocument()
  }, 15_000)

  it('step two opens with focus on Cancel, never on Clear all', async () => {
    await seed([1])
    await boot('#/settings')
    const dialog = await openClearAll()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Skip the backup' }))
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' }))
  })

  it('Cancel and Escape change nothing, and focus returns to Clear all', async () => {
    await seed([1])
    await boot('#/settings')
    let dialog = await openClearAll()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(within(await dataPanel()).getByRole('button', { name: 'Clear all' }))
    dialog = await openClearAll()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await counts()).toEqual([1, 1, 1])
  })

  it('after Clear all, the settings stay and the browser is not asked again about keeping the data', async () => {
    const sm = storageManager()
    await boot()
    await list()
    await choose([await single(1)])                                              // the first import: asked once
    await waitFor(() => expect(sm.persist).toHaveBeenCalledTimes(1))
    localStorage.setItem('sapphire_settings', JSON.stringify({ unitSystem: 'imperial' }))
    const before = Object.fromEntries(Array.from({ length: localStorage.length }, (_, i) => [localStorage.key(i)!, localStorage.getItem(localStorage.key(i)!)]))
    await act(async () => { window.location.hash = '#/settings' })
    const dialog = await openClearAll()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Skip the backup' }))
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Clear all' })) })
    await waitFor(async () => expect(await counts()).toEqual([0, 0, 0]))
    const after = Object.fromEntries(Array.from({ length: localStorage.length }, (_, i) => [localStorage.key(i)!, localStorage.getItem(localStorage.key(i)!)]))
    expect(after).toEqual(before)
    await act(async () => { window.location.hash = '#/' })
    await list()
    await choose([await single(2)])                                              // a first import again, after Clear all
    expect(sm.persist).toHaveBeenCalledTimes(1)
  })
})
