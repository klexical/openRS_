import JSZip from 'jszip'
import type {
  Session, SessionMeta, TripData, TripPoint, TripSummary,
  DiagnosticData, CanFrame, FpsSample, SessionEvent, DecodeEntry, ProbeResult,
} from '../types/session'

/** Import a ZIP file exported by the openRS_ Android app. */
export async function importZip(file: File): Promise<Session> {
  const zip = await JSZip.loadAsync(file)
  const id = crypto.randomUUID()

  const trip = await parseTripData(zip)
  const diagnostics = await parseDiagnosticData(zip)
  const meta = await parseMeta(zip, file.name)

  const name = buildSessionName(meta, trip, file.name)

  return {
    id,
    name,
    importedAt: Date.now(),
    trip,
    diagnostics,
    meta,
  }
}

// ── Trip CSV parsing ──

async function parseTripData(zip: JSZip): Promise<TripData | null> {
  const csvFile = Object.keys(zip.files).find((f) => f.startsWith('trip_') && f.endsWith('.csv'))
  if (!csvFile) return null

  const csv = await zip.files[csvFile].async('string')
  const lines = csv.trim().split('\n')
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
    ts: parseFloat(row['timestamp'] || row['ts'] || '0'),
    lat: parseFloat(row['lat'] || '0'),
    lng: parseFloat(row['lng'] || row['lon'] || '0'),
    rpm: parseFloat(row['rpm'] || '0'),
    boostPsi: parseFloat(row['boostPsi'] || row['boost_psi'] || '0'),
    speedKph: parseFloat(row['speedKph'] || row['speed_kph'] || '0'),
    coolantC: parseFloat(row['coolantC'] || row['coolant_c'] || '-99'),
    oilTempC: parseFloat(row['oilTempC'] || row['oil_temp_c'] || '-99'),
    rduTempC: parseFloat(row['rduTempC'] || row['rdu_temp_c'] || '-99'),
    ptuTempC: parseFloat(row['ptuTempC'] || row['ptu_temp_c'] || '-99'),
    ambientC: parseFloat(row['ambientC'] || row['ambient_c'] || '-99'),
    latG: parseFloat(row['latG'] || row['lat_g'] || '0'),
    longG: parseFloat(row['longG'] || row['long_g'] || '0'),
    fuelPct: parseFloat(row['fuelPct'] || row['fuel_pct'] || '-1'),
    wheelSpeedFL: parseFloat(row['wheelSpeedFL'] || row['ws_fl'] || '0'),
    wheelSpeedFR: parseFloat(row['wheelSpeedFR'] || row['ws_fr'] || '0'),
    wheelSpeedRL: parseFloat(row['wheelSpeedRL'] || row['ws_rl'] || '0'),
    wheelSpeedRR: parseFloat(row['wheelSpeedRR'] || row['ws_rr'] || '0'),
    driveMode: row['driveMode'] || row['drive_mode'] || 'NORMAL',
    awdTorqueL: parseFloat(row['awdTorqueL'] || row['awd_torque_l'] || '0'),
    awdTorqueR: parseFloat(row['awdTorqueR'] || row['awd_torque_r'] || '0'),
  }
}

function computeSummary(points: TripPoint[]): TripSummary {
  if (points.length === 0) {
    return emptySummary()
  }

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
    fuelUsedL: 0, // TODO: compute from fuel % delta
    avgSpeedKph: avg(speedArr),
    peakRpm: Math.max(...rpmArr),
    peakBoostPsi: Math.max(...boostArr),
    peakLatG: Math.max(...latGArr),
    peakLongG: Math.max(...longGArr),
    peakSpeedKph: Math.max(...speedArr),
    peakCoolantC: coolantArr.length > 0 ? Math.max(...coolantArr) : -99,
    peakOilTempC: oilArr.length > 0 ? Math.max(...oilArr) : -99,
    avgRpm: avg(rpmArr),
    avgFuelEconomy: 0,
    modeBreakdown,
    peakEvents,
  }
}

type PeakEvent = { type: string; value: number; ts: number; lat: number; lng: number }

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

// ── Diagnostic JSON parsing ──

async function parseDiagnosticData(zip: JSZip): Promise<DiagnosticData | null> {
  const jsonFile = Object.keys(zip.files).find(
    (f) => f.startsWith('diagnostic_detail_') && f.endsWith('.json')
  )
  if (!jsonFile) return null

  const raw = await zip.files[jsonFile].async('string')
  const data = JSON.parse(raw)

  return {
    canInventory: parseCanInventory(data),
    fpsTimeline: parseFpsTimeline(data),
    sessionEvents: parseSessionEvents(data),
    decodeTrace: parseDecodeTrace(data),
    probeResults: parseProbeResults(data),
  }
}

function parseCanInventory(data: Record<string, unknown>): CanFrame[] {
  const inv = data.canInventory || data.can_inventory || []
  if (!Array.isArray(inv)) return []
  return inv.map((f: Record<string, unknown>) => ({
    id: String(f.id || f.canId || ''),
    count: Number(f.count || 0),
    changed: Boolean(f.changed),
    firstHex: String(f.firstHex || f.first_hex || ''),
    lastHex: String(f.lastHex || f.last_hex || ''),
    samples: Array.isArray(f.samples) ? f.samples.map(String) : [],
  }))
}

function parseFpsTimeline(data: Record<string, unknown>): FpsSample[] {
  const fps = data.fpsTimeline || data.fps_timeline || []
  if (!Array.isArray(fps)) return []
  return fps.map((s: Record<string, unknown>) => ({
    ts: Number(s.ts || s.timestamp || 0),
    fps: Number(s.fps || 0),
  }))
}

function parseSessionEvents(data: Record<string, unknown>): SessionEvent[] {
  const events = data.sessionEvents || data.session_events || []
  if (!Array.isArray(events)) return []
  return events.map((e: Record<string, unknown>) => ({
    ts: Number(e.ts || e.timestamp || 0),
    type: String(e.type || 'info'),
    message: String(e.message || e.msg || ''),
  }))
}

function parseDecodeTrace(data: Record<string, unknown>): DecodeEntry[] {
  const trace = data.decodeTrace || data.decode_trace || []
  if (!Array.isArray(trace)) return []
  return trace.map((d: Record<string, unknown>) => ({
    ts: Number(d.ts || d.timestamp || 0),
    canId: String(d.canId || d.can_id || ''),
    rawHex: String(d.rawHex || d.raw_hex || ''),
    decoded: String(d.decoded || ''),
    issue: String(d.issue || ''),
  }))
}

function parseProbeResults(data: Record<string, unknown>): ProbeResult[] {
  const probes = data.probeResults || data.probe_results || []
  if (!Array.isArray(probes)) return []
  return probes.map((p: Record<string, unknown>) => ({
    module: String(p.module || ''),
    did: String(p.did || ''),
    status: String(p.status || ''),
    responseHex: String(p.responseHex || p.response_hex || ''),
    description: String(p.description || ''),
  }))
}

// ── Meta ──

async function parseMeta(zip: JSZip, filename: string): Promise<SessionMeta> {
  // Try to find summary text for metadata
  const summaryFile = Object.keys(zip.files).find(
    (f) => f.startsWith('diagnostic_summary_') || f.startsWith('trip_summary_')
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

  // Extract timestamp from filename: openrs_diag_20260326_072526.zip
  if (!sessionStart) {
    const tsMatch = filename.match(/(\d{8}_\d{6})/)
    if (tsMatch) {
      const [d, t] = tsMatch[1].split('_')
      sessionStart = `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)} ${t.slice(0, 2)}:${t.slice(2, 4)}:${t.slice(4, 6)}`
    }
  }

  return {
    appVersion,
    firmwareVersion,
    sessionStart,
    generatedAt: new Date().toISOString(),
  }
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
