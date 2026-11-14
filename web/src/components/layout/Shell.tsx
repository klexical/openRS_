import { useAddress } from '../../lib/address'
import { lookANight, lookAVars } from '../../styles/lookA'
import { ErrorBoundary } from '../ErrorBoundary'
import { Places } from './Places'
import { SettingsPage } from './SettingsPage'
import { DrivesPage } from '../drives/DrivesPage'
import { DriveFrame } from '../drive/DriveFrame'

/** Sapphire's frame (#384 ticket 03): the two places and the page the address names, in look A's Night palette. */
export function Shell() {
  const address = useAddress()
  const page = address.place === 'drive' ? `drive-${address.id}` : address.place
  return (
    <div className="look-a" style={lookAVars(lookANight)}>
      <Places current={address} />
      <div className="a-shell">
        <main>
          <ErrorBoundary key={page} fallbackLabel="Error loading this page">
            {address.place === 'drive' ? <DriveFrame id={address.id} />
              : address.place === 'settings' ? <SettingsPage /> : <DrivesPage />}
          </ErrorBoundary>
        </main>
      </div>
    </div>
  )
}
