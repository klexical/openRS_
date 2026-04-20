import JSZip from 'jszip'
import type {
  Session, SessionMeta, TripData, TripPoint, TripSummary, PeakEvent,
  DiagnosticData, DtcEntry, CanFrame, FpsSample, SessionEvent, DecodeEntry, ProbeResult,
  DriveProfile, Bookmark, ThermalProgression, ModeTimelineEntry, NrcSuppression,
  FreezeFrame, FreezeFrameEntry,
} from '../types/session'

/** Manifest schema for typed file identification in exported ZIPs. */
interface ManifestEntry {
  name: string
  type: string
}

interface ExportManifest {
  version: number
  app: string
  appVersion: string
  appBuild?: number
  exportedAt: number
  files: ManifestEntry[]
}

/** Read manifest.json from ZIP if present. */
async function readManifest(zip: JSZip): Promise<ExportManifest | null> {
  const f = zip.files['manifest.json']
  if (!f) return null
  try {
    return JSON.parse(await f.async('string'))
  } catch {
    return null
  }
}

/** Import a ZIP file exported by the openRS_ Android app. */
export async function importZip(file: File): Promise<Session> {
  const zip = await JSZip.loadAsync(file)
  const manifest = await readManifest(zip)

  // Detect batch ZIP (multiple drive CSVs)
  const driveCsvCount = countDriveCsvs(zip, manifest)
  if (driveCsvCount > 1) {
    // For single importZip, import the first drive only.
    // Use importBatchZip for full batch import.
    const sessions = await importBatchZipInner(zip, manifest, file.name)
    if (sessions.length > 0) return sessions[0]
  }

  return importSingleDrive(zip, manifest, file.name)
}

/** Import a batch ZIP containing multiple drives. Returns one Session per drive. */
export async function importBatchZip(file: File): Promise<Session[]> {
  const zip = await JSZip.loadAsync(file)
  const manifest = await readManifest(zip)
  return importBatchZipInner(zip, manifest, file.name)
}

/** Detect whether a ZIP is a batch export (multiple drive CSVs). */
export function isBatchZip(manifest: ExportManifest | null, zip: JSZip): boolean {
  return countDriveCsvs(zip, manifest) > 1
}

function countDriveCsvs(zip: JSZip, manifest: ExportManifest | null): number {
  if (manifest) {
    return manifest.files.filter((e) => e.type === 'drive_csv').length
  }
  return Object.keys(zip.files).filter(
    (f) => (f.startsWith('trip_') || f.startsWith('drive_')) && f.endsWith('.csv') && !f.includes('racechrono') && !f.includes('trackaddict')
  ).length
}

async function importBatchZipInner(zip: JSZip, manifest: ExportManifest | null, filename: string): Promise<Session[]> {
  // Find all drive CSV files
  const csvFiles: string[] = manifest
    ? manifest.files.filter((e) => e.type === 'drive_csv').map((e) => e.name)
    : Object.keys(zip.files).filter(
        (f) => (f.startsWith('trip_') || f.startsWith('drive_')) && f.endsWith('.csv') && !f.includes('racechrono') && !f.includes('trackaddict')
      )

  const sessions: Session[] = []
  for (const csvFile of csvFiles) {
    const id = crypto.randomUUID()
    const trip = await parseTripDataFromCsv(zip, csvFile)
    if (!trip) continue

    // Try to find matching profile JSON by prefix
    const prefix = extractDrivePrefix(csvFile)
    const profileFile = prefix ? findMatchingFile(zip, manifest, prefix, 'drive_profile', '.json') : null
    if (profileFile) {
      await mergeProfileJson(zip, profileFile, trip)
    }

    // DTC data (shared across batch — use first found)
    const dtcResults = await parseDtcData(zip, manifest)
    const diagnostics = await parseDiagnosticData(zip, dtcResults, manifest)
    const meta = await parseMeta(zip, filename, manifest)
    const name = buildSessionName(meta, trip, csvFile)

    const tags: string[] = []
    if (trip.profile?.drive.tags) {
      tags.push(...trip.profile.drive.tags.split(',').map((t) => t.trim()).filter(Boolean))
    }

    sessions.push({ id, name, importedAt: Date.now(), tags, trip, diagnostics, meta })
  }
  return sessions
}

function extractDrivePrefix(csvFilename: string): string | null {
  // Match patterns like drive_42_20260419_143000 or drive_20260419_143000
  const match = csvFilename.match(/^(drive_\d+_\d{8}_\d{6})/) || csvFilename.match(/^(drive_\d{8}_\d{6})/)
  return match ? match[1] : null
}

function findMatchingFile(zip: JSZip, manifest: ExportManifest | null, prefix: string, type: string, ext: string): string | null {
  if (manifest) {
    const entry = manifest.files.find((e) => e.type === type && e.name.startsWith(prefix))
    return entry?.name ?? null
  }
  return Object.keys(zip.files).find((f) => f.startsWith(prefix) && f.endsWith(ext)) ?? null
}

async function importSingleDrive(zip: JSZip, manifest: ExportManifest | null, filename: string): Promise<Session> {
  const id = crypto.randomUUID()
  const trip = await parseTripData(zip, manifest)

  // Parse profile JSON and merge into trip data
  if (trip) {
    const profileFile = manifest
      ? manifest.files.find((e) => e.type === 'drive_profile')?.name
      : Object.keys(zip.files).find((f) => f.startsWith('drive_profile_') && f.endsWith('.json'))
    if (profileFile) {
      await mergeProfileJson(zip, profileFile, trip)
    }
  }

  // Prefer DTC JSON over TXT when available
  const dtcResults = await parseDtcJsonData(zip, manifest) ?? await parseDtcData(zip, manifest)
  const diagnostics = await parseDiagnosticData(zip, dtcResults, manifest)
  const meta = await parseMeta(zip, filename, manifest)
  const name = buildSessionName(meta, trip, filename)

  const tags: string[] = []
  if (trip?.profile?.drive.tags) {
    tags.push(...trip.profile.drive.tags.split(',').map((t) => t.trim()).filter(Boolean))
  }

  return { id, name, importedAt: Date.now(), tags, trip, diagnostics, meta }
}

// ── Trip CSV parsing ──

async function parseTripData(zip: JSZip, manifest: ExportManifest | null): Promise<TripData | null> {
  const csvFile = manifest
    ? manifest.files.find((e) => e.type === 'drive_csv')?.name
    : Object.keys(zip.files).find(
        (f) => (f.startsWith('trip_') || f.startsWith('drive_')) && f.endsWith('.csv') && !f.includes('racechrono') && !f.includes('trackaddict')
      )
  if (!csvFile) return null
  return parseTripDataFromCsv(zip, csvFile)
}

async function parseTripDataFromCsv(zip: JSZip, csvFile: string): Promise<TripData | null> {
  const file = zip.files[csvFile]
  if (!file) return null

  const csv = await file.async('string')
  const lines = csv.trim().split('\n').filter((l) => !l.startsWith('#'))
  if (lines.length < 2) return null

  const headers = lines[0].split(',').map((h) => h.trim())
  const points: TripPoint[] = []

  for (let i = 1; i < lines.length; i++) {
    const vals = lines[i].split(',')
    if (vals.length < headers.length) continue
    const row = Object.fromEntries(headers.map((h, j) => [h, vals[j]?.trim() ?? '']))
    points.push(parseTripRow(row))
  }

  const summary = computeSummary(points)
  return { points, summary }
}

function parseTripRow(row: Record<string, string>): TripPoint {
  return {
    ts: parseFloat(row['timestamp_ms'] || row['timestamp'] || row['ts'] || '0'),
    lat: parseFloat(row['lat'] || '0'),
    lng: parseFloat(row['lng'] || row['lon'] || '0'),
    rpm: parseFloat(row['rpm'] || '0'),
    boostPsi: parseFloat(row['boostPsi'] || row['boost_psi'] || '0'),
    speedKph: parseFloat(row['speedKph'] || row['speed_kph'] || '0'),
    coolantC: parseFloat(row['coolantC'] || row['coolant_c'] || '-99'),
    oilTempC: parseFloat(row['oilTempC'] || row['oil_c'] || row['oil_temp_c'] || '-99'),
    rduTempC: parseFloat(row['rduTempC'] || row['rdu_c'] || row['rdu_temp_c'] || '-99'),
    ptuTempC: parseFloat(row['ptuTempC'] || row['ptu_c'] || row['ptu_temp_c'] || '-99'),
    ambientC: parseFloat(row['ambientC'] || row['ambient_c'] || '-99'),
    latG: parseFloat(row['latG'] || row['lateral_g'] || row['lat_g'] || '0'),
    longG: parseFloat(row['longG'] || row['longitudinal_g'] || row['long_g'] || '0'),  // T0F: fixed
    fuelPct: parseFloat(row['fuelPct'] || row['fuel_pct'] || '-1'),
    wheelSpeedFL: parseFloat(row['wheelSpeedFL'] || row['wheel_fl_kph'] || row['ws_fl'] || '0'),
    wheelSpeedFR: parseFloat(row['wheelSpeedFR'] || row['wheel_fr_kph'] || row['ws_fr'] || '0'),
    wheelSpeedRL: parseFloat(row['wheelSpeedRL'] || row['wheel_rl_kph'] || row['ws_rl'] || '0'),
    wheelSpeedRR: parseFloat(row['wheelSpeedRR'] || row['wheel_rr_kph'] || row['ws_rr'] || '0'),
    driveMode: row['driveMode'] || row['drive_mode'] || 'NORMAL',
    awdTorqueL: parseFloat(row['awdTorqueL'] || row['awd_torque_l'] || '0'),
    awdTorqueR: parseFloat(row['awdTorqueR'] || row['awd_torque_r'] || '0'),
    gear: row['gear'] || '',
    throttlePct: parseFloat(row['throttlePct'] || row['throttle_pct'] || '-1'),
    tirePressLF: parseFloat(row['tirePressLF'] || row['tire_press_lf_psi'] || '-1'),
    tirePressRF: parseFloat(row['tirePressRF'] || row['tire_press_rf_psi'] || '-1'),
    tirePressLR: parseFloat(row['tirePressLR'] || row['tire_press_lr_psi'] || '-1'),
    tirePressRR: parseFloat(row['tirePressRR'] || row['tire_press_rr_psi'] || '-1'),
    tireTempLF: parseFloat(row['tireTempLF'] || row['tire_temp_lf_c'] || '-99'),
    tireTempRF: parseFloat(row['tireTempRF'] || row['tire_temp_rf_c'] || '-99'),
    tireTempLR: parseFloat(row['tireTempLR'] || row['tire_temp_lr_c'] || '-99'),
    tireTempRR: parseFloat(row['tireTempRR'] || row['tire_temp_rr_c'] || '-99'),
    brakePressure: parseFloat(row['brakePressure'] || row['brake_pressure'] || '0'),      // T1A
    steeringAngle: parseFloat(row['steeringAngle'] || row['steering_angle'] || '0'),      // T1A
    raceReady: (row['raceReady'] || row['race_ready'] || 'false') === 'true',             // T1A
  }
}

// ── Profile JSON parsing (T1D) ──

async function mergeProfileJson(zip: JSZip, profileFile: string, trip: TripData): Promise<void> {
  const file = zip.files[profileFile]
  if (!file) return

  try {
    const raw = await file.async('string')
    const data = JSON.parse(raw)
    const profile = parseProfile(data)
    trip.profile = profile

    // Promote profile data to TripData for ergonomic access
    if (profile.thermalProgression) trip.thermalProgression = profile.thermalProgression
    if (profile.bookmarks?.length) trip.bookmarks = profile.bookmarks
    if (profile.drive.aggressionScore > 0) trip.aggressionScore = profile.drive.aggressionScore
    if (profile.modeTimeline?.length) trip.canonicalModeTimeline = profile.modeTimeline

    // Override CSV-computed summary with canonical profile values
    const d = profile.drive
    const s = trip.summary
    if (d.peakRpm > 0) s.peakRpm = d.peakRpm
    if (d.peakBoostPsi > 0) s.peakBoostPsi = d.peakBoostPsi
    if (d.peakLateralG > 0) s.peakLatG = d.peakLateralG
    if (d.maxSpeedKph > 0) s.peakSpeedKph = d.maxSpeedKph
    if (d.peakOilTempC > -90) s.peakOilTempC = d.peakOilTempC
    if (d.peakCoolantTempC > -90) s.peakCoolantC = d.peakCoolantTempC
    if (d.fuelUsedL > 0) s.fuelUsedL = d.fuelUsedL
    if (d.aggressionScore > 0) s.aggressionScore = d.aggressionScore

    // Override peak events with profile peaks (GPS-tagged, canonical)
    if (profile.peaks.length > 0) {
      s.peakEvents = profile.peaks.map((p) => ({
        type: p.type === 'RPM' ? 'rpm' : p.type === 'BOOST' ? 'boost' : p.type === 'LATERAL_G' ? 'latG' : 'speed',
        value: p.value,
        ts: p.timestampMs,
        lat: p.lat,
        lng: p.lng,
      }))
    }

    // Parse laps/perfRuns if present (T5B/T5C stubs)
    if (data.laps && Array.isArray(data.laps)) {
      trip.laps = data.laps.map((l: Record<string, unknown>) => ({
        lapNumber: Number(l.lapNumber || 0),
        lapTimeMs: Number(l.lapTimeMs || 0),
        peakRpm: l.peakRpm != null ? Number(l.peakRpm) : undefined,
        peakBoostPsi: l.peakBoostPsi != null ? Number(l.peakBoostPsi) : undefined,
        peakLateralG: l.peakLateralG != null ? Number(l.peakLateralG) : undefined,
        peakSpeedKph: l.peakSpeedKph != null ? Number(l.peakSpeedKph) : undefined,
      }))
    }
    if (data.perfRuns && Array.isArray(data.perfRuns)) {
      trip.perfRuns = data.perfRuns.map((r: Record<string, unknown>) => ({
        type: String(r.type || ''),
        timeMs: Number(r.timeMs || 0),
        launchRpm: r.launchRpm != null ? Number(r.launchRpm) : undefined,
        peakBoostPsi: r.peakBoostPsi != null ? Number(r.peakBoostPsi) : undefined,
        densityAltFt: r.densityAltFt != null ? Number(r.densityAltFt) : undefined,
        ambientTempC: r.ambientTempC != null ? Number(r.ambientTempC) : undefined,
      }))
    }
  } catch {
    // Profile JSON parse failure is non-fatal
  }
}

function parseProfile(data: Record<string, unknown>): DriveProfile {
  const d = (data.drive || {}) as Record<string, unknown>
  const drive = {
    startTime: Number(d.startTime || 0),
    endTime: Number(d.endTime || 0),
    distanceKm: Number(d.distanceKm || 0),
    avgSpeedKph: Number(d.avgSpeedKph || 0),
    maxSpeedKph: Number(d.maxSpeedKph || 0),
    peakRpm: Number(d.peakRpm || 0),
    peakBoostPsi: Number(d.peakBoostPsi || 0),
    peakLateralG: Number(d.peakLateralG || 0),
    peakOilTempC: Number(d.peakOilTempC ?? -99),
    peakCoolantTempC: Number(d.peakCoolantTempC ?? -99),
    fuelUsedL: Number(d.fuelUsedL || 0),
    aggressionScore: Number(d.aggressionScore || 0),
    tags: String(d.tags || ''),
    avgFuelL100km: d.avgFuelL100km != null ? Number(d.avgFuelL100km) : undefined,
    avgFuelMpg: d.avgFuelMpg != null ? Number(d.avgFuelMpg) : undefined,
  }

  const peaksRaw = Array.isArray(data.peaks) ? data.peaks : []
  const peaks = peaksRaw.map((p: Record<string, unknown>) => ({
    type: String(p.type || 'RPM') as 'RPM' | 'BOOST' | 'LATERAL_G' | 'SPEED',
    value: Number(p.value || 0),
    lat: Number(p.lat || 0),
    lng: Number(p.lng || 0),
    timestampMs: Number(p.timestampMs || 0),
  }))

  const thermalRaw = (data.thermalProgression || {}) as Record<string, unknown>
  const parseCurve = (c: unknown) => {
    const obj = (c || {}) as Record<string, unknown>
    return { startC: Number(obj.startC ?? -99), peakC: Number(obj.peakC ?? -99), endC: Number(obj.endC ?? -99) }
  }
  const thermalProgression: ThermalProgression = {
    oil: parseCurve(thermalRaw.oil),
    coolant: parseCurve(thermalRaw.coolant),
    ...(thermalRaw.rdu ? { rdu: parseCurve(thermalRaw.rdu) } : {}),
    ...(thermalRaw.ptu ? { ptu: parseCurve(thermalRaw.ptu) } : {}),
  }

  const timelineRaw = Array.isArray(data.modeTimeline) ? data.modeTimeline : []
  const modeTimeline: ModeTimelineEntry[] = timelineRaw.map((e: Record<string, unknown>) => ({
    mode: String(e.mode || 'NORMAL'),
    startMs: Number(e.startMs || 0),
    endMs: Number(e.endMs || 0),
  }))

  const bookmarksRaw = Array.isArray(data.bookmarks) ? data.bookmarks : []
  const bookmarks: Bookmark[] = bookmarksRaw.map((b: Record<string, unknown>) => ({
    timestamp: Number(b.timestamp || 0),
    lat: Number(b.lat || 0),
    lng: Number(b.lng || 0),
    label: String(b.label || ''),
    speedKph: Number(b.speedKph || 0),
    rpm: Number(b.rpm || 0),
    boostPsi: Number(b.boostPsi || 0),
  }))

  return { version: Number(data.version || 1), drive, peaks, thermalProgression, modeTimeline, bookmarks }
}

// ── Summary computation ──

function computeSummary(points: TripPoint[]): TripSummary {
  if (points.length === 0) return emptySummary()

  const durationMs = points[points.length - 1].ts - points[0].ts
  let distanceKm = 0
  for (let i = 1; i < points.length; i++) {
    distanceKm += haversine(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
  }

  const rpmArr = points.map((p) => p.rpm)
  const boostArr = points.map((p) => p.boostPsi)
  const speedArr = points.map((p) => p.speedKph)
  const latGArr = points.map((p) => Math.abs(p.latG))
  const longGArr = points.map((p) => Math.abs(p.longG))
  const coolantArr = points.map((p) => p.coolantC).filter((v) => v > -90)
  const oilArr = points.map((p) => p.oilTempC).filter((v) => v > -90)

  const modeBreakdown: Record<string, number> = {}
  for (let i = 1; i < points.length; i++) {
    const mode = points[i - 1].driveMode
    const dt = (points[i].ts - points[i - 1].ts) / 1000
    modeBreakdown[mode] = (modeBreakdown[mode] || 0) + dt
  }

  const peakEvents: PeakEvent[] = []
  const addPeak = (type: string, arr: number[], pts: TripPoint[]) => {
    const maxVal = Math.max(...arr)
    const idx = arr.indexOf(maxVal)
    if (idx >= 0 && pts[idx]) {
      peakEvents.push({ type, value: maxVal, ts: pts[idx].ts, lat: pts[idx].lat, lng: pts[idx].lng })
    }
  }
  addPeak('rpm', rpmArr, points)
  addPeak('boost', boostArr, points)
  addPeak('speed', speedArr, points)
  addPeak('latG', latGArr.map((_, i) => Math.abs(points[i].latG)), points)

  return {
    distanceKm,
    durationMs,
    fuelUsedL: computeFuelUsedL(points),
    avgSpeedKph: avg(speedArr),
    peakRpm: Math.max(...rpmArr),
    peakBoostPsi: Math.max(...boostArr),
    peakLatG: Math.max(...latGArr),
    peakLongG: Math.max(...longGArr),
    peakSpeedKph: Math.max(...speedArr),
    peakCoolantC: coolantArr.length > 0 ? Math.max(...coolantArr) : -99,
    peakOilTempC: oilArr.length > 0 ? Math.max(...oilArr) : -99,
    avgRpm: avg(rpmArr),
    avgFuelEconomy: computeAvgFuelEconomy(computeFuelUsedL(points), distanceKm),
    modeBreakdown,
    peakEvents,
  }
}

function emptySummary(): TripSummary {
  return {
    distanceKm: 0, durationMs: 0, fuelUsedL: 0, avgSpeedKph: 0,
    peakRpm: 0, peakBoostPsi: 0, peakLatG: 0, peakLongG: 0, peakSpeedKph: 0,
    peakCoolantC: -99, peakOilTempC: -99, avgRpm: 0, avgFuelEconomy: 0,
    modeBreakdown: {}, peakEvents: [],
  }
}

function avg(arr: number[]): number {
  return arr.length === 0 ? 0 : arr.reduce((a, b) => a + b, 0) / arr.length
}

function haversine(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371
  const dLat = (lat2 - lat1) * Math.PI / 180
  const dLon = (lon2 - lon1) * Math.PI / 180
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) ** 2
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

const FOCUS_RS_TANK_LITRES = 52.3

function computeFuelUsedL(points: TripPoint[]): number {
  const validFuel = points.map((p) => p.fuelPct).filter((v) => v >= 0)
  if (validFuel.length < 2) return 0
  const delta = validFuel[0] - validFuel[validFuel.length - 1]
  return delta > 0 ? (delta / 100) * FOCUS_RS_TANK_LITRES : 0
}

function computeAvgFuelEconomy(fuelUsedL: number, distanceKm: number): number {
  if (fuelUsedL <= 0 || distanceKm <= 0.1) return 0
  return (fuelUsedL / distanceKm) * 100 // L/100km
}

// ── DTC text parsing ──

async function parseDtcData(zip: JSZip, manifest: ExportManifest | null): Promise<DtcEntry[]> {
  const dtcFile = manifest
    ? manifest.files.find((e) => e.type === 'dtc_text')?.name
    : Object.keys(zip.files).find(
        (f) => f.startsWith('dtc_scan_') && f.endsWith('.txt')
      )
  if (!dtcFile) return []

  const text = await zip.files[dtcFile].async('string')
  const results: DtcEntry[] = []
  let currentModule = ''

  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    const modMatch = trimmed.match(/^─+\s+(\w+)\s+─/)
    if (modMatch) {
      currentModule = modMatch[1]
      continue
    }
    const dtcMatch = trimmed.match(/^([A-Z]\d{4})\s+\[(\w+)]\s+(.*)/)
    if (dtcMatch && currentModule) {
      results.push({
        module: currentModule,
        code: dtcMatch[1],
        status: dtcMatch[2],
        description: dtcMatch[3].trim(),
      })
    }
  }
  return results
}

// ── DTC JSON parsing (T1E — preferred over TXT) ──

async function parseDtcJsonData(zip: JSZip, manifest: ExportManifest | null): Promise<DtcEntry[] | null> {
  const jsonFile = manifest
    ? manifest.files.find((e) => e.type === 'dtc_json')?.name
    : Object.keys(zip.files).find(
        (f) => f.startsWith('dtc_scan_') && f.endsWith('.json')
      )
  if (!jsonFile || !zip.files[jsonFile]) return null

  try {
    const raw = await zip.files[jsonFile].async('string')
    const data = JSON.parse(raw)
    const codes = Array.isArray(data.codes) ? data.codes : []

    return codes.map((c: Record<string, unknown>): DtcEntry => {
      const entry: DtcEntry = {
        module: String(c.module || ''),
        code: String(c.code || ''),
        status: String(c.status || 'UNKNOWN'),
        description: String(c.description || ''),
        severity: c.severity ? String(c.severity) : undefined,
      }
      // Parse freeze frame if present
      const ff = c.freezeFrame as Record<string, unknown> | undefined
      if (ff && ff.entries) {
        const entries = Array.isArray(ff.entries) ? ff.entries : []
        entry.freezeFrame = {
          recordNumber: Number(ff.recordNumber || 0),
          entries: entries.map((e: Record<string, unknown>): FreezeFrameEntry => ({
            did: String(e.did || ''),
            label: String(e.label || ''),
            value: String(e.value || ''),
          })),
        }
      }
      return entry
    })
  } catch {
    return null
  }
}

// ── Diagnostic JSON parsing ──

async function parseDiagnosticData(zip: JSZip, dtcResults: DtcEntry[], manifest: ExportManifest | null): Promise<DiagnosticData | null> {
  const jsonFile = manifest
    ? manifest.files.find((e) => e.type === 'diagnostic_detail')?.name
    : Object.keys(zip.files).find(
        (f) => f.startsWith('diagnostic_detail_') && f.endsWith('.json')
      )
  if (!jsonFile) {
    if (dtcResults.length > 0) {
      return { canInventory: [], fpsTimeline: [], sessionEvents: [], decodeTrace: [], probeResults: [], dtcResults }
    }
    return null
  }

  const raw = await zip.files[jsonFile].async('string')
  const data = JSON.parse(raw)

  return {
    canInventory: parseCanInventory(data),
    fpsTimeline: parseFpsTimeline(data),
    sessionEvents: parseSessionEvents(data),
    decodeTrace: parseDecodeTrace(data),
    probeResults: parseProbeResults(data),
    dtcResults,
    nrcSuppression: parseNrcSuppression(data),
  }
}

// T0A: Fixed — Android uses "canFrameInventory" object keyed by CAN ID, not an array
function parseCanInventory(data: Record<string, unknown>): CanFrame[] {
  // Try object format first (Android actual output)
  const inv = data.canFrameInventory || data.canInventory || data.can_inventory
  if (!inv) return []

  // Object keyed by CAN ID hex (actual Android format)
  if (typeof inv === 'object' && !Array.isArray(inv)) {
    return Object.entries(inv as Record<string, Record<string, unknown>>)
      .map(([id, info]) => ({
        id,
        count: Number(info.totalReceived || info.count || 0),
        changed: Boolean(info.hasChanged ?? info.changed),
        firstHex: String(info.firstRawHex || info.firstHex || ''),
        lastHex: String(info.lastRawHex || info.lastHex || ''),
        samples: Array.isArray(info.periodicSamples)
          ? (info.periodicSamples as Record<string, unknown>[]).map((s) => String(s.rawHex || ''))
          : Array.isArray(info.samples) ? (info.samples as string[]).map(String) : [],
      }))
      .sort((a, b) => a.id.localeCompare(b.id))
  }

  // Array format (legacy/fallback)
  if (Array.isArray(inv)) {
    return (inv as Record<string, unknown>[]).map((f) => ({
      id: String(f.id || f.canId || ''),
      count: Number(f.count || 0),
      changed: Boolean(f.changed),
      firstHex: String(f.firstHex || f.first_hex || ''),
      lastHex: String(f.lastHex || f.last_hex || ''),
      samples: Array.isArray(f.samples) ? (f.samples as string[]).map(String) : [],
    }))
  }

  return []
}

// T0B: Fixed — Android uses "relMs", not "ts"/"timestamp"
function parseFpsTimeline(data: Record<string, unknown>): FpsSample[] {
  const fps = data.fpsTimeline || data.fps_timeline || []
  if (!Array.isArray(fps)) return []
  return (fps as Record<string, unknown>[]).map((s) => ({
    ts: Number(s.relMs || s.ts || s.timestamp || 0),
    fps: Number(s.fps || 0),
  }))
}

// T0D: Fixed — Android uses "relMs", not "ts"/"timestamp"
function parseSessionEvents(data: Record<string, unknown>): SessionEvent[] {
  const events = data.sessionEvents || data.session_events || []
  if (!Array.isArray(events)) return []
  return (events as Record<string, unknown>[]).map((e) => ({
    ts: Number(e.relMs || e.ts || e.timestamp || 0),
    type: String(e.type || 'info'),
    message: String(e.message || e.msg || ''),
  }))
}

// T0C: Fixed — Android uses "id", not "canId"/"can_id"
function parseDecodeTrace(data: Record<string, unknown>): DecodeEntry[] {
  const trace = data.decodeTrace || data.decode_trace || []
  if (!Array.isArray(trace)) return []
  return (trace as Record<string, unknown>[]).map((d) => ({
    ts: Number(d.relMs || d.ts || d.timestamp || 0),
    canId: String(d.id || d.canId || d.can_id || ''),
    rawHex: String(d.raw || d.rawHex || d.raw_hex || ''),
    decoded: String(d.decoded || ''),
    issue: String(d.issue || ''),
  }))
}

// T0E: Fixed — Android nests results under probe sessions
function parseProbeResults(data: Record<string, unknown>): ProbeResult[] {
  const probes = data.probeResults || data.probe_results || []
  if (!Array.isArray(probes)) return []

  const results: ProbeResult[] = []
  for (const p of probes as Record<string, unknown>[]) {
    // Check if this is a nested probe session (Android format)
    if (p.results && Array.isArray(p.results)) {
      const module = String(p.module || '')
      for (const r of p.results as Record<string, unknown>[]) {
        results.push({
          module,
          did: String(r.did || ''),
          status: String(r.status || '').toLowerCase(),
          responseHex: String(r.response || r.responseHex || ''),
          description: '',
        })
      }
    } else {
      // Flat format (legacy/fallback)
      results.push({
        module: String(p.module || ''),
        did: String(p.did || ''),
        status: String(p.status || ''),
        responseHex: String(p.responseHex || p.response_hex || ''),
        description: String(p.description || ''),
      })
    }
  }
  return results
}

// T1F: Parse NRC suppression
function parseNrcSuppression(data: Record<string, unknown>): NrcSuppression[] | undefined {
  const nrc = data.nrcSuppression || data.nrc_suppression
  if (!Array.isArray(nrc) || nrc.length === 0) return undefined
  return (nrc as Record<string, unknown>[]).map((n) => ({
    did: String(n.did || ''),
    nrcCode: Number(n.nrcCode || 0),
    nrcName: String(n.nrcName || 'unknown'),
    firstSeenMs: Number(n.firstSeenMs || 0),
    suppressedAfterCount: Number(n.suppressedAfterCount || 0),
  }))
}

// ── Meta (T1G: manifest metadata) ──

async function parseMeta(zip: JSZip, filename: string, manifest: ExportManifest | null): Promise<SessionMeta> {
  if (manifest) {
    return {
      appVersion: manifest.appVersion || 'unknown',
      firmwareVersion: 'unknown',
      sessionStart: '',
      generatedAt: new Date(manifest.exportedAt).toISOString(),
      appBuild: manifest.appBuild,
      exportedAt: manifest.exportedAt,
    }
  }

  const summaryFile = Object.keys(zip.files).find(
    (f) => f.startsWith('diagnostic_summary_') || f.startsWith('trip_summary_') || f.startsWith('drive_summary_')
  )

  let appVersion = 'unknown'
  let firmwareVersion = 'unknown'
  let sessionStart = ''

  if (summaryFile) {
    const text = await zip.files[summaryFile].async('string')
    const versionMatch = text.match(/App Version:\s*(.+)/i)
    if (versionMatch) appVersion = versionMatch[1].trim()
    const fwMatch = text.match(/Firmware:\s*(.+)/i)
    if (fwMatch) firmwareVersion = fwMatch[1].trim()
    const startMatch = text.match(/Session Start:\s*(.+)/i)
    if (startMatch) sessionStart = startMatch[1].trim()
  }

  if (!sessionStart) {
    const tsMatch = filename.match(/(\d{8}_\d{6})/)
    if (tsMatch) {
      const [d, t] = tsMatch[1].split('_')
      sessionStart = `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)} ${t.slice(0, 2)}:${t.slice(2, 4)}:${t.slice(4, 6)}`
    }
  }

  return { appVersion, firmwareVersion, sessionStart, generatedAt: new Date().toISOString() }
}

function buildSessionName(meta: SessionMeta, trip: TripData | null, filename: string): string {
  const date = meta.sessionStart || fmtDateFromFilename(filename)
  const type = trip ? 'Trip' : 'Diagnostic'
  return `${type} — ${date}`
}

function fmtDateFromFilename(filename: string): string {
  const match = filename.match(/(\d{8})/)
  if (!match) return filename.replace('.zip', '')
  const d = match[1]
  return `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)}`
}
