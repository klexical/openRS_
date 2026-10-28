import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import JSZip from 'jszip'
import { importZip, importBatchZip, ImportError } from './import'

// Synthetic drive exported by the rc.7 builders under pl_PL (#211) — see __fixtures__/android-export-locale/README.md
const DIR = `${__dirname}/__fixtures__/android-export-locale/`
const TS = '20231114_221320'
const read = (name: string) => readFileSync(DIR + name, 'utf8')
const CSV = read(`drive_${TS}.csv`)

// What openRS_ before rc.7 wrote for the same rows on a decimal-comma phone: every decimal point became a comma.
const corrupt = (csv: string) => csv.split('\n')
  .map((l, i) => (i < 2 ? l : l.replace(/(\d)\.(\d)/g, '$1,$2')))
  .join('\n')

async function zipFile(files: Record<string, string>, name = 'export.zip'): Promise<File> {
  const zip = new JSZip()
  for (const [f, text] of Object.entries(files)) zip.file(f, text)
  return new File([await zip.generateAsync({ type: 'blob' })], name, { type: 'application/zip' })
}

describe('decimal-comma locale exports (#204, #211)', () => {
  it('a corrected pl_PL export imports with the exported values', async () => {
    const s = await importZip(await zipFile({
      [`drive_${TS}.csv`]: CSV, [`drive_${TS}.gpx`]: read(`drive_${TS}.gpx`), [`drive_knock_${TS}.csv`]: read(`drive_knock_${TS}.csv`),
    }))
    const pts = s.trip!.points
    expect(pts).toHaveLength(3)                         // the knock CSV is not a drive
    expect(pts[0].lat).toBeCloseTo(52.520008, 6)
    expect(pts[0].lng).toBeCloseTo(13.404954, 6)
    expect(pts.map((p) => p.speedKph)).toEqual([50.25, 60.25, 70.25])
    expect(pts.map((p) => p.rpm)).toEqual([3000, 3500, 4000])
    expect(pts[0].boostPsi).toBe(15.75)
    expect(pts[0].longG).toBeCloseTo(-0.4321, 4)
  })

  it('a decimal-comma corrupted drive CSV is rejected with its filename and row, never imported', async () => {
    const bad = corrupt(CSV)
    expect(bad.split('\n')[2].split(',').length).toBeGreaterThan(CSV.split('\n')[1].split(',').length)
    const err = await importZip(await zipFile({ [`drive_${TS}.csv`]: bad })).catch((e) => e)
    expect(err).toBeInstanceOf(ImportError)
    expect(err.message).toMatch(new RegExp(`drive_${TS}\\.csv row 3: expected 35 fields, found \\d+`))
  })

  it('a batch with one corrupted drive is rejected as a whole, in either order', async () => {
    const good = `drive_1_${TS}.csv`, badName = `drive_2_20231114_231320.csv`
    for (const files of [{ [good]: CSV, [badName]: corrupt(CSV) }, { [badName]: corrupt(CSV), [good]: CSV }]) {
      const err = await importBatchZip(await zipFile(files, 'batch.zip')).catch((e) => e)
      expect(err).toBeInstanceOf(ImportError)
      expect(err.message).toContain(`${badName} row 3`)
    }
  })

  it('a short row and a non-numeric timestamp / rpm are rejected', async () => {
    const lines = CSV.trim().split('\n')
    const short = [...lines.slice(0, 3), lines[3].split(',').slice(0, 20).join(','), lines[4]].join('\n')
    await expect(importZip(await zipFile({ [`drive_${TS}.csv`]: short }))).rejects.toThrow(/row 4: expected 35 fields, found 20/)
    const nan = [...lines.slice(0, 2), lines[2].replace(/^\d+,/, 'abc,'), ...lines.slice(3)].join('\n')
    await expect(importZip(await zipFile({ [`drive_${TS}.csv`]: nan }))).rejects.toThrow(/row 3: timestamp_ms "abc" is not a number/)
  })

  it('CRLF line endings and blank lines still import', async () => {
    const crlf = CSV.trim().split('\n').join('\r\n') + '\r\n\r\n'
    const s = await importZip(await zipFile({ [`drive_${TS}.csv`]: crlf }))
    expect(s.trip!.points).toHaveLength(3)
  })
})
