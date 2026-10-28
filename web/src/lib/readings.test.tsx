import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from './import'
import { tempReading, pctReading, numReading, tripChartData, tempStackData, latestTpms } from './readings'
import { TpmsSummaryCard } from '../components/ui/TpmsSummaryCard'
import type { TripPoint } from '../types/session'

const FIXTURES = join(process.cwd(), 'src/lib/__fixtures__/android-exports/')   // vitest root = web/

// A sample as the app's recorder writes it before anything has been received / polled.
const pt = (over: Partial<TripPoint> = {}): TripPoint => ({
  ts: 0, lat: 0, lng: 0, rpm: 0, boostPsi: 0, speedKph: 0,
  coolantC: -99, oilTempC: -99, rduTempC: -99, ptuTempC: -99, ambientC: -99,
  latG: 0, longG: 0, fuelPct: -1,
  wheelSpeedFL: 0, wheelSpeedFR: 0, wheelSpeedRL: 0, wheelSpeedRR: 0,
  driveMode: 'Normal', awdTorqueL: 0, awdTorqueR: 0, gear: '', throttlePct: -1,
  tirePressLF: -1, tirePressRF: -1, tirePressLR: -1, tirePressRR: -1,
  tireTempLF: -99, tireTempRF: -99, tireTempLR: -99, tireTempRR: -99,
  brakePressure: 0, steeringAngle: 0, raceReady: false,
  ...over,
})

describe('chart readings: missing → gap (null), genuine zero → 0', () => {
  it('sentinels and malformed values are missing', () => {
    expect(tempReading(-99)).toBeNull()
    expect(tempReading(NaN)).toBeNull()
    expect(pctReading(-1)).toBeNull()
    expect(pctReading(Infinity)).toBeNull()
    expect(numReading(NaN)).toBeNull()
  })

  it('zeros and real negative readings are kept', () => {
    expect(tempReading(0)).toBe(0)
    expect(tempReading(-20)).toBe(-20)          // a cold morning, not a sentinel
    expect(pctReading(0)).toBe(0)               // empty tank / closed throttle / flat tyre
    expect(numReading(0)).toBe(0)
    expect(numReading(-5.2)).toBe(-5.2)         // vacuum
  })

  it('TRIP rows: a sample before the first temp / TPMS / fuel poll is a gap; zero rpm and throttle are zero', () => {
    const rows = tripChartData([
      pt({ ts: 1000, rpm: 0, throttlePct: 0 }),
      pt({ ts: 2000, rpm: 3200, throttlePct: 45, coolantC: 0, oilTempC: 88, fuelPct: 0, tirePressLF: 32.5, tireTempLF: 0 }),
      pt({ ts: 3000, rpm: 3300, oilTempC: NaN }),
    ])
    expect(rows.map((r) => r.ts)).toEqual([0, 1, 2])
    expect(rows[0]).toMatchObject({ rpm: 0, throttlePct: 0, coolantC: null, oilTempC: null, fuelPct: null, tirePressLF: null, tireTempLF: null })
    expect(rows[1]).toMatchObject({ coolantC: 0, oilTempC: 88, fuelPct: 0, tirePressLF: 32.5, tireTempLF: 0, throttlePct: 45 })
    expect(rows[2]).toMatchObject({ oilTempC: null, throttlePct: null })
    expect(Object.values(rows[0]).every((v) => v === null || typeof v === 'number')).toBe(true)
  })

  it('temperature stack: a missing layer is null, not a 0 floor', () => {
    const [row] = tempStackData([pt({ oilTempC: 95, coolantC: -99 })])
    expect(row).toEqual({ ts: 0, coolantC: null, oilTempC: 95, rduTempC: null, ptuTempC: null })
  })

  it('a real app export whose readings are all 0.0 keeps every one of them', async () => {
    const name = 'drive-nogps-telemetry.zip'
    const s = await importZip(new File([readFileSync(FIXTURES + name)], name))
    const rows = tripChartData(s.trip!.points)
    expect(rows).toHaveLength(3)
    for (const r of rows) {
      expect(r.rpm).toBe(5000)
      for (const k of ['coolantC', 'oilTempC', 'fuelPct', 'throttlePct', 'tirePressLF', 'tireTempRR', 'speedKph']) expect(r[k]).toBe(0)
    }
  })
})

describe('TPMS summary', () => {
  it('takes each corner\'s latest reading independently; never-reported corners stay missing', () => {
    const t = latestTpms([
      pt({ tirePressLF: 31, tireTempLF: 20, tirePressRR: 30 }),
      pt({ tirePressRF: 0 }),                              // 0 PSI is a reading
      pt({ tirePressLF: 32 }),                             // latest LF
    ])!
    expect(t).toMatchObject({ pressLF: 32, pressRF: 0, pressRR: 30, pressLR: -1, tempLF: 20, tempRF: -99 })
    expect(latestTpms([pt(), pt()])).toBeNull()
    expect(latestTpms([])).toBeNull()
  })

  it('the card shows "—" for a corner that never reported, not 0 PSI', () => {
    const { container } = render(<TpmsSummaryCard data={latestTpms([pt({ tirePressLF: 32.4, tireTempLF: 21 })])!} />)
    const cells = [...container.firstElementChild!.firstElementChild!.children].map((d) => d.textContent)
    expect(cells).toEqual(['LF32.4PSI21°C', 'RF—PSI—', 'LR—PSI—', 'RR—PSI—'])
  })
})
