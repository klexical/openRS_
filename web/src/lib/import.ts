import JSZip from 'jszip'
import type {
  Session, SessionMeta, TripData, TripPoint, TripSummary, PeakEvent,
  DiagnosticData, DtcEntry, CanFrame, FpsSample, SessionEvent, DecodeEntry, ProbeResult,
  DriveProfile, Bookmark, ThermalProgression, ModeTimelineEntry, NrcSuppression,
  FreezeFrameEntry,
} from '../types/session'
import { parseBoostSource, type BoostSource } from './boost'
import { parseExportSchemaVersion } from './exportSchema'
import { recordedDistanceKm } from './distance'

/** Thrown when an imported ZIP cannot be parsed — surfaced to the user via ImportPanel. */
export class ImportError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ImportError'
  }
}

/** Manifest schema for typed file identification in exported ZIPs. */
interface ManifestEntry {
  name: string
  type: string
}

interface ExportManifest {
  /** The manifest's own format, always 1; the export's contract is `exportSchemaVersion` (lib/exportSchema.ts). */
  version: number
  app: string
  appVersion: string
  appBuild?: number
  exportedAt: number
  files: ManifestEntry[]
}

/**
 * True for a drive telemetry CSV (`drive_*.csv` / legacy `trip_*.csv`). Excludes the
 * companion CSVs the app writes beside it — RaceChrono / TrackAddict exports and the
 * knock-event table (`drive_knock_*.csv`, `drive_<id>_<ts>_knock.csv`), which would
 * otherwise import as phantom sessions (#183).
 */
export function isDriveCsvName(name: string): boolean {
  return (name.startsWith('trip_') || name.startsWith('drive_')) && name.endsWith('.csv') &&
    !name.includes('racechrono') && !name.includes('trackaddict') && !name.includes('knock')
}

/**
 * Manifest entry for a drive CSV. Also checks the name: openRS_ v2.2.7-rc.4 manifests
 * tag the knock CSV as `drive_csv`, so the type alone can't be trusted.
 */
export function isDriveCsvEntry(e: { name: string; type: string }): boolean {
  return e.type === 'drive_csv' && isDriveCsvName(e.name)
}

/** manifest.json's root if it's a JSON object; null when absent or unreadable. */
async function readManifestObject(zip: JSZip): Promise<Record<string, unknown> | null> {
  const f = zip.files['manifest.json']
  if (!f) return null
  try {
    const m: unknown = JSON.parse(await f.async('string'))
    return m && typeof m === 'object' && !Array.isArray(m) ? (m as Record<string, unknown>) : null
  } catch {
    return null
  }
}

const isManifestEntry = (e: unknown): boolean =>
  !!e && typeof (e as ManifestEntry).name === 'string' && typeof (e as ManifestEntry).type === 'string'

/**
 * Read manifest.json from ZIP if present. One without a usable file list (a shape this Sapphire doesn't know, e.g. from a
 * newer export) counts as no manifest: the files are then found by name, so the drive isn't lost or the import stopped.
 */
async function readManifest(zip: JSZip): Promise<ExportManifest | null> {
  const m = await readManifestObject(zip)
  return m && Array.isArray(m.files) && m.files.every(isManifestEntry) ? (m as unknown as ExportManifest) : null
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

/** Drive CSVs in the ZIP — from the manifest when it lists any, else by filename (a list this Sapphire can't read loses no drive). */
function driveCsvFiles(zip: JSZip, manifest: ExportManifest | null): string[] {
  const listed = manifest ? manifest.files.filter(isDriveCsvEntry).map((e) => e.name) : []
  return listed.length > 0 ? listed : Object.keys(zip.files).filter(isDriveCsvName)
}

function countDriveCsvs(zip: JSZip, manifest: ExportManifest | null): number {
  return driveCsvFiles(zip, manifest).length
}

async function importBatchZipInner(zip: JSZip, manifest: ExportManifest | null, filename: string): Promise<Session[]> {
  const csvFiles = driveCsvFiles(zip, manifest)

  const sessions: Session[] = []
  for (const csvFile of csvFiles) {
    const id = crypto.randomUUID()
    const trip = await parseTripDataFromCsv(zip, csvFile)
    if (!trip) continue

    const profileFile = findProfileFor(zip, manifest, csvFile, csvFiles.length)
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

// Drive identity in export filenames: id (batch) + start time. Single export: drive_<ts>.csv +
// drive_profile_<ts>.json; batch: drive_<id>_<ts>.csv + drive_<id>_<ts>_profile.json.
const SINGLE_CSV = /^drive_(\d{8}_\d{6})\.csv$/
const BATCH_CSV = /^drive_(\d+)_(\d{8}_\d{6})\.csv$/
const SINGLE_PROFILE = /^drive_profile_(\d{8}_\d{6})\.json$/
const BATCH_PROFILE = /^drive_(\d+)_(\d{8}_\d{6})_profile\.json$/

const baseName = (path: string) => path.slice(path.lastIndexOf('/') + 1)
const dirName = (path: string) => path.slice(0, path.lastIndexOf('/') + 1)

/** Drive identity ("<id>_<ts>" or "_<ts>") from a drive CSV / profile filename, or null. */
function driveIdentity(path: string): string | null {
  const name = baseName(path)
  let m = name.match(BATCH_CSV) ?? name.match(BATCH_PROFILE)
  if (m) return `${m[1]}_${m[2]}`
  m = name.match(SINGLE_CSV) ?? name.match(SINGLE_PROFILE)
  return m ? `_${m[1]}` : null
}

function isProfileName(path: string, manifest: ExportManifest | null): boolean {
  const name = baseName(path)
  return SINGLE_PROFILE.test(name) || BATCH_PROFILE.test(name) ||
    (name.endsWith('.json') && !!manifest?.files.some((e) => e.name === path && e.type === 'drive_profile'))
}

/**
 * The profile JSON belonging to one drive CSV — the same rule for single and batch imports.
 * 1. By name in the same folder: drive_<ts>.csv → drive_profile_<ts>.json, drive_<id>_<ts>.csv →
 *    drive_<id>_<ts>_profile.json. The name carries the drive's id + start time, so the manifest
 *    type isn't needed (batch manifests up to rc.6 label these files "unknown").
 * 2. Only when the ZIP has one drive CSV and exactly one profile, and their names don't identify
 *    different drives: that pair (older exports with other CSV names).
 * Otherwise null — the drive keeps its CSV-only summary. Never chosen by ZIP order.
 */
function findProfileFor(zip: JSZip, manifest: ExportManifest | null, csvFile: string, driveCsvCount: number): string | null {
  const name = baseName(csvFile)
  const dir = dirName(csvFile)
  const batch = name.match(BATCH_CSV)
  const single = name.match(SINGLE_CSV)
  const expected = batch ? `${dir}drive_${batch[1]}_${batch[2]}_profile.json`
    : single ? `${dir}drive_profile_${single[1]}.json` : null
  if (expected && zip.files[expected] && !zip.files[expected].dir) return expected

  if (driveCsvCount !== 1) return null
  const candidates = Object.keys(zip.files).filter((f) => !zip.files[f].dir && isProfileName(f, manifest))
  if (candidates.length !== 1) return null
  const csvId = driveIdentity(csvFile)
  const profileId = driveIdentity(candidates[0])
  return csvId && profileId && csvId !== profileId ? null : candidates[0]
}

async function importSingleDrive(zip: JSZip, manifest: ExportManifest | null, filename: string): Promise<Session> {
  const id = crypto.randomUUID()
  const trip = await parseTripData(zip, manifest)

  // Parse profile JSON and merge into trip data (same association rule as batch imports)
  const csvFiles = driveCsvFiles(zip, manifest)
  if (trip && csvFiles.length > 0) {
    const profileFile = findProfileFor(zip, manifest, csvFiles[0], csvFiles.length)
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

// Required column synonyms — at least one variant from each group must be present in the
// drive CSV header (names as parseTripRow reads them). Only drive CSVs reach this check:
// companion CSVs (RaceChrono / TrackAddict / knock) are filtered out by isDriveCsvName.
const REQUIRED_COLUMN_GROUPS: { name: string; aliases: string[] }[] = [
  { name: 'timestamp', aliases: ['timestamp_ms', 'timestamp', 'ts'] },
  { name: 'rpm', aliases: ['rpm'] },
]

async function parseTripData(zip: JSZip, manifest: ExportManifest | null): Promise<TripData | null> {
  const csvFile = driveCsvFiles(zip, manifest)[0]
  if (!csvFile) return null
  return parseTripDataFromCsv(zip, csvFile)
}

async function parseTripDataFromCsv(zip: JSZip, csvFile: string): Promise<TripData | null> {
  const file = zip.files[csvFile]
  if (!file) return null

  // Strip a UTF-8 BOM first (as on main) — it must be gone before the `#` format-line check
  // below. trim() also removes U+FEFF; the explicit strip keeps that if the parsing changes.
  const csv = (await file.async('string')).replace(/^\uFEFF/, '')
  // openRS_ v2.2.7-rc.4+ starts its CSVs with a `# openrs_csv_v2` format line — not a header row
  // Keep each line's physical number (1-based, in the file as exported) for row errors — trim() drops leading blank
  // lines, so count them back in
  const leadingLines = csv.slice(0, csv.length - csv.trimStart().length).split('\n').length - 1
  const lines = csv.trim().split('\n')
    .map((text, i) => ({ text: text.replace(/\r$/, ''), lineNo: leadingLines + i + 1 }))
    .filter((l) => !l.text.startsWith('#') && l.text.trim() !== '')
  if (lines.length < 2) return null

  const headers = lines[0].text.split(',').map((h) => h.trim())

  // Validate headers — surface a clear error instead of silently producing
  // structurally-valid TripPoints filled with default values.
  const headerSet = new Set(headers)
  const missing = REQUIRED_COLUMN_GROUPS
    .filter((g) => !g.aliases.some((a) => headerSet.has(a)))
    .map((g) => `${g.name} (one of: ${g.aliases.join(', ')})`)
  if (missing.length > 0) {
    throw new ImportError(`CSV ${csvFile} is missing required columns: ${missing.join('; ')}`)
  }

  // Every data row must line up with the header. A row with extra fields is what openRS_ before v2.2.7-rc.7 wrote on
  // decimal-comma phones ("100,25" splits into two fields) — importing it would shift every later column into
  // plausible-looking but wrong telemetry, and the decimals can't be reconstructed reliably, so the file is rejected.
  const tsKey = ['timestamp_ms', 'timestamp', 'ts'].find((k) => headerSet.has(k))!
  const points: TripPoint[] = []
  for (let i = 1; i < lines.length; i++) {
    const { text, lineNo } = lines[i]
    const vals = text.split(',')
    if (vals.length !== headers.length) {
      throw new ImportError(
        `CSV ${csvFile} row ${lineNo}: expected ${headers.length} fields, found ${vals.length}. ` +
        'The file is damaged — exports from openRS_ before v2.2.7-rc.7 on phones that use a decimal comma are ' +
        'affected. Export the drive again with v2.2.7-rc.7 or later.',
      )
    }
    const row = Object.fromEntries(headers.map((h, j) => [h, vals[j]?.trim() ?? '']))
    for (const key of [tsKey, 'rpm']) {
      if (!/^-?\d+(\.\d+)?$/.test(row[key])) {
        throw new ImportError(`CSV ${csvFile} row ${lineNo}: ${key} "${row[key]}" is not a number.`)
      }
      // The token check passes any digit run; one too long for a double parses to Infinity
      if (!Number.isFinite(parseFloat(row[key]))) {
        const shown = row[key].length > 24 ? `${row[key].slice(0, 24)}…` : row[key]
        throw new ImportError(`CSV ${csvFile} row ${lineNo}: ${key} "${shown}" is not a finite number.`)
      }
    }
    points.push(parseTripRow(row))
  }

  const summary = computeSummary(points)
  return { points, summary }
}

/**
 * Boost cell + provenance (#207). Android writes an empty boost_psi for a point without a reading (boost_source NONE)
 * — that is no reading, never 0; a genuine 0.000 stays 0. boost_source CAN_0F8 / TIP_033E names the measurement; an
 * empty boost_source (or no such column — older exports) means the source wasn't recorded: the value is kept as
 * recorded and never given a source.
 */
function parseBoost(row: Record<string, string>): { boostPsi: number | null; boostSource: BoostSource | null } {
  const rawSource = (row['boost_source'] ?? row['boostSource'] ?? '').trim()
  const rawPsi = (row['boost_psi'] ?? row['boostPsi'] ?? '').trim()
  const psi = rawPsi === '' ? NaN : parseFloat(rawPsi)
  if (rawSource === 'NONE' || !Number.isFinite(psi)) return { boostPsi: null, boostSource: null }
  return { boostPsi: psi, boostSource: parseBoostSource(rawSource) }
}

function parseTripRow(row: Record<string, string>): TripPoint {
  return {
    ts: parseFloat(row['timestamp_ms'] || row['timestamp'] || row['ts'] || '0'),
    lat: parseFloat(row['lat'] || '0'),
    lng: parseFloat(row['lng'] || row['lon'] || '0'),
    rpm: parseFloat(row['rpm'] || '0'),
    ...parseBoost(row),
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
    // #207: a drive recorded under a boost rule states its peak and source (none = no reading); an older profile's
    // positive peak is kept as recorded with no source
    if (d.boostPolicy) {
      s.boostPolicy = d.boostPolicy
      s.peakBoostPsi = d.peakBoostPsi
      s.peakBoostSource = d.peakBoostPsi == null ? null : (d.peakBoostSource ?? null)
    } else if (d.peakBoostPsi != null && d.peakBoostPsi > 0) {
      s.peakBoostPsi = d.peakBoostPsi
      s.peakBoostSource = null
    }
    if (d.peakLateralG > 0) s.peakLatG = d.peakLateralG
    if (d.maxSpeedKph > 0) s.peakSpeedKph = d.maxSpeedKph
    if (d.peakOilTempC > -90) s.peakOilTempC = d.peakOilTempC
    if (d.peakCoolantTempC > -90) s.peakCoolantC = d.peakCoolantTempC
    if (d.fuelUsedL > 0) s.fuelUsedL = d.fuelUsedL
    // #284: the app's distance leaves out what it did not record (a pause, a link loss); the point sum does not.
    // A drive without coordinates keeps no distance, as on the app's TRIP (it shows DIST only with GPS)
    const recorded = recordedDistanceKm((data.drive as Record<string, unknown> | undefined)?.distanceKm)
    if (recorded != null && trip.points.some((p) => p.lat !== 0 || p.lng !== 0)) {
      s.distanceKm = recorded
      s.distanceSource = 'recorded'
      s.avgFuelEconomy = computeAvgFuelEconomy(s.fuelUsedL, recorded)
    }
    if (d.aggressionScore > 0) s.aggressionScore = d.aggressionScore

    // Override peak events with profile peaks (GPS-tagged, canonical)
    if (profile.peaks.length > 0) {
      s.peakEvents = profile.peaks.map((p) => ({
        type: p.type === 'RPM' ? 'rpm' : p.type === 'BOOST' ? 'boost' : p.type === 'LATERAL_G' ? 'latG' : 'speed',
        value: p.value,
        ts: p.timestampMs,
        lat: p.lat,
        lng: p.lng,
        ...(p.type === 'BOOST' ? { source: p.source ?? null } : {}),
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
    peakBoostPsi: d.peakBoostPsi != null && Number.isFinite(Number(d.peakBoostPsi)) ? Number(d.peakBoostPsi) : null,
    peakBoostSource: parseBoostSource(d.peakBoostSource),
    boostPolicy: typeof d.boostPolicy === 'string' ? d.boostPolicy : undefined,
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
    source: parseBoostSource(p.source),
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
    // older exports only; current ones omit it (no source) — absent stays absent, never 0
    ...(b.boostPsi != null && Number.isFinite(Number(b.boostPsi)) ? { boostPsi: Number(b.boostPsi) } : {}),
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
  // #207: one boost peak per measurement (TIP, CAN 0x0F8, source not recorded) — never one max across them; points
  // without a reading take no part
  const boostGroups = new Map<string, TripPoint[]>()
  for (const p of points) {
    if (p.boostPsi == null) continue
    const k = p.boostSource ?? ''
    boostGroups.set(k, [...(boostGroups.get(k) ?? []), p])
  }
  let peakBoost: { psi: number; source: BoostSource | null } | null = null
  for (const [k, pts] of boostGroups) {
    const best = pts.reduce((a, b) => ((b.boostPsi ?? -Infinity) > (a.boostPsi ?? -Infinity) ? b : a))
    const source = parseBoostSource(k)
    peakEvents.push({ type: 'boost', value: best.boostPsi!, ts: best.ts, lat: best.lat, lng: best.lng, source })
    // The drive peak: the highest sourced reading with its source (as Android); unsourced values only without one
    if (!peakBoost || (source && (!peakBoost.source || best.boostPsi! > peakBoost.psi)) ||
        (!source && !peakBoost.source && best.boostPsi! > peakBoost.psi)) peakBoost = { psi: best.boostPsi!, source }
  }
  addPeak('speed', speedArr, points)
  addPeak('latG', latGArr.map((_, i) => Math.abs(points[i].latG)), points)

  return {
    distanceKm,
    distanceSource: 'estimate',
    durationMs,
    fuelUsedL: computeFuelUsedL(points),
    avgSpeedKph: avg(speedArr),
    peakRpm: Math.max(...rpmArr),
    peakBoostPsi: peakBoost?.psi ?? null,
    peakBoostSource: peakBoost?.source ?? null,
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
  const dtcFile = manifest?.files.find((e) => e.type === 'dtc_text')?.name
    ?? Object.keys(zip.files).find((f) => f.startsWith('dtc_scan_') && f.endsWith('.txt'))
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
  const jsonFile = manifest?.files.find((e) => e.type === 'dtc_json')?.name
    ?? Object.keys(zip.files).find((f) => f.startsWith('dtc_scan_') && f.endsWith('.json'))
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

function diagnosticDetailFile(zip: JSZip, manifest: ExportManifest | null): string | undefined {
  return manifest?.files.find((e) => e.type === 'diagnostic_detail')?.name
    ?? Object.keys(zip.files).find((f) => f.startsWith('diagnostic_detail_') && f.endsWith('.json'))
}

async function parseDiagnosticData(zip: JSZip, dtcResults: DtcEntry[], manifest: ExportManifest | null): Promise<DiagnosticData | null> {
  const jsonFile = diagnosticDetailFile(zip, manifest)
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

/** The export's contract version: the manifest's, else the detail JSON's. Undefined = none stated; never blocks an import. */
async function readExportSchemaVersion(zip: JSZip, manifest: ExportManifest | null): Promise<number | undefined> {
  const fromManifest = parseExportSchemaVersion((await readManifestObject(zip))?.exportSchemaVersion)
  if (fromManifest !== undefined) return fromManifest
  const jsonFile = diagnosticDetailFile(zip, manifest)
  if (!jsonFile || !zip.files[jsonFile]) return undefined
  try {
    return parseExportSchemaVersion(JSON.parse(await zip.files[jsonFile].async('string'))?.exportSchemaVersion)
  } catch {
    return undefined
  }
}

async function parseMeta(zip: JSZip, filename: string, manifest: ExportManifest | null): Promise<SessionMeta> {
  const meta = await parseExportMeta(zip, filename, manifest)
  const exportSchemaVersion = await readExportSchemaVersion(zip, manifest)
  // No key at all without a version: a legacy export's session stays as it was
  return exportSchemaVersion === undefined ? meta : { ...meta, exportSchemaVersion }
}

async function parseExportMeta(zip: JSZip, filename: string, manifest: ExportManifest | null): Promise<SessionMeta> {
  if (manifest) {
    // Only a date that exists: new Date(x).toISOString() throws on anything else and would stop the import
    const exportedAt = typeof manifest.exportedAt === 'number' && !Number.isNaN(new Date(manifest.exportedAt).getTime())
      ? manifest.exportedAt : undefined
    return {
      appVersion: typeof manifest.appVersion === 'string' && manifest.appVersion ? manifest.appVersion : 'unknown',
      firmwareVersion: 'unknown',
      sessionStart: '',
      generatedAt: new Date(exportedAt ?? Date.now()).toISOString(),
      appBuild: typeof manifest.appBuild === 'number' ? manifest.appBuild : undefined,
      exportedAt,
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
