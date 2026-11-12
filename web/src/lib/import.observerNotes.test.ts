import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { importZip } from './import'
import { observerNotes } from './observerNotes'

// Synthetic exports only: coordinates, times and texts are made up for the test.
async function zipOf(files: Record<string, string>): Promise<File> {
  const zip = new JSZip()
  for (const [n, c] of Object.entries(files)) zip.file(n, c)
  return new File([await zip.generateAsync({ type: 'blob' })], 'synthetic.zip', { type: 'application/zip' })
}

const CSV = ['timestamp_ms,lat,lng,rpm,speedKph', '1000000,10.0,20.0,2000,50', '1001000,10.001,20.001,2100,52'].join('\n')
const profile = (bookmarks: unknown[]) => JSON.stringify({
  version: 2, drive: { startTime: 1000000, endTime: 1060000 }, peaks: [], thermalProgression: {}, modeTimeline: [], bookmarks,
})
const BASE = { timestamp: 1012500, lat: 10.0005, lng: 20.0005, speedKph: 61, rpm: 2400 }

describe('observer notes in an imported export', () => {
  it('a bookmark keeps its drive-relative time and kind', async () => {
    const s = await importZip(await zipOf({
      'drive_20260101_000000.csv': CSV,
      'drive_profile_20260101_000000.json': profile([
        { ...BASE, label: 'Cluster showed SPORT', kind: 'observer_note', relMs: 12500 },
        { ...BASE, timestamp: 1000000, label: '', kind: 'observer_note', relMs: 0 },
      ]),
    }))
    const bm = s.trip!.bookmarks!
    expect(bm[0]).toMatchObject({ label: 'Cluster showed SPORT', kind: 'observer_note', relMs: 12500 })
    expect(bm[1].relMs).toBe(0)            // a note at the very start is 0, not "absent"
  })

  it('an older export without the two fields imports as before: absent stays absent', async () => {
    const s = await importZip(await zipOf({
      'drive_20260101_000000.csv': CSV,
      'drive_profile_20260101_000000.json': profile([{ ...BASE, label: 'Peak corner' }]),
    }))
    const bm = s.trip!.bookmarks![0]
    expect(bm.label).toBe('Peak corner')
    expect('relMs' in bm).toBe(false)
    expect('kind' in bm).toBe(false)
  })

  it('a relMs that is not a number is dropped, not turned into 0', async () => {
    const s = await importZip(await zipOf({
      'drive_20260101_000000.csv': CSV,
      'drive_profile_20260101_000000.json': profile([{ ...BASE, label: 'x', relMs: 'soon' }]),
    }))
    expect('relMs' in s.trip!.bookmarks![0]).toBe(false)
  })

  it('NOTE session events survive the import with their time', async () => {
    const s = await importZip(await zipOf({
      'drive_20260101_000000.csv': CSV,
      'diagnostic_detail_20260101_000000.json': JSON.stringify({
        sessionEvents: [
          { relMs: 500, type: 'STATE', message: 'Connected' },
          { relMs: 24000, type: 'NOTE', message: 'Observer note: Cluster showed SPORT' },
        ],
      }),
    }))
    expect(observerNotes(s.diagnostics!.sessionEvents)).toEqual([{ ts: 24000, text: 'Cluster showed SPORT', onMap: true }])
  })
})
