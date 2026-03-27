/** Unit conversion and formatting helpers. */

export const KM_TO_MI = 0.621371
export const KPA_TO_PSI = 0.145038
export const L_PER_100KM_TO_MPG = 235.215

export function fmtNumber(n: number, decimals = 1): string {
  if (!isFinite(n)) return '—'
  return n.toFixed(decimals)
}

export function fmtDuration(ms: number): string {
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

export function fmtTimestamp(epoch: number): string {
  return new Date(epoch).toLocaleString()
}

export function fmtDateShort(epoch: number): string {
  return new Date(epoch).toLocaleDateString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
  })
}

export function fmtTemp(c: number): string {
  if (c <= -90) return '—'
  return `${Math.round(c)}°C`
}

export function fmtSpeed(kph: number): string {
  return `${Math.round(kph)} kph`
}

export function fmtPsi(psi: number): string {
  if (psi < 0) return '—'
  return `${fmtNumber(psi)} PSI`
}
