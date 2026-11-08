import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from './import'

// #271: the rc.8 drive CSV appends `map_f40b_kpa_abs` and `map_f40b_saturated` after the existing columns. Sapphire reads
// columns by header name and doesn't use MAP yet, so the import must go through unchanged. Fixture written by the Android
// production CSV builder (MapF40bStorageTest); synthetic values and times.
const DIR = join(process.cwd(), 'src/lib/__fixtures__/android-export-rc8-map/')   // vitest root = web/

async function exportZip(): Promise<File> {
  const zip = new JSZip()
  for (const f of readdirSync(DIR)) if (f !== 'README.md') zip.file(f, readFileSync(DIR + f, 'utf8'))
  return new File([await zip.generateAsync({ type: 'blob' })], 'openrs_export_rc8_map.zip', { type: 'application/zip' })
}

describe('rc.8 drive CSV with the MAP columns (#271) → Sapphire', () => {
  it('imports every row; the appended columns change nothing Sapphire reads', async () => {
    const csv = readFileSync(DIR + 'drive_20260101_000000.csv', 'utf8')
    const header = csv.split('\n')[1].split(',')
    expect(header.slice(-4)).toEqual(['boost_source', 'baro_kpa', 'map_f40b_kpa_abs', 'map_f40b_saturated'])

    const session = await importZip(await exportZip())
    const pts = session.trip!.points
    expect(pts).toHaveLength(3)
    expect(pts.map((p) => p.ts)).toEqual([1000, 1001, 1002])
  })
})
