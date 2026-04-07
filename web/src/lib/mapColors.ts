import type { TripPoint } from '../types/session'
import { colors } from '../styles/tokens'

/**
 * Color modes for the GPS map polyline — mirrors Android DriveMap.kt ColorMode.
 */
export type ColorMode = 'speed' | 'mode' | 'boost' | 'throttle' | 'latG' | 'oilTemp'

export const colorModes: { id: ColorMode; label: string }[] = [
  { id: 'speed', label: 'SPD' },
  { id: 'mode', label: 'MODE' },
  { id: 'boost', label: 'BOOST' },
  { id: 'throttle', label: 'THRTL' },
  { id: 'latG', label: 'G-LAT' },
  { id: 'oilTemp', label: 'TEMP' },
]

/** Returns a CSS color string for a trip point based on the active color mode. */
export function pointColor(point: TripPoint, mode: ColorMode): string {
  switch (mode) {
    case 'speed':
      if (point.speedKph < 60) return colors.ok
      if (point.speedKph < 100) return colors.accent
      if (point.speedKph < 140) return colors.warn
      return colors.orange

    case 'mode': {
      const m = point.driveMode.toLowerCase()
      if (m === 'sport') return colors.warn
      if (m === 'track') return colors.ok
      if (m === 'drift') return colors.orange
      return colors.accent // Normal
    }

    case 'boost':
      if (point.boostPsi < 0) return colors.accent
      if (point.boostPsi < 8) return colors.ok
      if (point.boostPsi < 16) return colors.warn
      return colors.orange

    case 'throttle':
      if (point.throttlePct < 25) return colors.accent
      if (point.throttlePct < 50) return colors.ok
      if (point.throttlePct < 75) return colors.warn
      return colors.orange

    case 'latG':
      if (Math.abs(point.latG) < 0.3) return colors.accent
      if (Math.abs(point.latG) < 0.6) return colors.ok
      if (Math.abs(point.latG) < 0.9) return colors.warn
      return colors.orange

    case 'oilTemp':
      if (point.oilTempC < 0) return colors.accent
      if (point.oilTempC < 90) return colors.ok
      if (point.oilTempC < 110) return colors.warn
      return colors.orange
  }
}

/** Legend entries for the given color mode (label + color pairs). */
export function colorLegend(mode: ColorMode): { label: string; color: string }[] {
  switch (mode) {
    case 'speed':
      return [
        { label: '<60', color: colors.ok },
        { label: '60-100', color: colors.accent },
        { label: '100-140', color: colors.warn },
        { label: '140+', color: colors.orange },
      ]
    case 'mode':
      return [
        { label: 'Normal', color: colors.accent },
        { label: 'Sport', color: colors.warn },
        { label: 'Track', color: colors.ok },
        { label: 'Drift', color: colors.orange },
      ]
    case 'boost':
      return [
        { label: 'Vac', color: colors.accent },
        { label: '<8', color: colors.ok },
        { label: '8-16', color: colors.warn },
        { label: '16+', color: colors.orange },
      ]
    case 'throttle':
      return [
        { label: '<25%', color: colors.accent },
        { label: '25-50', color: colors.ok },
        { label: '50-75', color: colors.warn },
        { label: '75+', color: colors.orange },
      ]
    case 'latG':
      return [
        { label: '<0.3g', color: colors.accent },
        { label: '0.3-0.6', color: colors.ok },
        { label: '0.6-0.9', color: colors.warn },
        { label: '0.9+', color: colors.orange },
      ]
    case 'oilTemp':
      return [
        { label: '<90°', color: colors.ok },
        { label: '90-110', color: colors.warn },
        { label: '110+', color: colors.orange },
      ]
  }
}

/** Peak marker colors and labels matching Android DriveMap. */
export function peakMarkerStyle(type: string): { color: string; prefix: string; unit: string; decimals: number } {
  switch (type) {
    case 'rpm':   return { color: colors.warn, prefix: 'RPM', unit: '', decimals: 0 }
    case 'boost': return { color: colors.accent, prefix: 'Boost', unit: ' PSI', decimals: 1 }
    case 'latG':  return { color: colors.orange, prefix: 'Lat-G', unit: '', decimals: 2 }
    case 'speed': return { color: colors.frost, prefix: 'Speed', unit: ' kph', decimals: 0 }
    default:      return { color: colors.frost, prefix: type, unit: '', decimals: 1 }
  }
}
