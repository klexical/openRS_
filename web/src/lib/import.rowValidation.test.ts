import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { importZip, importBatchZip, ImportError } from './import'

// #204 row checks on synthetic drives: positions are metres from 0.5° N 0.5° E (open sea), no recording involved.
const MARKER = '# openrs_csv_v2'
const HEADER = 'timestamp_ms,lat,lng,speed_kph,rpm,gear,boost_psi,coolant_c'
const ROWS = ['1700000000000,0.500000,0.500000,50.5,3000,3,12.250,90.5', '1700000001000,0.500100,0.500100,60.5,3500,3,13.500,91.0']

async function zipFile(files: [string, string][], name = 'export.zip'): Promise<File> {
  const zip = new JSZip()
  for (const [f, text] of files) zip.file(f, text)   // insertion order = ZIP order
  return new File([await zip.generateAsync({ type: 'blob' })], name, { type: 'application/zip' })
}
const one = (csv: string, name = 'drive_20261102_120000.csv') => zipFile([[name, csv]])
const rejection = (p: Promise<unknown>) => p.then(() => null, (e) => e)

describe('drive CSV rows (#204)', () => {
  it('a legacy CSV (no format line, camelCase columns) still imports with its values', async () => {
    const legacy = ['timestamp,lat,lng,speedKph,rpm,boostPsi', '1000,0.5,0.5,40,2500,5.5', '2000,0.5001,0.5001,45,2700,6.0'].join('\n')
    const pts = (await importZip(await one(legacy))).trip!.points
    expect(pts.map((p) => [p.ts, p.rpm, p.speedKph, p.boostPsi])).toEqual([[1000, 2500, 40, 5.5], [2000, 2700, 45, 6]])
  })

  it('no-GPS rows (empty lat / lng) still import; their telemetry is kept', async () => {
    const noGps = [MARKER, HEADER, ...ROWS.map((r) => r.replace(/^(\d+),[^,]*,[^,]*,/, '$1,,,'))].join('\n')
    const pts = (await importZip(await one(noGps))).trip!.points
    expect(pts.map((p) => [p.rpm, p.speedKph, p.boostPsi])).toEqual([[3000, 50.5, 12.25], [3500, 60.5, 13.5]])
  })

  it('a non-numeric or empty rpm is rejected with the file and row', async () => {
    for (const rpm of ['abc', '', '3,5']) {
      const bad = [MARKER, HEADER, ROWS[0], ROWS[1].replace(',3500,', `,${rpm},`)].join('\n')
      const err = await rejection(importZip(await one(bad)))
      expect(err).toBeInstanceOf(ImportError)
      expect(err.message).toContain('drive_20261102_120000.csv row 4:')
    }
  })

  it('the row number is the physical line, counting the format line and blank lines', async () => {
    const bad = [MARKER, HEADER, ROWS[0], '', ROWS[1].replace('1700000001000', '1,700')].join('\n')
    await expect(importZip(await one(bad))).rejects.toThrow(/drive_20261102_120000\.csv row 5: expected 8 fields, found 9/)
  })

  it('a batch with a bad row in its first or last drive is rejected as a whole', async () => {
    const good = [MARKER, HEADER, ...ROWS].join('\n')
    const bad = [MARKER, HEADER, ROWS[0], ROWS[1].replace('0.500100', '0,500100')].join('\n')
    const g = 'drive_1_20261102_120000.csv', b = 'drive_2_20261102_130000.csv'
    for (const files of [[[b, bad], [g, good]], [[g, good], [b, bad]]] as [string, string][][]) {
      const err = await rejection(importBatchZip(await zipFile(files, 'openrs_batch_20261102_140000.zip')))
      expect(err).toBeInstanceOf(ImportError)
      expect(err.message).toContain(`${b} row 4: expected 8 fields, found 9`)
    }
  })
})
