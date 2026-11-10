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
