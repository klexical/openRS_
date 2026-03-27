import { useStore } from '../../store'
import { NavRail } from './NavRail'
import { Header } from './Header'
import { DashboardPanel } from '../panels/DashboardPanel'
import { TripPanel } from '../panels/TripPanel'
import { DiagnosticsPanel } from '../panels/DiagnosticsPanel'
import { SessionsPanel } from '../panels/SessionsPanel'
import { ImportPanel } from '../panels/ImportPanel'

export function Shell() {
  const activePanel = useStore((s) => s.activePanel)

  return (
    <div className="flex h-screen overflow-hidden bg-bg">
      <NavRail />
      <div className="flex flex-col flex-1 overflow-hidden">
        <Header />
        <main className="flex-1 overflow-y-auto p-6">
          {activePanel === 'dashboard' && <DashboardPanel />}
          {activePanel === 'trip' && <TripPanel />}
          {activePanel === 'diagnostics' && <DiagnosticsPanel />}
          {activePanel === 'sessions' && <SessionsPanel />}
          {activePanel === 'import' && <ImportPanel />}
        </main>
      </div>
    </div>
  )
}
