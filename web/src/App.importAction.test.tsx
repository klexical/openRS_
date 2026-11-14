import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import JSZip from 'jszip'

// #384 ticket 06 at the page: Import is an action, not a place. The whole App over an in-memory IndexedDB
// (fake-indexeddb) and the real storage and import modules; only the map is stubbed. Invented data only: each ZIP is
// built here, 2096, invented coordinates.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

const T0 = 4_000_000_000_000
const HEADER = '# openrs_csv_v2\ntimestamp_ms,lat,lng,speed_kph,rpm'
/** A drive CSV whose points differ by [n] (a different drive per n). */
const csv = (n: number) => `${HEADER}\n${T0 + n * 1e6},${10 + n / 100},20,40,3000\n${T0 + n * 1e6 + 1000},${10 + n / 100 + 0.001},20,42,3100`
const BAD_ROW = `${HEADER}\n${T0},10,20,40,3000\n${T0 + 1000},10,001,20,42,3100`          // 6 fields: a decimal comma

async function zipOf(name: string, files: [string, string][]): Promise<File> {
  const zip = new JSZip()
  for (const [n, text] of files) zip.file(n, text)
  return new File([await zip.generateAsync({ type: 'blob' })], name, { type: 'application/zip' })
}
const single = (n: number, name = `drive-${n}.zip`) => zipOf(name, [[`drive_2096010${n}_120000.csv`, csv(n)]])

async function boot() {
  window.history.replaceState(null, '', '/')
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
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

async function list() {
  const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
  await waitFor(() => expect(root).toHaveAttribute('aria-busy', 'false'), { timeout: 3000 })
  return root
}
async function start() {
  const booted = await boot()
  render(<booted.App />)
  return { ...booted, root: await list() }
}
/** Choose files with the list's Import button (its file input). */
async function choose(files: File[]) {
  const input = document.querySelector('input[type="file"]#file-input') as HTMLInputElement
  expect(input.multiple).toBe(true)
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  await act(async () => { fireEvent.change(input) })
}
/** Drop files on the page (anywhere: the event goes to whatever is under the pointer). */
async function drop(target: Element, files: File[]) {
  const dataTransfer = { files, types: ['Files'], items: files.map((f) => ({ kind: 'file', type: f.type, getAsFile: () => f })), dropEffect: 'copy' }
  fireEvent.dragEnter(target, { dataTransfer })
  expect(fireEvent.dragOver(target, { dataTransfer })).toBe(false)              // cancelled: the browser does not open the file
  await act(async () => { fireEvent.drop(target, { dataTransfer }) })
}
const status = () => screen.getByRole('region', { name: 'Import' })
const done = () => waitFor(() => expect(status().textContent).toMatch(/Done/), { timeout: 5000 })

describe('#384 ticket 06: Import is an action', () => {
  it('there is no Import place; the Import button takes several files', async () => {
    const { root } = await start()
    expect(within(screen.getByRole('navigation', { name: 'Places' })).queryByRole('link', { name: /Import/ })).toBeNull()
    expect(within(root).getByRole('button', { name: 'Import' })).toBeInTheDocument()
    expect((document.querySelector('#file-input') as HTMLInputElement).multiple).toBe(true)
  })

  it('dropping two export ZIPs anywhere on the page stores both, and the list marks the new rows', async () => {
    const { useStore } = await start()
    await act(async () => { window.location.hash = '#/settings' })
    await screen.findByRole('heading', { level: 1, name: 'Settings' })
    await drop(document.querySelector('main')!, [await single(1), await single(2)])
    await done()
    expect(useStore.getState().drives).toHaveLength(2)
    expect(window.location.hash).toBe('#/')                                         // several: the list
    const root = await list()
    const rows = within(root).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    for (const r of rows) expect(within(r).getByText(/New/, { selector: '.a-marker' })).toBeInTheDocument()
  })

  it('one import lands on the new drive', async () => {
    const { useStore } = await start()
    await choose([await single(3)])
    await done()
    const id = useStore.getState().drives[0].id
    await waitFor(() => expect(window.location.hash).toBe(`#/drive/${id}`))
  })

  it('three files, the second damaged: two drives stored, the second named with its file and reason', async () => {
    const { useStore } = await start()
    const bad = await zipOf('damaged.zip', [['drive_20960102_120000.csv', BAD_ROW]])
    await choose([await single(1), bad, await single(3)])
    await done()
    expect(useStore.getState().drives).toHaveLength(2)
    expect(status().textContent).toContain('damaged.zip: CSV drive_20960102_120000.csv row 4: expected 5 fields, found 6')
    expect(window.location.hash).toBe('#/')                                         // two stored: the list, the failure kept in view
  })

  it('inside one ZIP all of it is stored or none of it: a batch with one bad drive stores nothing', async () => {
    const { useStore } = await start()
    const batch = await zipOf('batch.zip', [['drive_1_20960101_120000.csv', csv(1)], ['drive_2_20960101_130000.csv', BAD_ROW]])
    await choose([batch])
    await done()
    expect(useStore.getState().drives).toHaveLength(0)
    expect(status().textContent).toMatch(/batch\.zip: CSV drive_2_20960101_130000\.csv row 4/)
  })

  it('a file that is not a ZIP, and a Sapphire backup, are named with a reason', async () => {
    const { useStore } = await start()
    const backup = await zipOf('sapphire-backup-2096-01-01.zip', [['manifest.json', JSON.stringify({ format: 'sapphire-backup', version: 1, drives: 0 })]])
    await choose([new File(['not a zip'], 'notes.txt'), backup])
    await done()
    expect(status().textContent).toContain('notes.txt: not a .zip file')
    expect(status().textContent).toContain('sapphire-backup-2096-01-01.zip: this is a Sapphire backup: restore it in Settings')
    expect(useStore.getState().drives).toHaveLength(0)
  })

  it('during a batch of five, the progress shows more than one value: files done of files chosen, and the stage', async () => {
    await start()
    const seen = new Set<string>()
    const stages = new Set<string>()
    const watch = new MutationObserver(() => {
      const bar = document.querySelector('[role="progressbar"]')
      if (bar) seen.add(`${bar.getAttribute('aria-valuenow')}/${bar.getAttribute('aria-valuemax')}`)
      const m = /(reading|parsing|storing)/.exec(document.querySelector('[data-import-stage]')?.textContent ?? '')
      if (m) stages.add(m[1])
    })
    watch.observe(document.body, { subtree: true, childList: true, attributes: true, characterData: true })
    await choose(await Promise.all([1, 2, 3, 4, 5].map((n) => single(n))))
    await done()
    watch.disconnect()
    expect([...seen].filter((v) => v.endsWith('/5')).length).toBeGreaterThan(1)
    expect([...stages].sort()).toEqual(['parsing', 'reading', 'storing'])
  })

  it('the same file twice leaves one drive and says "already imported" with a link to the drive', async () => {
    const { useStore } = await start()
    const file = await single(4, 'same.zip')
    await choose([file])
    await done()
    const id = useStore.getState().drives[0].id
    await act(async () => { window.location.hash = '#/' })
    await list()
    await choose([file])
    await done()
    expect(useStore.getState().drives).toHaveLength(1)
    expect(status().textContent).toMatch(/same\.zip: already imported/)
    expect(within(status()).getByRole('link', { name: /Trip/ })).toHaveAttribute('href', `#/drive/${id}`)
  })

  it('a later import that fills in a stored drive says so, and the open drive shows it', async () => {
    const { useStore } = await start()
    await choose([await single(5, 'first.zip')])
    await done()
    const id = useStore.getState().drives[0].id
    await waitFor(() => expect(useStore.getState().activeSession?.id).toBe(id))
    expect(useStore.getState().activeSession?.diagnostics ?? null).toBeNull()
    // the same drive again, now with its session log: dropped on the drive's own page
    const withLog = await zipOf('second.zip', [['drive_20960105_120000.csv', csv(5)], ['diagnostic_detail_20960105_120000.json',
      JSON.stringify({ sessionEvents: [{ ts: 0, type: 'SESSION', message: 'Started' }, { ts: 1, type: 'STATE', message: 'Up' }, { ts: 2, type: 'STATE', message: 'Polling' }] })]])
    await drop(document.querySelector('main')!, [withLog])
    await done()
    expect(status().textContent).toMatch(/second\.zip: already imported .*filled in: session log/)
    await waitFor(() => expect(useStore.getState().activeSession?.diagnostics?.sessionEvents).toHaveLength(3))
  })
})
