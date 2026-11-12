import { useStore } from '../../store'
import { NavRail } from './NavRail'
import { Header } from './Header'
import { ErrorBoundary } from '../ErrorBoundary'
import { DashboardPanel } from '../panels/DashboardPanel'
import { TripPanel } from '../panels/TripPanel'
import { DiagnosticsPanel } from '../panels/DiagnosticsPanel'
import { ComparePanel } from '../panels/ComparePanel'
import { SessionsPanel } from '../panels/SessionsPanel'
import { ImportPanel } from '../panels/ImportPanel'
import { SettingsPanel } from '../panels/SettingsPanel'

export function Shell() {
  const activePanel = useStore((s) => s.activePanel)

  return (
    // Below md (768 px) the navigation is a bar at the bottom and the panels take the full width; md and up as before.
    // h-dvh: the visible viewport, so a phone browser's toolbar never covers the bar (100vh is the toolbar-hidden height;
    // dvh equals vh on desktop).
    <div className="flex flex-col-reverse md:flex-row h-dvh overflow-hidden bg-bg">
      <NavRail />
      <div className="flex flex-col flex-1 min-h-0 overflow-hidden">
        <Header />
        <main className="flex-1 overflow-y-auto p-3 md:p-6">
          <ErrorBoundary key={activePanel} fallbackLabel={`Error loading ${activePanel} panel`}>
            {activePanel === 'dashboard' && <DashboardPanel />}
            {activePanel === 'trip' && <TripPanel />}
            {activePanel === 'diagnostics' && <DiagnosticsPanel />}
            {activePanel === 'compare' && <ComparePanel />}
            {activePanel === 'sessions' && <SessionsPanel />}
            {activePanel === 'import' && <ImportPanel />}
            {activePanel === 'settings' && <SettingsPanel />}
          </ErrorBoundary>
        </main>
      </div>
    </div>
  )
}
