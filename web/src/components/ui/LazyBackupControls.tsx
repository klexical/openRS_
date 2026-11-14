// The backup and restore controls, loaded when they are first shown (Settings, or the failed-upgrade message) rather
// than on the first visit: the 300 kB first-visit budget (#384 ticket 02).
import { lazy, Suspense } from 'react'

/** A tab left open across a deploy asks for a chunk that is gone: say so, and keep the page. */
function Unloadable() {
  return <span className="text-xs font-mono text-warn">Sapphire was updated: reload this tab to back up or restore.</span>
}
let loaded: Promise<typeof import('./BackupControls') | null> | null = null
const load = () => (loaded ??= import('./BackupControls').catch((err: unknown) => {
  console.warn('[Sapphire] The backup controls could not load:', err)
  return null
}))
const LazyBackup = lazy(() => load().then((m) => ({ default: m?.BackupButton ?? Unloadable })))
const LazyRestore = lazy(() => load().then((m) => ({ default: m?.RestoreInput ?? (() => null) })))

export function BackupButton(props: { label: string; afterFailedUpgrade?: boolean }) {
  return <Suspense fallback={null}><LazyBackup {...props} /></Suspense>
}

export function RestoreInput() {
  return <Suspense fallback={null}><LazyRestore /></Suspense>
}
