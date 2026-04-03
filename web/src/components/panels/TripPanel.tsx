import { useMemo } from 'react'
import { useActiveSession } from '../../store'
import { EmptyState } from '../ui/EmptyState'
import { SectionLabel } from '../ui/SectionLabel'
import { ExportDropdown } from '../ui/ExportDropdown'
import { TimeSeriesChart } from '../charts/TimeSeriesChart'
import { GpsMap } from '../charts/GpsMap'
import { ModeTimeline } from '../charts/ModeTimeline'
import { GForceScatter } from '../charts/GForceScatter'
import { TempStackChart } from '../charts/TempStackChart'
import { Histogram } from '../charts/Histogram'
import { useUnitFormatters } from '../../lib/format'
import { colors, chartColors } from '../../styles/tokens'

export function TripPanel() {
  const session = useActiveSession()
  const fmt = useUnitFormatters()

  if (!session) {
    return <EmptyState icon="◎" title="No Session Selected" description="Select a session to view trip data." />
  }

  const trip = session.trip
  if (!trip || trip.points.length === 0) {
    return <EmptyState icon="◎" title="No Trip Data" description="This session does not contain trip data." />
  }

  const points = trip.points
  const t0 = points[0].ts
  const peakEvents = trip.summary.peakEvents

  // Build chart data with relative time in seconds
  const chartData = useMemo(() => points.map((p) => ({
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
    throttlePct: p.throttlePct >= 0 ? p.throttlePct : 0,
    tirePressLF: p.tirePressLF >= 0 ? p.tirePressLF : 0,
    tirePressRF: p.tirePressRF >= 0 ? p.tirePressRF : 0,
    tirePressLR: p.tirePressLR >= 0 ? p.tirePressLR : 0,
    tirePressRR: p.tirePressRR >= 0 ? p.tirePressRR : 0,
    tireTempLF: p.tireTempLF > -90 ? p.tireTempLF : 0,
    tireTempRF: p.tireTempRF > -90 ? p.tireTempRF : 0,
    tireTempLR: p.tireTempLR > -90 ? p.tireTempLR : 0,
    tireTempRR: p.tireTempRR > -90 ? p.tireTempRR : 0,
  })), [points, t0])

  const fmtTime = (sec: number) => {
    const m = Math.floor(sec / 60)
    const s = Math.round(sec % 60)
    return `${m}:${s.toString().padStart(2, '0')}`
  }

  // Check data availability
  const hasThrottle = points.some((p) => p.throttlePct >= 0)
  const hasTpmsPress = points.some((p) => p.tirePressLF >= 0 || p.tirePressRF >= 0)
  const hasTpmsTemp = points.some((p) => p.tireTempLF > -90 || p.tireTempRF > -90)

  const syncId = 'trip-charts'

  return (
    <div className="max-w-6xl mx-auto space-y-2">
      {/* Header with export */}
      <div className="flex items-center justify-between mb-2">
        <h2 className="text-sm font-display tracking-wide text-frost">{session.name}</h2>
        <ExportDropdown session={session} />
      </div>

      <SectionLabel>Route</SectionLabel>
      <GpsMap points={points} peakEvents={peakEvents} />

      <SectionLabel>Drive Modes</SectionLabel>
      <ModeTimeline points={points} />

      <SectionLabel>RPM</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'rpm', label: 'RPM', color: colors.accent }]}
        xFormatter={fmtTime}
        syncId={syncId}
        peakEvents={peakEvents}
        peakFilter="rpm"
      />

      <SectionLabel>Boost ({fmt.boostUnit})</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'boostPsi', label: `Boost ${fmt.boostUnit}`, color: chartColors[1] }]}
        xFormatter={fmtTime}
        syncId={syncId}
        peakEvents={peakEvents}
        peakFilter="boost"
      />

      <SectionLabel>Speed ({fmt.speedUnit})</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'speedKph', label: 'Speed', color: chartColors[2] }]}
        xFormatter={fmtTime}
        syncId={syncId}
        peakEvents={peakEvents}
        peakFilter="speed"
      />

      {hasThrottle && (
        <>
          <SectionLabel>Throttle (%)</SectionLabel>
          <TimeSeriesChart
            data={chartData}
            series={[{ key: 'throttlePct', label: 'Throttle', color: chartColors[5] }]}
            xFormatter={fmtTime}
            syncId={syncId}
          />
        </>
      )}

      <SectionLabel>Temperatures ({fmt.tempUnit})</SectionLabel>
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
        syncId={syncId}
      />

      <SectionLabel>Lateral G</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'latG', label: 'Lat G', color: chartColors[3] }]}
        xFormatter={fmtTime}
        yFormatter={(v) => `${v.toFixed(2)}G`}
        syncId={syncId}
        peakEvents={peakEvents}
        peakFilter="latG"
      />

      <SectionLabel>Fuel Level (%)</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'fuelPct', label: 'Fuel %', color: chartColors[6] }]}
        xFormatter={fmtTime}
        syncId={syncId}
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
        syncId={syncId}
      />

      {hasTpmsPress && (
        <>
          <SectionLabel>Tire Pressure ({fmt.tirePressUnit})</SectionLabel>
          <TimeSeriesChart
            data={chartData}
            series={[
              { key: 'tirePressLF', label: 'LF', color: colors.accent },
              { key: 'tirePressRF', label: 'RF', color: chartColors[1] },
              { key: 'tirePressLR', label: 'LR', color: chartColors[2] },
              { key: 'tirePressRR', label: 'RR', color: chartColors[3] },
            ]}
            xFormatter={fmtTime}
            yFormatter={(v) => `${v.toFixed(1)}`}
            height={200}
            syncId={syncId}
          />
        </>
      )}

      {hasTpmsTemp && (
        <>
          <SectionLabel>Tire Temperature ({fmt.tempUnit})</SectionLabel>
          <TimeSeriesChart
            data={chartData}
            series={[
              { key: 'tireTempLF', label: 'LF', color: colors.accent },
              { key: 'tireTempRF', label: 'RF', color: chartColors[1] },
              { key: 'tireTempLR', label: 'LR', color: chartColors[2] },
              { key: 'tireTempRR', label: 'RR', color: chartColors[3] },
            ]}
            xFormatter={fmtTime}
            yFormatter={(v) => `${v}°`}
            height={200}
            syncId={syncId}
          />
        </>
      )}

      <SectionLabel>AWD Torque (Nm)</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[
          { key: 'awdL', label: 'Left', color: colors.accent },
          { key: 'awdR', label: 'Right', color: chartColors[1] },
        ]}
        xFormatter={fmtTime}
        syncId={syncId}
      />

      {/* ── Advanced Visualizations ── */}

      <SectionLabel>G-Force vs Speed</SectionLabel>
      <GForceScatter points={points} />

      <SectionLabel>Thermal Soak</SectionLabel>
      <TempStackChart points={points} syncId={syncId} />

      <SectionLabel>RPM Distribution</SectionLabel>
      <Histogram
        values={points.map((p) => p.rpm)}
        bins={14}
        color={colors.accent}
        xLabel="RPM"
        yLabel="Samples"
      />

      <SectionLabel>Boost Distribution ({fmt.boostUnit})</SectionLabel>
      <Histogram
        values={points.map((p) => p.boostPsi).filter((v) => v > -5)}
        bins={12}
        color={chartColors[1]}
        xLabel={fmt.boostUnit}
        yLabel="Samples"
      />
    </div>
  )
}
