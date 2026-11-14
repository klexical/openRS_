import { useStore, type DriveView } from '../../store'
import { hrefOf } from '../../lib/address'
import { notCheckedLine } from '../../lib/warningWords'
import { ErrorBoundary } from '../ErrorBoundary'
import { lazyChunk } from '../ui/lazyChunk'

const Views = lazyChunk(() => import('./DriveViews').then((m) => m.DriveViews), 'this drive')

const VIEWS: { id: DriveView; label: string }[] = [
  { id: 'dashboard', label: 'Dashboard' }, { id: 'trip', label: 'Trip' }, { id: 'diagnostics', label: 'Diagnostics' },
]

/**
 * One drive at `#/drive/<id>` (#384 ticket 03): its name, the way back to the list, and today's Dashboard, Trip and
 * Diagnostics views reached from the drive. The old views keep their own layout and colours (slice 3 replaces them).
 */
export function DriveFrame({ id }: { id: string }) {
  const record = useStore((s) => s.drives.find((d) => d.id === id))
  const open = useStore((s) => (s.activeSession?.id === id ? s.activeSession : null))
  const view = useStore((s) => s.driveView)
  const failed = useStore((s) => s.openFailedId === id)
  const setView = useStore((s) => s.setDriveView)
  const name = record?.name ?? open?.name
  const notChecked = record ? notCheckedLine(record) : null   // #384 ticket 04
  return (
    <div className="a-page">
      <header>
        <a className="a-crumb" href={hrefOf({ place: 'drives' })}>← Drives</a>
        <h1>{name ?? 'Opening the drive…'}</h1>
        {notChecked && <p className="a-notchecked">{notChecked}</p>}
      </header>
      {failed ? (
        <p role="alert" className="a-notice">Couldn't open this drive. Try again. <a href={hrefOf({ place: 'drives' })}>Back to the list</a></p>
      ) : (<>
      <nav className="a-views" aria-label="Views of this drive">
        {VIEWS.map((v) => (
          <button key={v.id} type="button" className="a-btn" aria-pressed={view === v.id} onClick={() => setView(v.id)}>{v.label}</button>
        ))}
      </nav>
      <div className="old-view">
        <ErrorBoundary key={view} fallbackLabel={`Error loading the ${view} view`}>
          <Views view={view} />
        </ErrorBoundary>
      </div>
      </>)}
    </div>
  )
}
