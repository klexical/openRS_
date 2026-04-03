import { colors } from '../../styles/tokens'

interface TireData {
  pressLF: number
  pressRF: number
  pressLR: number
  pressRR: number
  tempLF: number
  tempRF: number
  tempLR: number
  tempRR: number
}

/**
 * 4-corner tire visualization showing pressure + temp for each tire.
 * Color-coded by threshold (green/yellow/red).
 */
export function TpmsSummaryCard({ data }: { data: TireData }) {
  const hasPress = data.pressLF >= 0 || data.pressRF >= 0 || data.pressLR >= 0 || data.pressRR >= 0
  const hasTemp = data.tempLF > -90 || data.tempRF > -90 || data.tempLR > -90 || data.tempRR > -90

  if (!hasPress && !hasTemp) return null

  const tires = [
    { label: 'LF', press: data.pressLF, temp: data.tempLF, col: 0, row: 0 },
    { label: 'RF', press: data.pressRF, temp: data.tempRF, col: 1, row: 0 },
    { label: 'LR', press: data.pressLR, temp: data.tempLR, col: 0, row: 1 },
    { label: 'RR', press: data.pressRR, temp: data.tempRR, col: 1, row: 1 },
  ]

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-4">
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, maxWidth: 280 }}>
        {tires.map((t) => (
          <div
            key={t.label}
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 2,
              padding: '8px 12px',
              borderRadius: 8,
              background: colors.surf3,
              border: `1px solid ${colors.brd}`,
            }}
          >
            <span style={{
              fontSize: 9,
              fontFamily: 'JetBrains Mono, monospace',
              color: colors.dim,
              textTransform: 'uppercase',
              letterSpacing: '0.1em',
            }}>
              {t.label}
            </span>
            {t.press >= 0 && (
              <span style={{
                fontSize: 16,
                fontFamily: 'Orbitron, sans-serif',
                fontWeight: 700,
                color: pressColor(t.press),
              }}>
                {t.press.toFixed(1)}
              </span>
            )}
            {t.press >= 0 && (
              <span style={{ fontSize: 8, fontFamily: 'JetBrains Mono', color: colors.dim }}>PSI</span>
            )}
            {t.temp > -90 && (
              <span style={{
                fontSize: 11,
                fontFamily: 'JetBrains Mono, monospace',
                color: tempColor(t.temp),
              }}>
                {Math.round(t.temp)}°C
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

function pressColor(psi: number): string {
  if (psi < 28) return colors.red
  if (psi < 30) return colors.warn
  if (psi > 40) return colors.warn
  return colors.ok
}

function tempColor(c: number): string {
  if (c < 0) return colors.accent
  if (c < 40) return colors.ok
  if (c < 60) return colors.warn
  return colors.orange
}
