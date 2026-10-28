import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, cleanup, fireEvent, waitFor } from '@testing-library/react'
import JSZip from 'jszip'
import { useStore } from '../../store'
import type { Session } from '../../types/session'
import { ImportPanel } from './ImportPanel'

// IndexedDB writes are what "partially imported" would mean — record them instead.
const db = vi.hoisted(() => ({ putSession: vi.fn(), putSessions: vi.fn() }))
vi.mock('../../lib/db', () => ({ putSession: db.putSession, putSessions: db.putSessions }))

const MARKER = '# openrs_csv_v2'
const VALID = `${MARKER}\ntimestamp_ms,lat,lng,speed_kph,rpm\n1000,51.5,-0.12,40,3000\n2000,51.6,-0.13,60,4200`
const MALFORMED = `${MARKER}\nlat,lng,speed_kph\n51.5,-0.12,40\n51.6,-0.13,60`

async function zipFile(files: [string, string][]): Promise<File> {
  const zip = new JSZip()
  for (const [name, text] of files) zip.file(name, text)   // insertion order = ZIP order
  return new File([await zip.generateAsync({ type: 'blob' })], 'openrs_batch_20261026_120000.zip', { type: 'application/zip' })
}

const existing = (id: string, name: string) => ({ id, name, importedAt: 1, tags: [], trip: null, diagnostics: null,
  meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', exportedAt: '' } }) as unknown as Session

async function drop(file: File) {
  const { container } = render(<ImportPanel />)
  const input = container.querySelector('#file-input') as HTMLInputElement
  Object.defineProperty(input, 'files', { value: [file] })
  await act(async () => { fireEvent.change(input) })
  return container
}

beforeEach(() => {
  db.putSession.mockReset().mockResolvedValue(undefined)
  db.putSessions.mockReset().mockResolvedValue(undefined)
  useStore.setState({
    sessions: [existing('a', 'Earlier drive'), existing('b', 'Other drive')],
    activeSessionId: 'b',
    compareSessionIds: ['a', 'b'],
    activePanel: 'import',
  })
})

afterEach(cleanup)

describe('batch import is all-or-nothing', () => {
  const VALID_NAME = 'drive_1_20261026_120000.csv'
  const BAD_NAME = 'drive_2_20261026_130000.csv'

  for (const [order, files] of [
    ['valid drive first', [[VALID_NAME, VALID], [BAD_NAME, MALFORMED]]],
    ['malformed drive first', [[BAD_NAME, MALFORMED], [VALID_NAME, VALID]]],
  ] as [string, [string, string][]][]) {
    it(`rejects the whole batch (${order}) and leaves loaded sessions and selection unchanged`, async () => {
      const before = useStore.getState()
      const container = await drop(await zipFile(files))

      await waitFor(() => expect(container.textContent).toContain('missing required columns'))
      expect(container.textContent).toContain(`CSV ${BAD_NAME} is missing required columns: timestamp`)
      expect(container.textContent).toContain('rpm (one of: rpm)')
      expect(container.textContent).not.toContain('Imported')

      const after = useStore.getState()
      expect(after.sessions).toBe(before.sessions)
      expect(after.sessions.map((s) => s.id)).toEqual(['a', 'b'])
      expect(after.activeSessionId).toBe('b')
      expect(after.compareSessionIds).toEqual(['a', 'b'])
      expect(after.activePanel).toBe('import')
      expect(db.putSession).not.toHaveBeenCalled()
      expect(db.putSessions).not.toHaveBeenCalled()
    })
  }

  it('a valid batch is saved in one write and added together', async () => {
    const container = await drop(await zipFile([[VALID_NAME, VALID], ['drive_2_20261026_130000.csv', VALID]]))
    await waitFor(() => expect(container.textContent).toContain('Imported 2 drives'))
    expect(db.putSessions).toHaveBeenCalledTimes(1)
    expect(db.putSessions.mock.calls[0][0]).toHaveLength(2)
    expect(db.putSession).not.toHaveBeenCalled()
    expect(useStore.getState().sessions).toHaveLength(4)
  })

  it('a storage failure saves nothing and leaves the store unchanged', async () => {
    db.putSessions.mockRejectedValue(new Error('QuotaExceededError'))
    const before = useStore.getState()
    const container = await drop(await zipFile([[VALID_NAME, VALID], ['drive_2_20261026_130000.csv', VALID]]))
    await waitFor(() => expect(container.textContent).toContain('QuotaExceededError'))
    expect(useStore.getState().sessions).toBe(before.sessions)
    expect(useStore.getState().activeSessionId).toBe('b')
  })
})
