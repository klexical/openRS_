import type { TripPoint } from '../types/session'
import { colors, modeColors } from '../styles/tokens'

export interface GForceSample { latG: number; speedKph: number; driveMode: string }

/**
 * G-force scatter samples: moving samples only, stride-downsampled to about `target` dots for long drives —
 * but the largest left and right lateral-G samples always stay, so the plotted envelope shows the trip's
 * real peaks even when they fall between strides (main e9cd565).
 */
export function gForceSamples(points: TripPoint[], target = 2000): GForceSample[] {
  const valid: GForceSample[] = []
  for (const p of points) {
    if (Math.abs(p.latG) > 0.01 || p.speedKph > 5) valid.push({ latG: p.latG, speedKph: p.speedKph, driveMode: p.driveMode })
  }
  if (valid.length <= target) return valid

  let maxIdx = 0
  let minIdx = 0
  for (let i = 1; i < valid.length; i++) {
    if (valid[i].latG > valid[maxIdx].latG) maxIdx = i
    if (valid[i].latG < valid[minIdx].latG) minIdx = i
  }
  const step = Math.ceil(valid.length / target)
  const kept = new Set<number>([maxIdx, minIdx])
  for (let i = 0; i < valid.length; i += step) kept.add(i)
  return [...kept].sort((a, b) => a - b).map((i) => valid[i])
}

/**
 * The G-force plot's dot colour for a drive mode (any casing). "--" is what the app stores while it does not know the mode
 * yet (Sport or Track before the first 0x420, #516): dim, never drawn as Normal.
 */
export function modeDotColor(driveMode: string): string {
  switch (driveMode.toLowerCase()) {
    case 'normal': return modeColors.normal
    case 'sport': return modeColors.sport
    case 'track': return modeColors.track
    case 'drift': return modeColors.drift
    default: return colors.dim
  }
}
