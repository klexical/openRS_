import type { BoostSource } from '../lib/boost'
/** A stored driving session with trip + diagnostic data. */
export interface Session {
  id: string
  name: string
  importedAt: number
  /** User-assigned tags for filtering/grouping. */
  tags: string[]
  /** Trip data (may be absent for diagnostic-only exports). */
  trip: TripData | null
  /** Diagnostic data (may be absent for trip-only exports). */
  diagnostics: DiagnosticData | null
  /** Metadata from the export. */
  meta: SessionMeta
  /**
   * Whether the import held a fault-code scan (#384): without one, "no fault codes" cannot be told from "not scanned".
   * Absent = stored before this was recorded.
   */
  dtcScan?: boolean
}

export interface SessionMeta {
  appVersion: string
  firmwareVersion: string
  sessionStart: string
  generatedAt: string
  appBuild?: number
  exportedAt?: number
  /** Export contract version (lib/exportSchema.ts); absent = none stated (rc.7 and earlier). */
  exportSchemaVersion?: number
}

// ── Trip ──

export interface TripData {
  points: TripPoint[]
  summary: TripSummary
  /** Structured profile from drive_profile_*.json (rc.3+). */
  profile?: DriveProfile
  /** Thermal start→peak→end (from profile JSON). */
  thermalProgression?: ThermalProgression
  /** User-placed bookmarks during recording (from profile JSON). */
  bookmarks?: Bookmark[]
  /** Aggression score 0–100 (from profile JSON). */
  aggressionScore?: number
  /** Pre-computed mode timeline segments (from profile JSON). */
  canonicalModeTimeline?: ModeTimelineEntry[]
  /** Lap data (from profile JSON, when available). */
  laps?: Lap[]
  /** Performance runs (from profile JSON, when available). */
  perfRuns?: PerfRun[]
}

export interface TripPoint {
  ts: number         // epoch ms
  lat: number
  lng: number
  rpm: number
  /** Gauge psi, or null when the point has no reading (#207 — never 0 for "none"). */
  boostPsi: number | null
  /** Which measurement boostPsi is (lib/boost.ts); null / absent = source not recorded (older exports). */
  boostSource?: BoostSource | null
  speedKph: number
  coolantC: number
  oilTempC: number
  rduTempC: number
  ptuTempC: number
  ambientC: number
  latG: number
  longG: number
  fuelPct: number
  wheelSpeedFL: number
  wheelSpeedFR: number
  wheelSpeedRL: number
  wheelSpeedRR: number
  driveMode: string
  awdTorqueL: number
  awdTorqueR: number
  gear: string
  throttlePct: number
  tirePressLF: number
  tirePressRF: number
  tirePressLR: number
  tirePressRR: number
  tireTempLF: number
  tireTempRF: number
  tireTempLR: number
  tireTempRR: number
  brakePressure: number
  steeringAngle: number
  raceReady: boolean
}

export interface TripSummary {
  distanceKm: number
  /**
   * Where distanceKm comes from (#284): 'recorded' = the app's own figure from the export's profile (nothing counted
   * across a pause or a link loss); 'estimate' = the sum of straight lines between GPS points, gaps included.
   * Absent = imported before the source was kept: a point sum (lib/distance.ts treats it as an estimate).
   */
  distanceSource?: 'recorded' | 'estimate'
  durationMs: number
  fuelUsedL: number
  avgSpeedKph: number
  peakRpm: number
  /** Null when the drive has no boost reading. */
  peakBoostPsi: number | null
  /** Source of the peak; null / absent = not recorded (older exports). */
  peakBoostSource?: BoostSource | null
  /** Android boost rule the drive was recorded under (absent = older drive, no provenance). */
  boostPolicy?: string
  peakLatG: number
  peakLongG: number
  peakSpeedKph: number
  peakCoolantC: number
  peakOilTempC: number
  avgRpm: number
  avgFuelEconomy: number
  modeBreakdown: Record<string, number>  // mode → seconds
  peakEvents: PeakEvent[]
  aggressionScore?: number
}

export interface PeakEvent {
  type: string
  value: number
  ts: number
  lat: number
  lng: number
  /** Boost peaks: the measurement (one peak per source). */
  source?: BoostSource | null
}

// ── Profile JSON (drive_profile_*.json) ──

export interface DriveProfile {
  version: number
  drive: DriveProfileSummary
  peaks: ProfilePeak[]
  thermalProgression: ThermalProgression
  modeTimeline: ModeTimelineEntry[]
  bookmarks: Bookmark[]
}

export interface DriveProfileSummary {
  startTime: number
  endTime: number
  distanceKm: number
  avgSpeedKph: number
  maxSpeedKph: number
  peakRpm: number
  /** Null when absent: a drive recorded under a boost rule with no reading has no peak. */
  peakBoostPsi: number | null
  peakBoostSource?: BoostSource | null
  boostPolicy?: string
  peakLateralG: number
  peakOilTempC: number
  peakCoolantTempC: number
  fuelUsedL: number
  aggressionScore: number
  tags: string
  /** Fuel economy averages (when Android export includes them). */
  avgFuelL100km?: number
  avgFuelMpg?: number
}

export interface ProfilePeak {
  type: 'RPM' | 'BOOST' | 'LATERAL_G' | 'SPEED'
  value: number
  lat: number
  lng: number
  timestampMs: number
  source?: BoostSource | null
}

export interface ThermalProgression {
  oil: ThermalCurve
  coolant: ThermalCurve
  rdu?: ThermalCurve
  ptu?: ThermalCurve
}

export interface ThermalCurve {
  startC: number
  peakC: number
  endC: number
}

export interface ModeTimelineEntry {
  mode: string
  startMs: number
  endMs: number
}

export interface Bookmark {
  timestamp: number
  lat: number
  lng: number
  label: string
  speedKph: number
  rpm: number
  /** Older exports only — current ones omit it (a bookmark stores no boost source). */
  boostPsi?: number
  /** ms since the drive's start. Absent in exports made before observer notes. */
  relMs?: number
  /** "observer_note": what a person in the car marked or typed, not a measurement. Absent in older exports. */
  kind?: string
}

// ── Lap & Performance (stubs for T5 Android gap-fill) ──

export interface Lap {
  lapNumber: number
  lapTimeMs: number
  peakRpm?: number
  peakBoostPsi?: number
  peakLateralG?: number
  peakSpeedKph?: number
}

export interface PerfRun {
  type: string        // "0-60" | "0-100"
  timeMs: number
  launchRpm?: number
  peakBoostPsi?: number
  densityAltFt?: number
  ambientTempC?: number
}

// ── Diagnostics ──

export interface DiagnosticData {
  canInventory: CanFrame[]
  fpsTimeline: FpsSample[]
  sessionEvents: SessionEvent[]
  decodeTrace: DecodeEntry[]
  probeResults: ProbeResult[]
  dtcResults: DtcEntry[]
  nrcSuppression?: NrcSuppression[]
  /** rc.8 exports (#253). Undefined = the export recorded none; never the same as an empty inventory. */
  didInventory?: DidInventory
}

/** Mode 22 requests of one session, per module and DID, as the app counted them (#253). */
export interface DidInventory {
  rows: DidInventoryRow[]
  /** Counts for DIDs past the app's row limit: not listed. */
  overflow: number
  /** Rows of the export this Sapphire could not read: left out, not shown as zeros. */
  unreadable: number
}

export interface DidInventoryRow {
  module: string          // "PCM" | "BCM" | ...
  responseId: string      // CAN response ID, 3 hex digits ("7E8")
  did: string             // 4 hex digits ("033E")
  sent: number
  positive: number
  nrcAttributed: number
  /** Time of the last positive reply since the session start. Absent = no positive reply (not 0). */
  lastPositiveRelMs?: number
}

export interface DtcEntry {
  module: string      // "PCM" | "BCM" | "ABS" | "AWD" | "PSCM"
  code: string        // e.g. "P0101"
  status: string      // "STORED" | "PENDING" | "PERMANENT" | "ACTIVE"
  description: string
  severity?: string   // "CRITICAL" | "WARNING" | "INFO" | "UNKNOWN"
  freezeFrame?: FreezeFrame
}

export interface FreezeFrame {
  recordNumber: number
  entries: FreezeFrameEntry[]
}

export interface FreezeFrameEntry {
  did: string
  label: string
  value: string
}

export interface NrcSuppression {
  did: string
  nrcCode: number
  nrcName: string
  firstSeenMs: number
  suppressedAfterCount: number
}

export interface CanFrame {
  id: string          // hex, e.g. "0x090"
  count: number
  changed: boolean
  firstHex: string
  lastHex: string
  samples: string[]
}

export interface FpsSample {
  ts: number
  fps: number
}

export interface SessionEvent {
  ts: number
  type: string        // "error" | "session" | "slcan" | "firmware" | "dm_cmd"
  message: string
}

export interface DecodeEntry {
  ts: number
  canId: string
  rawHex: string
  decoded: string
  issue: string
}

export interface ProbeResult {
  module: string
  did: string
  status: string      // "ok" | "negative" | "timeout"
  responseHex: string
  description: string
}
