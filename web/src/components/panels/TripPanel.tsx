import { useActiveSession } from '../../store'
import { EmptyState } from '../ui/EmptyState'
import { SectionLabel } from '../ui/SectionLabel'
import { TimeSeriesChart } from '../charts/TimeSeriesChart'
import { colors, chartColors } from '../../styles/tokens'

export function TripPanel() {
  const session = useActiveSession()

  if (!session) {
    return <EmptyState icon="◎" title="No Session Selected" description="Select a session to view trip data." />
  }

  const trip = session.trip
  if (!trip || trip.points.length === 0) {
    return <EmptyState icon="◎" title="No Trip Data" description="This session does not contain trip data." />
  }

  const points = trip.points
  const t0 = points[0].ts

  // Build chart data with relative time in seconds
  const chartData = points.map((p) => ({
    ts: (p.ts - t0) / 1000,
    rpm: p.rpm,
    boostPsi: p.boostPsi,
    speedKph: p.speedKph,
    coolantC: p.coolantC > -90 ? p.coolantC : 0,
    oilTempC: p.oilTempC > -90 ? p.oilTempC : 0,
    rduTempC: p.rduTempC > -90 ? p.rduTempC : 0,
    ptuTempC: p.ptuTempC > -90 ? p.ptuTempC : 0,
    latG: p.latG,
    fuelPct: p.fuelPct >= 0 ? p.fuelPct : 0,
    wsFL: p.wheelSpeedFL,
    wsFR: p.wheelSpeedFR,
    wsRL: p.wheelSpeedRL,
    wsRR: p.wheelSpeedRR,
    awdL: p.awdTorqueL,
    awdR: p.awdTorqueR,
  }))

  const fmtTime = (sec: number) => {
    const m = Math.floor(sec / 60)
    const s = Math.round(sec % 60)
    return `${m}:${s.toString().padStart(2, '0')}`
  }

  return (
    <div className="max-w-6xl mx-auto space-y-2">
      <SectionLabel>RPM</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'rpm', label: 'RPM', color: colors.accent }]}
        xFormatter={fmtTime}
      />

      <SectionLabel>Boost (PSI)</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'boostPsi', label: 'Boost PSI', color: chartColors[1] }]}
        xFormatter={fmtTime}
      />

      <SectionLabel>Speed (KPH)</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'speedKph', label: 'Speed', color: chartColors[2] }]}
        xFormatter={fmtTime}
      />

      <SectionLabel>Temperatures</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[
          { key: 'coolantC', label: 'Coolant', color: colors.accent },
          { key: 'oilTempC', label: 'Oil', color: colors.orange },
          { key: 'rduTempC', label: 'RDU', color: chartColors[4] },
          { key: 'ptuTempC', label: 'PTU', color: chartColors[3] },
        ]}
        xFormatter={fmtTime}
        yFormatter={(v) => `${v}°`}
        height={220}
      />

      <SectionLabel>Lateral G</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'latG', label: 'Lat G', color: chartColors[3] }]}
        xFormatter={fmtTime}
        yFormatter={(v) => `${v.toFixed(2)}G`}
      />

      <SectionLabel>Fuel Level (%)</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'fuelPct', label: 'Fuel %', color: chartColors[6] }]}
        xFormatter={fmtTime}
      />

      <SectionLabel>Wheel Speeds</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[
          { key: 'wsFL', label: 'FL', color: colors.accent },
          { key: 'wsFR', label: 'FR', color: chartColors[1] },
          { key: 'wsRL', label: 'RL', color: chartColors[2] },
          { key: 'wsRR', label: 'RR', color: chartColors[3] },
        ]}
        xFormatter={fmtTime}
        height={220}
      />

      <SectionLabel>AWD Torque (Nm)</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[
          { key: 'awdL', label: 'Left', color: colors.accent },
          { key: 'awdR', label: 'Right', color: chartColors[1] },
        ]}
        xFormatter={fmtTime}
      />
    </div>
  )
}
