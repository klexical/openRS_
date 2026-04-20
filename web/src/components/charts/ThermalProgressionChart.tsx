import { colors } from '../../styles/tokens'
import type { ThermalProgression } from '../../types/session'

/** Waterfall chart showing start → peak → end temperatures for oil + coolant. */
export function ThermalProgressionChart({ data }: { data: ThermalProgression }) {
  const sensors = [
    { key: 'oil', label: 'Oil', curve: data.oil, color: colors.orange },
    { key: 'coolant', label: 'Coolant', curve: data.coolant, color: colors.accent },
    ...(data.rdu ? [{ key: 'rdu', label: 'RDU', curve: data.rdu, color: '#E040FB' }] : []),
    ...(data.ptu ? [{ key: 'ptu', label: 'PTU', curve: data.ptu, color: '#FFCC00' }] : []),
  ].filter((s) => s.curve.peakC > -90)

  if (sensors.length === 0) return null

  const allTemps = sensors.flatMap((s) => [s.curve.startC, s.curve.peakC, s.curve.endC]).filter((v) => v > -90)
  const minT = Math.floor(Math.min(...allTemps) / 10) * 10
  const maxT = Math.ceil(Math.max(...allTemps) / 10) * 10
  const range = maxT - minT || 1

  const w = 400
  const h = 160
  const padL = 40
  const padR = 16
  const padT = 20
  const padB = 28
  const plotW = w - padL - padR
  const plotH = h - padT - padB

  const stageX = [0, 0.5, 1]
  const labels = ['Start', 'Peak', 'End']
  const toX = (frac: number) => padL + frac * plotW
  const toY = (temp: number) => padT + plotH - ((temp - minT) / range) * plotH

  // Y axis gridlines
  const gridCount = 4
  const gridStep = range / gridCount

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-4">
      <svg viewBox={`0 0 ${w} ${h}`} className="w-full" style={{ maxHeight: 200 }}>
        {/* Y gridlines */}
        {Array.from({ length: gridCount + 1 }).map((_, i) => {
          const temp = minT + i * gridStep
          const y = toY(temp)
          return (
            <g key={i}>
              <line x1={padL} y1={y} x2={w - padR} y2={y} stroke={colors.brd} strokeWidth={0.5} />
              <text x={padL - 4} y={y + 3} textAnchor="end" fill={colors.dim} fontSize={8} fontFamily="JetBrains Mono">
                {Math.round(temp)}°
              </text>
            </g>
          )
        })}

        {/* X labels */}
        {labels.map((label, i) => (
          <text key={label} x={toX(stageX[i])} y={h - 6} textAnchor="middle" fill={colors.dim} fontSize={8} fontFamily="JetBrains Mono">
            {label}
          </text>
        ))}

        {/* Sensor lines */}
        {sensors.map((sensor) => {
          const pts = [
            { x: toX(0), y: toY(sensor.curve.startC) },
            { x: toX(0.5), y: toY(sensor.curve.peakC) },
            { x: toX(1), y: toY(sensor.curve.endC) },
          ]
          const pathD = `M${pts.map((p) => `${p.x},${p.y}`).join(' L')}`
          const delta = sensor.curve.peakC - sensor.curve.startC

          return (
            <g key={sensor.key}>
              {/* Glow */}
              <path d={pathD} fill="none" stroke={sensor.color} strokeWidth={4} opacity={0.15} />
              {/* Line */}
              <path d={pathD} fill="none" stroke={sensor.color} strokeWidth={1.5} strokeLinejoin="round" />
              {/* Dots */}
              {pts.map((p, i) => (
                <circle key={i} cx={p.x} cy={p.y} r={3} fill={sensor.color} />
              ))}
              {/* Peak value label */}
              <text
                x={pts[1].x}
                y={pts[1].y - 8}
                textAnchor="middle"
                fill={sensor.color}
                fontSize={9}
                fontWeight="bold"
                fontFamily="JetBrains Mono"
              >
                {Math.round(sensor.curve.peakC)}°
              </text>
              {/* Delta annotation at end */}
              <text
                x={pts[2].x + 6}
                y={pts[2].y + 3}
                textAnchor="start"
                fill={delta > 0 ? colors.orange : colors.ok}
                fontSize={7}
                fontFamily="JetBrains Mono"
              >
                {delta > 0 ? '+' : ''}{Math.round(delta)}°
              </text>
            </g>
          )
        })}

        {/* Legend */}
        {sensors.map((sensor, i) => (
          <g key={`leg-${sensor.key}`} transform={`translate(${padL + i * 70}, ${padT - 10})`}>
            <rect width={8} height={3} rx={1} fill={sensor.color} />
            <text x={12} y={3} fill={sensor.color} fontSize={8} fontFamily="JetBrains Mono">{sensor.label}</text>
          </g>
        ))}
      </svg>
    </div>
  )
}
