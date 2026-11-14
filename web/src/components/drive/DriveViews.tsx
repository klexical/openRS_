// Today's three views of a drive, loaded when a drive is first opened (they carry the charts and the map: the 300 kB
// first-visit budget, #384). Slice 3 replaces them with the drive page.
import type { DriveView } from '../../store'
import { DashboardPanel } from '../panels/DashboardPanel'
import { TripPanel } from '../panels/TripPanel'
import { DiagnosticsPanel } from '../panels/DiagnosticsPanel'

export function DriveViews({ view }: { view: DriveView }) {
  if (view === 'trip') return <TripPanel />
  if (view === 'diagnostics') return <DiagnosticsPanel />
  return <DashboardPanel />
}
