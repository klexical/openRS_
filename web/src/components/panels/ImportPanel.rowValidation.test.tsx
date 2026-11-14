import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, cleanup, fireEvent, waitFor } from '@testing-library/react'
import JSZip from 'jszip'
import { useStore } from '../../store'
import type { Session } from '../../types/session'
import { ImportPanel } from './ImportPanel'
import { toDriveRecord } from '../../lib/storage'
import { storeWithSessions } from '../../test/storeState'

// #204 through the panel: a drive with a bad row saves nothing (writes recorded, not a real database — scripts/idb-check
// covers IndexedDB itself)
const db = vi.hoisted(() => ({ storeImported: vi.fn() }))
vi.mock('../../lib/storage', async (importOriginal) => ({ ...(await importOriginal<object>()), storeImported: db.storeImported }))

const HEADER = '# openrs_csv_v2\ntimestamp_ms,lat,lng,speed_kph,rpm'
const VALID = `${HEADER}\n1000,0.5,0.5,40,3000\n2000,0.5001,0.5001,60,4200`
const BAD_ROW = `${HEADER}\n1000,0.5,0.5,40,3000\n2000,0,5001,0,5001,60,4200`   // decimal commas: 7 fields

async function zipFile(files: [string, string][]): Promise<File> {
  const zip = new JSZip()
  for (const [name, text] of files) zip.file(name, text)
  return new File([await zip.generateAsync({ type: 'blob' })], 'openrs_batch_20261102_120000.zip', { type: 'application/zip' })
}

const existing = (id: string, name: string) => ({ id, name, importedAt: 1, tags: [], trip: null, diagnostics: null,
  meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', exportedAt: '' } }) as unknown as Session

beforeEach(() => {
  db.storeImported.mockReset().mockImplementation(async (sessions: Session[]) => ({ stored: sessions.map(toDriveRecord), alreadyImported: [] }))
  useStore.setState({ ...storeWithSessions([existing('a', 'Earlier drive')], 'a'), activePanel: 'import' })
})
afterEach(cleanup)

describe('batch with a malformed drive row (#204)', () => {
  const VALID_NAME = 'drive_1_20261102_120000.csv', BAD_NAME = 'drive_2_20261102_130000.csv'
  for (const [order, files] of [
    ['bad drive first', [[BAD_NAME, BAD_ROW], [VALID_NAME, VALID]]],
    ['bad drive last', [[VALID_NAME, VALID], [BAD_NAME, BAD_ROW]]],
  ] as [string, [string, string][]][]) {
    it(`names the file and row and saves nothing (${order})`, async () => {
      const before = useStore.getState().drives
      const { container } = render(<ImportPanel />)
      const input = container.querySelector('#file-input') as HTMLInputElement
      Object.defineProperty(input, 'files', { value: [await zipFile(files)] })
      await act(async () => { fireEvent.change(input) })

      await waitFor(() => expect(container.textContent).toContain(`CSV ${BAD_NAME} row 4: expected 5 fields, found 7`))
      expect(container.textContent).not.toContain('Imported')
      expect(useStore.getState().drives).toBe(before)
      expect(useStore.getState().activeSessionId).toBe('a')
      expect(db.storeImported).not.toHaveBeenCalled()
    })
  }
})
