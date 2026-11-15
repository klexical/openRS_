import { useAddress } from '../../lib/address'
import { LOOK_A, lookAVars } from '../../styles/lookA'
import { useSettings } from '../../store/settings'
import { useResolvedPalette } from '../../lib/devicePalette'
import { ErrorBoundary } from '../ErrorBoundary'
import { Places } from './Places'
import { SettingsPage } from './SettingsPage'
import { DrivesPage } from '../drives/DrivesPage'
import { DriveFrame } from '../drive/DriveFrame'
import { DropToImport, ImportStatus } from '../importing/ImportStatus'

/** Sapphire's frame (#384 ticket 03): the two places and the page the address names, in look A's palette (ticket 08). */
export function Shell() {
  const address = useAddress()
  const palette = useResolvedPalette(useSettings((s) => s.palette))
  const page = address.place === 'drive' ? `drive-${address.id}` : address.place
  return (
    <div className="look-a" data-palette={palette} style={lookAVars(LOOK_A[palette])}>
      <Places current={address} />
      <DropToImport />
      <div className="a-shell">
        <main>
          <ImportStatus />
          <ErrorBoundary key={page} fallbackLabel="Error loading this page">
            {address.place === 'drive' ? <DriveFrame id={address.id} />
              : address.place === 'settings' ? <SettingsPage /> : <DrivesPage />}
          </ErrorBoundary>
        </main>
      </div>
    </div>
  )
}
