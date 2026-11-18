import type { TripSummary } from '../types/session'

/**
 * The app's own distance from an export's profile (`drive.distanceKm`), or null when the profile states none.
 * The app counts only the segments it recorded: nothing across a pause or a link loss (#284). Zero is a distance.
 */
export function recordedDistanceKm(raw: unknown): number | null {
  return typeof raw === 'number' && Number.isFinite(raw) && raw >= 0 ? raw : null
}

/**
 * True when the summary's distance is the sum of straight lines between GPS points: an export without a recorded
 * distance, or a session imported before the source was kept (no flag). Such a sum includes the line across any
 * pause or link loss, so the views say so.
 */
export function isDistanceEstimate(summary: Pick<TripSummary, 'distanceKm' | 'distanceSource'>): boolean {
  return summary.distanceSource !== 'recorded' && summary.distanceKm > 0
}

export const DISTANCE_ESTIMATE_NOTE = 'Estimate from GPS points'

/** " est." after a distance in a one-line row, or nothing for a recorded distance. */
export function estimateSuffix(summary: Pick<TripSummary, 'distanceKm' | 'distanceSource'>): string {
  return isDistanceEstimate(summary) ? ' est.' : ''
}

/** The great-circle distance in km between two positions (moved here from lib/import.ts for the range summary, #416). */
export function haversine(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371
  const dLat = (lat2 - lat1) * Math.PI / 180
  const dLon = (lon2 - lon1) * Math.PI / 180
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) ** 2
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/** A position the app recorded: the import stores a point without one at 0/0 (QA's FINAL on delivery A, R2). */
export const hasPosition = (p: { lat: number; lng: number }) => p.lat !== 0 || p.lng !== 0
