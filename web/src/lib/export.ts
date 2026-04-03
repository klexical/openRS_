import type { Session, TripPoint } from '../types/session'

/** Trigger a browser download of a Blob. */
function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ── CSV columns for trip points ──

const CSV_HEADERS: (keyof TripPoint)[] = [
  'ts', 'lat', 'lng', 'rpm', 'boostPsi', 'speedKph',
  'coolantC', 'oilTempC', 'rduTempC', 'ptuTempC', 'ambientC',
  'latG', 'longG', 'fuelPct',
  'wheelSpeedFL', 'wheelSpeedFR', 'wheelSpeedRL', 'wheelSpeedRR',
  'driveMode', 'awdTorqueL', 'awdTorqueR',
  'gear', 'throttlePct',
  'tirePressLF', 'tirePressRF', 'tirePressLR', 'tirePressRR',
  'tireTempLF', 'tireTempRF', 'tireTempLR', 'tireTempRR',
]

function escapeCsv(v: unknown): string {
  const s = String(v)
  if (s.includes(',') || s.includes('"') || s.includes('\n')) {
    return `"${s.replace(/"/g, '""')}"`
  }
  return s
}

/** Export a session's trip data as CSV. */
export function exportSessionCsv(session: Session) {
  const points = session.trip?.points
  if (!points || points.length === 0) return

  const rows = [CSV_HEADERS.join(',')]
  for (const p of points) {
    rows.push(CSV_HEADERS.map((k) => escapeCsv(p[k])).join(','))
  }

  const blob = new Blob([rows.join('\n')], { type: 'text/csv;charset=utf-8' })
  const safeName = session.name.replace(/[^a-zA-Z0-9_-]/g, '_')
  downloadBlob(blob, `${safeName}_trip.csv`)
}

/** Export a full session as JSON. */
export function exportSessionJson(session: Session) {
  const blob = new Blob([JSON.stringify(session, null, 2)], { type: 'application/json' })
  const safeName = session.name.replace(/[^a-zA-Z0-9_-]/g, '_')
  downloadBlob(blob, `${safeName}.json`)
}
