/** A stored driving session with trip + diagnostic data. */
export interface Session {
  id: string
  name: string
  importedAt: number
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
}

// ── Trip ──

export interface TripData {
  points: TripPoint[]
  summary: TripSummary
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
}

export interface PeakEvent {
  type: string
  value: number
  ts: number
  lat: number
  lng: number
}

// ── Diagnostics ──

export interface DiagnosticData {
  canInventory: CanFrame[]
  fpsTimeline: FpsSample[]
  sessionEvents: SessionEvent[]
  decodeTrace: DecodeEntry[]
  probeResults: ProbeResult[]
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
