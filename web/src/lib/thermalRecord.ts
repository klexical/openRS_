// The drive's thermal record (#315), read from the Android profile export's `thermalRecord` block
// (DriveExportBuilder.buildThermalRecordJson): which basis it has, the limits saved per profile segment, the per-sensor
// peaks and the reading behind each TEMP flag. All temperatures arrive in °C; the page converts them for display.
// A value the block does not hold, or holds as anything but a finite number, is left out: never a 0 or a made-up value.
import type { ThermalCurve, ThermalProgression } from '../types/session'

/** The sensors the app records, in the order it shows them; the block's keys are their names in lower case. */
export const THERMAL_SENSORS = ['oil', 'coolant', 'ptu', 'rdu'] as const
export type ThermalSensor = (typeof THERMAL_SENSORS)[number]

export type ThermalLevel = 'NORMAL' | 'WARN' | 'CRIT'

export interface ThermalLimits {
  warnC: number
  critC: number
  /** 95 % of critC, as the app computed it (°C); the TEMP flag rule. */
  flagThresholdC: number
}

export interface ThermalSegment {
  /** The app's profile id ("street", "track", "race", "custom"). */
  profile: string
  /** Time into the drive when this profile took effect. */
  startOffsetMs: number
  limits: Partial<Record<ThermalSensor, ThermalLimits>>
  peaksC: Partial<Record<ThermalSensor, number>>
}

export interface ThermalFlag {
  readingC: number
  profile: string | null
  flagThresholdC: number
}

export interface ThermalSensorPeak {
  peakC: number
  peakProfile: string | null
  /** The peak's level under its own segment's limits; null when the record states none. */
  peakLevel: ThermalLevel | null
  /** The reading that raised the TEMP flag (can be below the peak when a stricter profile applied later). */
  flag: ThermalFlag | null
}

export interface LegacySensor {
  /** A drive-end reading, not a peak. */
  driveEndC: number
  fixedLimitC: number | null
  flagThresholdC: number | null
  flagged: boolean
}

export type ThermalRecord =
  | { basis: 'profile'; flagged: boolean; segments: ThermalSegment[]; sensors: Partial<Record<ThermalSensor, ThermalSensorPeak>> }
  | { basis: 'legacy'; flagged: boolean; sensors: Partial<Record<ThermalSensor, LegacySensor>> }
  /** absent = the export has no record (or an empty block); unreadable = it has one that cannot be read. */
  | { basis: 'none'; reason: 'absent' | 'unreadable' }

const ABSENT: ThermalRecord = { basis: 'none', reason: 'absent' }
const UNREADABLE: ThermalRecord = { basis: 'none', reason: 'unreadable' }

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)
const str = (v: unknown): string | null => (typeof v === 'string' && v.length > 0 ? v : null)
const level = (v: unknown): ThermalLevel | null => (v === 'NORMAL' || v === 'WARN' || v === 'CRIT' ? v : null)

/** Each known sensor of [obj] read by [read]; a sensor whose entry does not read is left out. */
function perSensor<T>(obj: unknown, read: (v: unknown) => T | null): Partial<Record<ThermalSensor, T>> {
  const out: Partial<Record<ThermalSensor, T>> = {}
  if (!isObject(obj)) return out
  for (const s of THERMAL_SENSORS) {
    const v = read(obj[s])
    if (v != null) out[s] = v
  }
  return out
}

function readLimits(v: unknown): ThermalLimits | null {
  if (!isObject(v)) return null
  const warnC = num(v.warnC), critC = num(v.critC), flagThresholdC = num(v.flagThresholdC)
  return warnC != null && critC != null && flagThresholdC != null ? { warnC, critC, flagThresholdC } : null
}

function readSegment(v: unknown): ThermalSegment | null {
  if (!isObject(v)) return null
  const profile = str(v.profile)
  const startOffsetMs = num(v.startOffsetMs)
  if (profile == null || startOffsetMs == null || startOffsetMs < 0) return null
  return { profile, startOffsetMs, limits: perSensor(v.limits, readLimits), peaksC: perSensor(v.peaksC, num) }
}

function readPeak(v: unknown): ThermalSensorPeak | null {
  if (!isObject(v)) return null
  const peakC = num(v.peakC)
  if (peakC == null) return null
  const f = isObject(v.flag) ? v.flag : null
  const readingC = num(f?.readingC), flagThresholdC = num(f?.flagThresholdC)
  return {
    peakC,
    peakProfile: str(v.peakProfile),
    peakLevel: level(v.peakLevel),
    flag: v.flagged === true && readingC != null && flagThresholdC != null
      ? { readingC, flagThresholdC, profile: str(f?.profile) } : null,
  }
}

function readLegacy(v: unknown): LegacySensor | null {
  if (!isObject(v)) return null
  const driveEndC = num(v.driveEndC)
  if (driveEndC == null) return null
  return { driveEndC, fixedLimitC: num(v.fixedLimitC), flagThresholdC: num(v.flagThresholdC), flagged: v.flagged === true }
}

/** The export's `thermalRecord` block read as a record; missing or empty = absent, anything that does not read = unreadable. */
export function parseThermalRecord(raw: unknown): ThermalRecord {
  if (raw === undefined || raw === null) return ABSENT
  if (!isObject(raw)) return UNREADABLE
  if (Object.keys(raw).length === 0) return ABSENT
  const flagged = raw.flagged === true
  switch (raw.basis) {
    case 'profile': {
      if (!Array.isArray(raw.segments)) return UNREADABLE
      const segments = raw.segments.map(readSegment)
      if (segments.length === 0 || segments.some((s) => s == null)) return UNREADABLE
      return { basis: 'profile', flagged, segments: segments as ThermalSegment[], sensors: perSensor(raw.sensors, readPeak) }
    }
    case 'legacy': {
      const sensors = perSensor(raw.sensors, readLegacy)
      return Object.keys(sensors).length === 0 ? UNREADABLE : { basis: 'legacy', flagged, sensors }
    }
    case 'none':
      return raw.reason === 'absent' ? ABSENT : UNREADABLE
    default:
      return UNREADABLE
  }
}

/** The app's name for a profile id; an id it does not know is shown as given. */
export function profileName(id: string): string {
  return id.toUpperCase()
}

/** Sensor names as the thermal progression chart shows them. */
export const SENSOR_LABEL: Record<ThermalSensor, string> = { oil: 'Oil', coolant: 'Coolant', ptu: 'PTU', rdu: 'RDU' }

/** The segments' profile names, a repeat of the one before marked "(edited)" (a saved Custom edit starts a segment). */
export function segmentNames(segments: ThermalSegment[]): string[] {
  return segments.map((s, i) => profileName(s.profile) + (i > 0 && segments[i - 1].profile === s.profile ? ' (edited)' : ''))
}

export interface ThermalRow {
  sensor: ThermalSensor
  /** Null = unknown ("—"): the export has no such reading, or it is above the peak. */
  startC: number | null
  peakC: number
  endC: number | null
  level: ThermalLevel | null
}

/**
 * One row per sensor with a recorded peak: start and end from the export's thermal progression, each unknown when it is
 * missing or above the record's peak (as the app: never adjust the peak to fit).
 */
export function thermalRows(record: ThermalRecord, progression: ThermalProgression | undefined): ThermalRow[] {
  if (record.basis !== 'profile') return []
  const endpoint = (v: number | undefined, peak: number) => (v != null && Number.isFinite(v) && v > -90 && v <= peak ? v : null)
  const rows: ThermalRow[] = []
  for (const sensor of THERMAL_SENSORS) {
    const p = record.sensors[sensor]
    if (!p) continue
    const curve = progression?.[sensor] as ThermalCurve | undefined
    rows.push({ sensor, peakC: p.peakC, level: p.peakLevel, startC: endpoint(curve?.startC, p.peakC), endC: endpoint(curve?.endC, p.peakC) })
  }
  return rows
}
