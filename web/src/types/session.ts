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
}

export interface SessionMeta {
  appVersion: string
  firmwareVersion: string
  sessionStart: string
  generatedAt: string
  appBuild?: number
  exportedAt?: number
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
  boostPsi: number
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
  durationMs: number
  fuelUsedL: number
  avgSpeedKph: number
  peakRpm: number
  peakBoostPsi: number
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
  peakBoostPsi: number
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
  boostPsi: number
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
