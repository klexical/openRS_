// The backup and restore controls, loaded when they are first shown (Settings, or the failed-upgrade message) rather
// than on the first visit: the 300 kB first-visit budget (#384 ticket 02).
import { lazy, Suspense } from 'react'

const Controls = { load: () => import('./BackupControls') }
const LazyBackup = lazy(() => Controls.load().then((m) => ({ default: m.BackupButton })))
const LazyRestore = lazy(() => Controls.load().then((m) => ({ default: m.RestoreInput })))

export function BackupButton({ label }: { label: string }) {
  return <Suspense fallback={null}><LazyBackup label={label} /></Suspense>
}

export function RestoreInput() {
  return <Suspense fallback={null}><LazyRestore /></Suspense>
}
