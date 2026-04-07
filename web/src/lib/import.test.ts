import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { importZip } from './import'

// Helper: build a ZIP blob and wrap as File
async function buildZipFile(files: Record<string, string>, name = 'test.zip'): Promise<File> {
  const zip = new JSZip()
  for (const [fname, content] of Object.entries(files)) {
    zip.file(fname, content)
  }
  const blob = await zip.generateAsync({ type: 'blob' })
  return new File([blob], name, { type: 'application/zip' })
}

const CSV_HEADER = 'timestamp_ms,lat,lng,rpm,boostPsi,speedKph,coolantC,oilTempC,rduTempC,ptuTempC,ambientC,latG,longG,fuelPct,wheelSpeedFL,wheelSpeedFR,wheelSpeedRL,wheelSpeedRR,driveMode,awdTorqueL,awdTorqueR,gear,throttlePct,tirePressLF,tirePressRF,tirePressLR,tirePressRR,tireTempLF,tireTempRF,tireTempLR,tireTempRR'

function makeCsvRow(ts: number, overrides: Record<string, string | number> = {}): string {
  const defaults: Record<string, string | number> = {
    timestamp_ms: ts,
    lat: 51.5074, lng: -0.1278,
    rpm: 3000, boostPsi: 10, speedKph: 80,
    coolantC: 90, oilTempC: 95, rduTempC: 60, ptuTempC: 55, ambientC: 20,
    latG: 0.3, longG: 0.1, fuelPct: 75,
    wheelSpeedFL: 80, wheelSpeedFR: 80, wheelSpeedRL: 80, wheelSpeedRR: 80,
    driveMode: 'NORMAL', awdTorqueL: 0, awdTorqueR: 0,
    gear: '3', throttlePct: 45,
    tirePressLF: 35, tirePressRF: 35, tirePressLR: 33, tirePressRR: 33,
    tireTempLF: 40, tireTempRF: 40, tireTempLR: 38, tireTempRR: 38,
  }
  const merged = { ...defaults, ...overrides }
  const headers = CSV_HEADER.split(',')
  return headers.map((h) => String(merged[h] ?? '')).join(',')
}

describe('importZip', () => {
  it('parses a drive_*.csv file with trip data', async () => {
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { rpm: 2500, speedKph: 60, fuelPct: 80 }),
      makeCsvRow(1001000, { rpm: 4000, speedKph: 100, fuelPct: 79.5 }),
      makeCsvRow(1002000, { rpm: 3500, speedKph: 90, fuelPct: 79 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_1000000.csv': csv })
    const session = await importZip(file)

    expect(session.id).toBeTruthy()
    expect(session.trip).not.toBeNull()
    expect(session.trip!.points).toHaveLength(3)
    expect(session.trip!.points[0].rpm).toBe(2500)
    expect(session.trip!.points[1].speedKph).toBe(100)
  })

  it('parses trip_*.csv prefix as well', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({ 'trip_1000000.csv': csv })
    const session = await importZip(file)
    expect(session.trip).not.toBeNull()
    expect(session.trip!.points).toHaveLength(1)
  })

  it('computes trip summary correctly', async () => {
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { rpm: 2000, boostPsi: 5, speedKph: 60, latG: 0.2, fuelPct: 80 }),
      makeCsvRow(1001000, { rpm: 6000, boostPsi: 20, speedKph: 160, latG: 0.8, fuelPct: 79 }),
      makeCsvRow(1002000, { rpm: 4000, boostPsi: 12, speedKph: 100, latG: 0.5, fuelPct: 78 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_test.csv': csv })
    const session = await importZip(file)
    const summary = session.trip!.summary

    expect(summary.durationMs).toBe(2000)
    expect(summary.peakRpm).toBe(6000)
    expect(summary.peakBoostPsi).toBe(20)
    expect(summary.peakSpeedKph).toBe(160)
    expect(summary.peakLatG).toBeCloseTo(0.8, 1)
    expect(summary.avgRpm).toBeCloseTo(4000, 0)
    expect(summary.avgSpeedKph).toBeCloseTo(106.67, 0)
  })

  it('computes fuel consumption from percentage delta', async () => {
    // 80% → 76% = 4% of 52.3L tank = 2.092L
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { fuelPct: 80 }),
      makeCsvRow(1010000, { fuelPct: 76 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_fuel.csv': csv })
    const session = await importZip(file)
    expect(session.trip!.summary.fuelUsedL).toBeCloseTo(2.092, 2)
  })

  it('handles sentinel values for temperatures', async () => {
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { coolantC: -99, oilTempC: -99 }),
      makeCsvRow(1001000, { coolantC: 95, oilTempC: 100 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_sentinel.csv': csv })
    const session = await importZip(file)
    // Peaks should use valid values only, not sentinels
    expect(session.trip!.summary.peakCoolantC).toBe(95)
    expect(session.trip!.summary.peakOilTempC).toBe(100)
  })

  it('returns -99 peak temps when all values are sentinels', async () => {
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { coolantC: -99, oilTempC: -99 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_no_temps.csv': csv })
    const session = await importZip(file)
    expect(session.trip!.summary.peakCoolantC).toBe(-99)
    expect(session.trip!.summary.peakOilTempC).toBe(-99)
  })

  it('computes mode breakdown from drive mode transitions', async () => {
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { driveMode: 'NORMAL' }),
      makeCsvRow(1003000, { driveMode: 'SPORT' }),   // 3s in NORMAL
      makeCsvRow(1005000, { driveMode: 'SPORT' }),   // 2s in SPORT
    ].join('\n')

    const file = await buildZipFile({ 'drive_modes.csv': csv })
    const session = await importZip(file)
    const mb = session.trip!.summary.modeBreakdown
    expect(mb['NORMAL']).toBeCloseTo(3, 0)
    expect(mb['SPORT']).toBeCloseTo(2, 0)
  })

  it('generates peak events for RPM, boost, speed, latG', async () => {
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { rpm: 3000, boostPsi: 10, speedKph: 80, latG: 0.3 }),
      makeCsvRow(1001000, { rpm: 6500, boostPsi: 22, speedKph: 180, latG: 0.95 }),
      makeCsvRow(1002000, { rpm: 4000, boostPsi: 8, speedKph: 100, latG: 0.2 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_peaks.csv': csv })
    const session = await importZip(file)
    const peaks = session.trip!.summary.peakEvents

    expect(peaks).toHaveLength(4)
    expect(peaks.find((p) => p.type === 'rpm')?.value).toBe(6500)
    expect(peaks.find((p) => p.type === 'boost')?.value).toBe(22)
    expect(peaks.find((p) => p.type === 'speed')?.value).toBe(180)
    expect(peaks.find((p) => p.type === 'latG')?.value).toBeCloseTo(0.95, 2)
  })

  it('parses DTC scan results from dtc_scan_*.txt', async () => {
    const dtcText = `
─── PCM ───────────────
P0101  [STORED]  Mass air flow sensor range/performance
P0234  [PENDING]  Turbocharger overboost condition

─── BCM ───────────────
U0100  [ACTIVE]  Lost communication with ECM
`.trim()

    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_dtc.csv': csv,
      'dtc_scan_1000000.txt': dtcText,
    })

    const session = await importZip(file)
    const dtcs = session.diagnostics!.dtcResults

    expect(dtcs).toHaveLength(3)
    expect(dtcs[0]).toEqual({
      module: 'PCM', code: 'P0101', status: 'STORED',
      description: 'Mass air flow sensor range/performance',
    })
    expect(dtcs[1]).toEqual({
      module: 'PCM', code: 'P0234', status: 'PENDING',
      description: 'Turbocharger overboost condition',
    })
    expect(dtcs[2]).toEqual({
      module: 'BCM', code: 'U0100', status: 'ACTIVE',
      description: 'Lost communication with ECM',
    })
  })

  it('returns null trip when no CSV in ZIP', async () => {
    const file = await buildZipFile({ 'readme.txt': 'no data here' })
    const session = await importZip(file)
    expect(session.trip).toBeNull()
  })

  it('parses metadata from drive_summary_* file', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const summary = `App Version: openRS_ v2.2.6\nFirmware: v1.61\nSession Start: 2026-04-01 14:30:00`
    const file = await buildZipFile({
      'drive_1000000.csv': csv,
      'drive_summary_1000000.txt': summary,
    })

    const session = await importZip(file)
    expect(session.meta.appVersion).toBe('openRS_ v2.2.6')
    expect(session.meta.firmwareVersion).toBe('v1.61')
    expect(session.meta.sessionStart).toBe('2026-04-01 14:30:00')
  })

  it('handles alternate CSV column names (snake_case)', async () => {
    const headers = 'timestamp_ms,lat,lng,rpm,boost_psi,speed_kph,coolant_c,oil_temp_c,rdu_temp_c,ptu_temp_c,ambient_c,lateral_g,long_g,fuel_pct,ws_fl,ws_fr,ws_rl,ws_rr,drive_mode,awd_torque_l,awd_torque_r,gear,throttle_pct,tire_press_lf_psi,tire_press_rf_psi,tire_press_lr_psi,tire_press_rr_psi,tire_temp_lf_c,tire_temp_rf_c,tire_temp_lr_c,tire_temp_rr_c'
    const row = '1000000,51.5,-0.13,5000,15,120,92,98,65,58,22,0.5,0.2,70,120,120,119,119,SPORT,100,100,4,60,34,34,32,32,42,42,40,40'
    const csv = [headers, row].join('\n')

    const file = await buildZipFile({ 'drive_snake.csv': csv })
    const session = await importZip(file)
    const p = session.trip!.points[0]

    expect(p.boostPsi).toBe(15)
    expect(p.speedKph).toBe(120)
    expect(p.coolantC).toBe(92)
    expect(p.oilTempC).toBe(98)
    expect(p.latG).toBe(0.5)
    expect(p.throttlePct).toBe(60)
    expect(p.tirePressLF).toBe(34)
    expect(p.tireTempLF).toBe(42)
    expect(p.driveMode).toBe('SPORT')
  })
})
