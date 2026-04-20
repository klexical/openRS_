import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { importZip, importBatchZip } from './import'

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

/** CSV header matching Android's actual buildCsv output (snake_case with extended fields). */
const ANDROID_CSV_HEADER = 'timestamp_ms,lat,lng,speed_kph,rpm,gear,boost_psi,coolant_c,oil_c,ambient_c,rdu_c,ptu_c,fuel_pct,tire_press_lf_psi,tire_press_rf_psi,tire_press_lr_psi,tire_press_rr_psi,tire_temp_lf_c,tire_temp_rf_c,tire_temp_lr_c,tire_temp_rr_c,wheel_fl_kph,wheel_fr_kph,wheel_rl_kph,wheel_rr_kph,lateral_g,longitudinal_g,brake_pressure,steering_angle,throttle_pct,drive_mode,race_ready'

function makeAndroidCsvRow(ts: number, overrides: Record<string, string | number> = {}): string {
  const defaults: Record<string, string | number> = {
    timestamp_ms: ts, lat: 51.5074, lng: -0.1278,
    speed_kph: 80, rpm: 3000, gear: '3', boost_psi: 10,
    coolant_c: 90, oil_c: 95, ambient_c: 20, rdu_c: 60, ptu_c: 55, fuel_pct: 75,
    tire_press_lf_psi: 35, tire_press_rf_psi: 35, tire_press_lr_psi: 33, tire_press_rr_psi: 33,
    tire_temp_lf_c: 40, tire_temp_rf_c: 40, tire_temp_lr_c: 38, tire_temp_rr_c: 38,
    wheel_fl_kph: 80, wheel_fr_kph: 80, wheel_rl_kph: 80, wheel_rr_kph: 80,
    lateral_g: 0.3, longitudinal_g: 0.1, brake_pressure: 0, steering_angle: 0,
    throttle_pct: 45, drive_mode: 'NORMAL', race_ready: 'false',
  }
  const merged = { ...defaults, ...overrides }
  const headers = ANDROID_CSV_HEADER.split(',')
  return headers.map((h) => String(merged[h] ?? '')).join(',')
}

function makeManifest(files: { name: string; type: string }[]): string {
  return JSON.stringify({
    version: 1, app: 'openRS_', appVersion: '2.2.7-rc.3', appBuild: 35,
    exportedAt: 1713500000000, files,
  })
}

function makeProfileJson(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    version: 2,
    drive: {
      startTime: 1000000, endTime: 1010000, distanceKm: 12.5,
      avgSpeedKph: 75, maxSpeedKph: 180, peakRpm: 6500,
      peakBoostPsi: 22, peakLateralG: 0.95, peakOilTempC: 115,
      peakCoolantTempC: 102, fuelUsedL: 3.5, aggressionScore: 68,
      tags: 'TRACK,SPIRITED', avgFuelL100km: 12.8, avgFuelMpg: 18.4,
    },
    peaks: [
      { type: 'RPM', value: 6500, lat: 51.5, lng: -0.12, timestampMs: 1005000 },
      { type: 'BOOST', value: 22, lat: 51.51, lng: -0.13, timestampMs: 1006000 },
    ],
    thermalProgression: {
      oil: { startC: 70, peakC: 115, endC: 105 },
      coolant: { startC: 85, peakC: 102, endC: 98 },
      rdu: { startC: 40, peakC: 78, endC: 65 },
    },
    modeTimeline: [
      { mode: 'NORMAL', startMs: 0, endMs: 3000 },
      { mode: 'SPORT', startMs: 3000, endMs: 7000 },
      { mode: 'TRACK', startMs: 7000, endMs: 10000 },
    ],
    bookmarks: [
      { timestamp: 1003000, lat: 51.505, lng: -0.125, label: 'Peak corner', speedKph: 120, rpm: 5500, boostPsi: 18 },
    ],
    ...overrides,
  })
}

function makeDiagJson(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    canFrameInventory: {
      '0x090': { totalReceived: 1200, hasChanged: true, firstRawHex: 'AABB', lastRawHex: 'CCDD', periodicSamples: [{ rawHex: '1122' }] },
      '0x0F8': { totalReceived: 800, hasChanged: false, firstRawHex: '0000', lastRawHex: '0000', periodicSamples: [] },
    },
    fpsTimeline: [
      { relMs: 1000, fps: 45 },
      { relMs: 2000, fps: 50 },
    ],
    sessionEvents: [
      { relMs: 500, type: 'connect', message: 'Connected via WiFi' },
      { relMs: 1500, type: 'decode', message: 'First CAN frame decoded' },
    ],
    decodeTrace: [
      { relMs: 100, id: '0x090', raw: 'AABBCCDD', decoded: 'RPM=3000', issue: '' },
    ],
    probeResults: [
      {
        module: 'PCM',
        requestId: '0x7E0', responseId: '0x7E8',
        results: [
          { did: '0x0110', status: 'found', response: '6201100055' },
          { did: '0x0304', status: 'found', response: '620304089C' },
        ],
      },
      {
        module: 'BCM',
        requestId: '0x726', responseId: '0x72E',
        results: [
          { did: '0x2813', status: 'not_found', response: '' },
        ],
      },
    ],
    nrcSuppression: [
      { did: '0x0462', nrcCode: 49, nrcName: 'requestOutOfRange', firstSeenMs: 5000, suppressedAfterCount: 3 },
    ],
    ...overrides,
  })
}

// ═══════════════════════════════════════════════════════════════════════════
// Original tests (preserved)
// ═══════════════════════════════════════════════════════════════════════════

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
      makeCsvRow(1003000, { driveMode: 'SPORT' }),
      makeCsvRow(1005000, { driveMode: 'SPORT' }),
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

// ═══════════════════════════════════════════════════════════════════════════
// T6E: New CSV column tests (brake_pressure, steering_angle, race_ready, longitudinal_g)
// ═══════════════════════════════════════════════════════════════════════════

describe('new CSV columns (T6E)', () => {
  it('parses brake_pressure from Android CSV', async () => {
    const csv = [
      ANDROID_CSV_HEADER,
      makeAndroidCsvRow(1000000, { brake_pressure: 42.5 }),
      makeAndroidCsvRow(1001000, { brake_pressure: 0 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_brake.csv': csv })
    const session = await importZip(file)
    expect(session.trip!.points[0].brakePressure).toBe(42.5)
    expect(session.trip!.points[1].brakePressure).toBe(0)
  })

  it('parses steering_angle from Android CSV', async () => {
    const csv = [
      ANDROID_CSV_HEADER,
      makeAndroidCsvRow(1000000, { steering_angle: -15.3 }),
      makeAndroidCsvRow(1001000, { steering_angle: 22.7 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_steering.csv': csv })
    const session = await importZip(file)
    expect(session.trip!.points[0].steeringAngle).toBeCloseTo(-15.3, 1)
    expect(session.trip!.points[1].steeringAngle).toBeCloseTo(22.7, 1)
  })

  it('parses race_ready boolean from Android CSV', async () => {
    const csv = [
      ANDROID_CSV_HEADER,
      makeAndroidCsvRow(1000000, { race_ready: 'false' }),
      makeAndroidCsvRow(1001000, { race_ready: 'true' }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_rr.csv': csv })
    const session = await importZip(file)
    expect(session.trip!.points[0].raceReady).toBe(false)
    expect(session.trip!.points[1].raceReady).toBe(true)
  })

  it('parses longitudinal_g from Android CSV header (T0F fix)', async () => {
    const csv = [
      ANDROID_CSV_HEADER,
      makeAndroidCsvRow(1000000, { longitudinal_g: 0.45 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_longg.csv': csv })
    const session = await importZip(file)
    expect(session.trip!.points[0].longG).toBeCloseTo(0.45, 2)
  })
})

// ═══════════════════════════════════════════════════════════════════════════
// T6A: Profile JSON parsing tests
// ═══════════════════════════════════════════════════════════════════════════

describe('profile JSON parsing (T6A)', () => {
  it('parses full profile and overrides CSV summary', async () => {
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { rpm: 3000, boostPsi: 8 }),
      makeCsvRow(1001000, { rpm: 4000, boostPsi: 12 }),
    ].join('\n')

    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'drive_profile_20260419_143000.json': makeProfileJson(),
    })
    const session = await importZip(file)

    // Profile peaks should override CSV-computed values
    expect(session.trip!.summary.peakRpm).toBe(6500)
    expect(session.trip!.summary.peakBoostPsi).toBe(22)
    expect(session.trip!.summary.peakLatG).toBeCloseTo(0.95, 2)
    expect(session.trip!.summary.peakSpeedKph).toBe(180)
    expect(session.trip!.summary.peakOilTempC).toBe(115)
    expect(session.trip!.summary.peakCoolantC).toBe(102)
    expect(session.trip!.summary.fuelUsedL).toBe(3.5)
  })

  it('populates tags from comma-separated profile string', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'drive_profile_20260419_143000.json': makeProfileJson(),
    })
    const session = await importZip(file)
    expect(session.tags).toEqual(['TRACK', 'SPIRITED'])
  })

  it('parses thermal progression', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'drive_profile_20260419_143000.json': makeProfileJson(),
    })
    const session = await importZip(file)
    const tp = session.trip!.thermalProgression

    expect(tp).toBeDefined()
    expect(tp!.oil.startC).toBe(70)
    expect(tp!.oil.peakC).toBe(115)
    expect(tp!.oil.endC).toBe(105)
    expect(tp!.coolant.startC).toBe(85)
    expect(tp!.coolant.peakC).toBe(102)
    expect(tp!.rdu?.startC).toBe(40)
    expect(tp!.rdu?.peakC).toBe(78)
  })

  it('parses bookmarks from profile', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'drive_profile_20260419_143000.json': makeProfileJson(),
    })
    const session = await importZip(file)
    const bm = session.trip!.bookmarks

    expect(bm).toBeDefined()
    expect(bm).toHaveLength(1)
    expect(bm![0].label).toBe('Peak corner')
    expect(bm![0].speedKph).toBe(120)
    expect(bm![0].rpm).toBe(5500)
    expect(bm![0].boostPsi).toBe(18)
  })

  it('parses aggression score', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'drive_profile_20260419_143000.json': makeProfileJson(),
    })
    const session = await importZip(file)
    expect(session.trip!.aggressionScore).toBe(68)
    expect(session.trip!.summary.aggressionScore).toBe(68)
  })

  it('parses canonical mode timeline', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'drive_profile_20260419_143000.json': makeProfileJson(),
    })
    const session = await importZip(file)
    const mt = session.trip!.canonicalModeTimeline

    expect(mt).toBeDefined()
    expect(mt).toHaveLength(3)
    expect(mt![0].mode).toBe('NORMAL')
    expect(mt![1].mode).toBe('SPORT')
    expect(mt![2].mode).toBe('TRACK')
    expect(mt![2].endMs).toBe(10000)
  })

  it('handles profile with missing optional fields gracefully', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const minimalProfile = JSON.stringify({
      version: 2,
      drive: {
        startTime: 1000000, endTime: 1010000, distanceKm: 5,
        avgSpeedKph: 50, maxSpeedKph: 100, peakRpm: 4000,
        peakBoostPsi: 10, peakLateralG: 0.4, peakOilTempC: -99,
        peakCoolantTempC: -99, fuelUsedL: 0, aggressionScore: 0,
        tags: '',
      },
    })
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'drive_profile_20260419_143000.json': minimalProfile,
    })
    const session = await importZip(file)

    // Should not crash, bookmarks/thermal should be empty/absent
    expect(session.tags).toEqual([])
    expect(session.trip!.bookmarks).toBeUndefined()
    expect(session.trip!.aggressionScore).toBeUndefined()
  })

  it('works without profile JSON (backward compat)', async () => {
    const csv = [
      CSV_HEADER,
      makeCsvRow(1000000, { rpm: 5000 }),
      makeCsvRow(1001000, { rpm: 6000 }),
    ].join('\n')

    const file = await buildZipFile({ 'drive_norofile.csv': csv })
    const session = await importZip(file)

    // Should use CSV-computed summary
    expect(session.trip!.summary.peakRpm).toBe(6000)
    expect(session.trip!.profile).toBeUndefined()
    expect(session.trip!.thermalProgression).toBeUndefined()
    expect(session.trip!.bookmarks).toBeUndefined()
  })

  it('overrides peak events with GPS-tagged profile peaks', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'drive_profile_20260419_143000.json': makeProfileJson(),
    })
    const session = await importZip(file)
    const peaks = session.trip!.summary.peakEvents

    expect(peaks).toHaveLength(2) // only RPM and BOOST from profile
    const rpmPeak = peaks.find((p) => p.type === 'rpm')
    expect(rpmPeak!.lat).toBe(51.5)
    expect(rpmPeak!.lng).toBe(-0.12)
    expect(rpmPeak!.value).toBe(6500)
  })
})

// ═══════════════════════════════════════════════════════════════════════════
// T6B: DTC JSON parsing tests
// ═══════════════════════════════════════════════════════════════════════════

describe('DTC JSON parsing (T6B)', () => {
  it('parses DTC codes with freeze frames', async () => {
    const dtcJson = JSON.stringify({
      version: 1, timestamp: 1713500000000, app: 'openRS_ v2.2.7',
      totalCodes: 1,
      codes: [
        {
          module: 'PCM', code: 'P0234', description: 'Overboost', status: 'ACTIVE', severity: 'WARNING',
          freezeFrame: {
            recordNumber: 1,
            entries: [
              { did: '0x0110', label: 'Engine RPM', value: '5500' },
              { did: '0x0304', label: 'Battery Voltage', value: '14.2V' },
            ],
          },
        },
      ],
    })

    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'dtc_scan_1000000.json': dtcJson,
    })
    const session = await importZip(file)
    const dtcs = session.diagnostics!.dtcResults

    expect(dtcs).toHaveLength(1)
    expect(dtcs[0].code).toBe('P0234')
    expect(dtcs[0].severity).toBe('WARNING')
    expect(dtcs[0].freezeFrame).toBeDefined()
    expect(dtcs[0].freezeFrame!.recordNumber).toBe(1)
    expect(dtcs[0].freezeFrame!.entries).toHaveLength(2)
    expect(dtcs[0].freezeFrame!.entries[0].did).toBe('0x0110')
    expect(dtcs[0].freezeFrame!.entries[0].label).toBe('Engine RPM')
    expect(dtcs[0].freezeFrame!.entries[0].value).toBe('5500')
  })

  it('parses DTC codes without freeze frames', async () => {
    const dtcJson = JSON.stringify({
      version: 1, timestamp: 1713500000000, app: 'openRS_',
      totalCodes: 2,
      codes: [
        { module: 'BCM', code: 'U0100', description: 'Lost comm', status: 'PENDING', severity: 'INFO' },
        { module: 'ABS', code: 'C0034', description: 'ABS sensor', status: 'ACTIVE' },
      ],
    })

    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'dtc_scan_1000000.json': dtcJson,
    })
    const session = await importZip(file)
    const dtcs = session.diagnostics!.dtcResults

    expect(dtcs).toHaveLength(2)
    expect(dtcs[0].severity).toBe('INFO')
    expect(dtcs[0].freezeFrame).toBeUndefined()
    expect(dtcs[1].severity).toBeUndefined()
  })

  it('prefers JSON over TXT when both present', async () => {
    const dtcJson = JSON.stringify({
      version: 1, timestamp: 1713500000000, app: 'openRS_',
      totalCodes: 1,
      codes: [{ module: 'PCM', code: 'P0234', description: 'From JSON', status: 'ACTIVE', severity: 'CRITICAL' }],
    })
    const dtcTxt = '─── PCM ───────────────\nP0234  [ACTIVE]  From TXT'

    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'dtc_scan_1000000.json': dtcJson,
      'dtc_scan_1000000.txt': dtcTxt,
    })
    const session = await importZip(file)
    const dtcs = session.diagnostics!.dtcResults

    expect(dtcs).toHaveLength(1)
    expect(dtcs[0].description).toBe('From JSON') // JSON wins
    expect(dtcs[0].severity).toBe('CRITICAL')
  })

  it('falls back to TXT when JSON is malformed', async () => {
    const dtcTxt = '─── PCM ───────────────\nP0101  [STORED]  From TXT fallback'

    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'dtc_scan_1000000.json': '{ invalid json',
      'dtc_scan_1000000.txt': dtcTxt,
    })
    const session = await importZip(file)
    const dtcs = session.diagnostics!.dtcResults

    expect(dtcs).toHaveLength(1)
    expect(dtcs[0].description).toBe('From TXT fallback')
  })
})

// ═══════════════════════════════════════════════════════════════════════════
// T6G: Diagnostic parser bug fix tests
// ═══════════════════════════════════════════════════════════════════════════

describe('diagnostic parser bug fixes (T6G)', () => {
  it('T0A: parses canFrameInventory object keyed by CAN ID', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'diagnostic_detail_test.json': makeDiagJson(),
    })
    const session = await importZip(file)
    const inv = session.diagnostics!.canInventory

    expect(inv).toHaveLength(2)
    expect(inv[0].id).toBe('0x090')
    expect(inv[0].count).toBe(1200)
    expect(inv[0].changed).toBe(true)
    expect(inv[0].firstHex).toBe('AABB')
    expect(inv[0].lastHex).toBe('CCDD')
    expect(inv[0].samples).toEqual(['1122'])
    expect(inv[1].id).toBe('0x0F8')
    expect(inv[1].count).toBe(800)
    expect(inv[1].changed).toBe(false)
  })

  it('T0B: parses fpsTimeline with relMs key', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'diagnostic_detail_test.json': makeDiagJson(),
    })
    const session = await importZip(file)
    const fps = session.diagnostics!.fpsTimeline

    expect(fps).toHaveLength(2)
    expect(fps[0].ts).toBe(1000)
    expect(fps[0].fps).toBe(45)
    expect(fps[1].ts).toBe(2000)
  })

  it('T0C: parses decodeTrace with id key', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'diagnostic_detail_test.json': makeDiagJson(),
    })
    const session = await importZip(file)
    const trace = session.diagnostics!.decodeTrace

    expect(trace).toHaveLength(1)
    expect(trace[0].canId).toBe('0x090')
    expect(trace[0].rawHex).toBe('AABBCCDD')
    expect(trace[0].decoded).toBe('RPM=3000')
    expect(trace[0].ts).toBe(100)
  })

  it('T0D: parses sessionEvents with relMs key', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'diagnostic_detail_test.json': makeDiagJson(),
    })
    const session = await importZip(file)
    const events = session.diagnostics!.sessionEvents

    expect(events).toHaveLength(2)
    expect(events[0].ts).toBe(500)
    expect(events[0].type).toBe('connect')
    expect(events[0].message).toBe('Connected via WiFi')
    expect(events[1].ts).toBe(1500)
  })

  it('T0E: flattens nested probe results from Android format', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'diagnostic_detail_test.json': makeDiagJson(),
    })
    const session = await importZip(file)
    const probes = session.diagnostics!.probeResults

    // 2 PCM results + 1 BCM result = 3 flattened entries
    expect(probes).toHaveLength(3)
    expect(probes[0].module).toBe('PCM')
    expect(probes[0].did).toBe('0x0110')
    expect(probes[0].status).toBe('found')
    expect(probes[0].responseHex).toBe('6201100055')
    expect(probes[1].module).toBe('PCM')
    expect(probes[1].did).toBe('0x0304')
    expect(probes[2].module).toBe('BCM')
    expect(probes[2].status).toBe('not_found')
  })
})

// ═══════════════════════════════════════════════════════════════════════════
// T6C: NRC suppression parsing test
// ═══════════════════════════════════════════════════════════════════════════

describe('NRC suppression parsing (T6C)', () => {
  it('parses nrcSuppression from diagnostic detail JSON', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'diagnostic_detail_test.json': makeDiagJson(),
    })
    const session = await importZip(file)
    const nrc = session.diagnostics!.nrcSuppression

    expect(nrc).toBeDefined()
    expect(nrc).toHaveLength(1)
    expect(nrc![0].did).toBe('0x0462')
    expect(nrc![0].nrcCode).toBe(49)
    expect(nrc![0].nrcName).toBe('requestOutOfRange')
    expect(nrc![0].firstSeenMs).toBe(5000)
    expect(nrc![0].suppressedAfterCount).toBe(3)
  })

  it('returns undefined nrcSuppression when absent', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const file = await buildZipFile({
      'drive_test.csv': csv,
      'diagnostic_detail_test.json': makeDiagJson({ nrcSuppression: undefined }),
    })
    const session = await importZip(file)
    // nrcSuppression should be undefined when not in the JSON
    expect(session.diagnostics!.nrcSuppression).toBeUndefined()
  })
})

// ═══════════════════════════════════════════════════════════════════════════
// T6D: Batch import tests
// ═══════════════════════════════════════════════════════════════════════════

describe('batch import (T6D)', () => {
  it('imports multiple drives from batch ZIP', async () => {
    const csv1 = [CSV_HEADER, makeCsvRow(1000000, { rpm: 3000 }), makeCsvRow(1001000, { rpm: 4000 })].join('\n')
    const csv2 = [CSV_HEADER, makeCsvRow(2000000, { rpm: 5000 }), makeCsvRow(2001000, { rpm: 6000 })].join('\n')

    const manifest = makeManifest([
      { name: 'drive_1_20260419_100000.csv', type: 'drive_csv' },
      { name: 'drive_2_20260419_120000.csv', type: 'drive_csv' },
    ])

    const file = await buildZipFile({
      'drive_1_20260419_100000.csv': csv1,
      'drive_2_20260419_120000.csv': csv2,
      'manifest.json': manifest,
    })
    const sessions = await importBatchZip(file)

    expect(sessions).toHaveLength(2)
    expect(sessions[0].trip!.points[0].rpm).toBe(3000)
    expect(sessions[1].trip!.points[0].rpm).toBe(5000)
  })

  it('matches per-drive profile JSON by prefix', async () => {
    const csv1 = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const csv2 = [CSV_HEADER, makeCsvRow(2000000), makeCsvRow(2001000)].join('\n')
    const profile1 = makeProfileJson({ drive: { startTime: 1000000, endTime: 1010000, distanceKm: 5, avgSpeedKph: 50, maxSpeedKph: 100, peakRpm: 5500, peakBoostPsi: 15, peakLateralG: 0.5, peakOilTempC: 100, peakCoolantTempC: 95, fuelUsedL: 1, aggressionScore: 30, tags: 'CRUISE' } })
    const profile2 = makeProfileJson({ drive: { startTime: 2000000, endTime: 2010000, distanceKm: 15, avgSpeedKph: 90, maxSpeedKph: 200, peakRpm: 7000, peakBoostPsi: 24, peakLateralG: 1.1, peakOilTempC: 125, peakCoolantTempC: 108, fuelUsedL: 5, aggressionScore: 85, tags: 'TRACK' } })

    const manifest = makeManifest([
      { name: 'drive_1_20260419_100000.csv', type: 'drive_csv' },
      { name: 'drive_1_20260419_100000_profile.json', type: 'drive_profile' },
      { name: 'drive_2_20260419_120000.csv', type: 'drive_csv' },
      { name: 'drive_2_20260419_120000_profile.json', type: 'drive_profile' },
    ])

    const file = await buildZipFile({
      'drive_1_20260419_100000.csv': csv1,
      'drive_1_20260419_100000_profile.json': profile1,
      'drive_2_20260419_120000.csv': csv2,
      'drive_2_20260419_120000_profile.json': profile2,
      'manifest.json': manifest,
    })
    const sessions = await importBatchZip(file)

    expect(sessions).toHaveLength(2)
    expect(sessions[0].tags).toEqual(['CRUISE'])
    expect(sessions[0].trip!.summary.peakRpm).toBe(5500)
    expect(sessions[1].tags).toEqual(['TRACK'])
    expect(sessions[1].trip!.summary.peakRpm).toBe(7000)
  })

  it('handles drives without profile JSON', async () => {
    const csv1 = [CSV_HEADER, makeCsvRow(1000000, { rpm: 4000 }), makeCsvRow(1001000, { rpm: 5000 })].join('\n')
    const csv2 = [CSV_HEADER, makeCsvRow(2000000, { rpm: 3000 }), makeCsvRow(2001000, { rpm: 3500 })].join('\n')

    const file = await buildZipFile({
      'drive_1_20260419_100000.csv': csv1,
      'drive_2_20260419_120000.csv': csv2,
    })
    const sessions = await importBatchZip(file)

    expect(sessions).toHaveLength(2)
    // Should work without profiles, using CSV-computed summary
    expect(sessions[0].trip!.summary.peakRpm).toBe(5000)
    expect(sessions[1].trip!.summary.peakRpm).toBe(3500)
    expect(sessions[0].tags).toEqual([])
  })

  it('single-drive ZIP still works via importZip', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000), makeCsvRow(1001000)].join('\n')
    const manifest = makeManifest([
      { name: 'drive_20260419_143000.csv', type: 'drive_csv' },
    ])
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'manifest.json': manifest,
    })
    const session = await importZip(file)
    expect(session.trip).not.toBeNull()
    expect(session.trip!.points).toHaveLength(2)
  })
})

// ═══════════════════════════════════════════════════════════════════════════
// T1G: Manifest metadata test
// ═══════════════════════════════════════════════════════════════════════════

describe('manifest metadata (T1G)', () => {
  it('parses appBuild and exportedAt from manifest.json', async () => {
    const csv = [CSV_HEADER, makeCsvRow(1000000)].join('\n')
    const manifest = makeManifest([
      { name: 'drive_20260419_143000.csv', type: 'drive_csv' },
    ])
    const file = await buildZipFile({
      'drive_20260419_143000.csv': csv,
      'manifest.json': manifest,
    })
    const session = await importZip(file)
    expect(session.meta.appVersion).toBe('2.2.7-rc.3')
    expect(session.meta.appBuild).toBe(35)
    expect(session.meta.exportedAt).toBe(1713500000000)
  })
})
