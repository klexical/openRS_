import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import JSZip from 'jszip'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from './import'
import { ThermalProgressionChart } from '../components/charts/ThermalProgressionChart'

// Complete per-drive exports written by the Android app's export builders (DriveExportThermalTest with
// OPENRS_EXPORT_SAMPLES) — drive CSV, GPX, summary, profile JSON with `thermalRecord`, manifest. Checks that the
// rc.6 profile format imports: omitted curves read as no data and the additive `thermalRecord` block is ignored.
const ROOT = join(process.cwd(), 'src/lib/__fixtures__/android-export-rc6/')   // vitest root = web/

async function exportZip(name: string): Promise<File> {
  const zip = new JSZip()
  for (const f of readdirSync(ROOT + name)) zip.file(f, readFileSync(ROOT + name + '/' + f, 'utf8'))
  return new File([await zip.generateAsync({ type: 'blob' })], `openrs_export_${name}.zip`, { type: 'application/zip' })
}

const curve = (startC: number, peakC: number, endC: number) => ({ startC, peakC, endC })
const NONE = curve(-99, -99, -99)

async function imported(name: string) {
  const session = await importZip(await exportZip(name))
  const trip = session.trip!
  expect(trip).toBeTruthy()
  expect(trip.profile).toBeDefined()                       // profile JSON parsed despite the new block
  // Whatever curves arrive, the chart draws only real readings
  const { container } = render(<ThermalProgressionChart data={trip.thermalProgression!} />)
  expect(container.textContent ?? '').not.toMatch(/NaN|-99/)
  for (const el of container.querySelectorAll('path, circle, line, text')) {
    for (const a of ['d', 'cx', 'cy', 'y', 'y1', 'y2']) expect(el.getAttribute(a) ?? '').not.toMatch(/NaN|Infinity/)
  }
  return { session, trip, container }
}

describe('Android rc.6 profile export import', () => {
  it('normal (TRACK record) — all four curves from the saved readings', async () => {
    const { trip, container } = await imported('1-track')
    const tp = trip.thermalProgression!
    expect(tp.oil).toEqual(curve(62, 131, 112))
    expect(tp.coolant).toEqual(curve(58, 104, 96))
    expect(tp.ptu).toEqual(curve(60, 88, 80))
    expect(tp.rdu).toEqual(curve(50, 92, 85))
    expect(container.textContent).toContain('131°')
    expect(trip.summary.peakOilTempC).toBe(131)
  })

  it('mixed profiles — the true 122 °C peak, no RDU / PTU curves when the record has none', async () => {
    const { trip } = await imported('3-street-to-race')
    expect(trip.thermalProgression!.oil).toEqual(curve(70, 122, 104))
    expect(trip.thermalProgression!.rdu).toBeUndefined()
    expect(trip.thermalProgression!.ptu).toBeUndefined()
    expect(trip.summary.peakOilTempC).toBe(122)
  })

  it('legacy record — the drive peaks, never the drive-end reading as a curve', async () => {
    const { trip } = await imported('4-legacy')
    expect(trip.thermalProgression!.oil).toEqual(curve(40, 136, 134))
    expect(trip.thermalProgression!.rdu).toBeUndefined()
  })

  it('no record and unreadable record — oil / coolant curves still import', async () => {
    for (const name of ['5-no-record', '5b-unreadable']) {
      const { trip } = await imported(name)
      expect(trip.thermalProgression!.oil).toEqual(curve(40, 112, 110))
    }
  })

  it('endpoint above the saved peak — the omitted curve is no data, the peak stays in the summary', async () => {
    const { trip, container } = await imported('6-endpoints-above-peak')
    expect(trip.thermalProgression!.oil).toEqual(NONE)          // omitted in the export → parsed as missing
    expect(trip.thermalProgression!.coolant).toEqual(curve(45, 90, 88))
    expect(trip.thermalProgression!.rdu).toBeUndefined()
    expect(trip.summary.peakOilTempC).toBe(100)                 // from the profile's drive block
    expect(container.textContent).not.toContain('Oil')          // chart legend: coolant only
    expect(container.textContent).toContain('Coolant')
  })

  it('no coherent endpoints at all — no chart, import still succeeds', async () => {
    const { trip, container } = await imported('6b-no-endpoints')
    expect(trip.thermalProgression!.oil).toEqual(NONE)
    expect(trip.thermalProgression!.coolant).toEqual(NONE)
    expect(container.querySelector('svg')).toBeNull()
  })
})
