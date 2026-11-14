import { useEffect } from 'react'
import { Shell } from './components/layout/Shell'
import { useStore } from './store'
import { useSettings } from './store/settings'
import { fillBestLap, listDrives, loadDrive, onStorageStatus, storageStatus } from './lib/storage'
import { go, useAddress } from './lib/address'
import { rsThemes, shadeHex, printAccent } from './styles/tokens'
import { colors } from './styles/tokens'
import { BackupButton } from './components/ui/LazyBackupControls'

export default function App() {
  const setDrives = useStore((s) => s.setDrives)
  const activeSessionId = useStore((s) => s.activeSessionId)
  const compareSessionIds = useStore((s) => s.compareSessionIds)
  const setStorage = useStore((s) => s.setStorage)
  const themeId = useSettings((s) => s.themeId)

  // Storage state → the store, for the messages below (#384)
  useEffect(() => {
    setStorage(storageStatus())
    return onStorageStatus(setStorage)
  }, [setStorage])

  // Load every drive's small record on mount (no points). Nothing is opened by itself: the address names what is shown
  // (#384 ticket 03; the remembered view is retired).
  useEffect(() => {
    listDrives()
      .then((drives) => { setDrives(drives); useStore.getState().setDrivesLoaded(true) })
      .catch((err) => {
        console.error('[Sapphire] Failed to load drives from IndexedDB:', err)
      })
  }, [setDrives])

  // The address names the open drive; the list and Settings open none (and a drive left is let go)
  const address = useAddress()
  const addressedId = address.place === 'drive' ? address.id : null
  useEffect(() => {
    const st = useStore.getState()
    st.setActiveSessionId(addressedId)
    if (address.place !== 'drives') st.setListNotice(null)
    if (address.place !== 'drives' || addressedId !== null) st.setImportOpen(false)
  }, [address.place, addressedId])

  // The open drive's points and log are read when it is opened and dropped when it is left (#384)
  useEffect(() => {
    const { setActiveSession } = useStore.getState()
    setActiveSession(null)
    if (!activeSessionId) return
    let current = true
    loadDrive(activeSessionId)
      .then((s) => {
        if (!current || useStore.getState().activeSessionId !== activeSessionId) return
        if (s === null) {
          // an address naming a drive that is not stored here (deleted, or another browser's link): the list, with a
          // plain message; the address is replaced so Back does not lead to it again. A failed read is not this.
          useStore.getState().setListNotice('That drive is not stored in this browser.')
          go({ place: 'drives' }, { replace: true })
        } else {
          setActiveSession(s)
          // a drive with laps stored before ticket 04 gets its best lap now that its points are read (only then: no
          // write on any other opening); a failure changes nothing
          const rec = useStore.getState().drives.find((d) => d.id === s.id)
          if (rec?.hasLaps && !('bestLapMs' in rec)) {
            fillBestLap(s)
              .then((filled) => { if (filled) useStore.getState().updateDrive(filled) })
              .catch((err) => console.warn('[Sapphire] Could not note the best lap:', err))
          }
        }
      })
      .catch((err) => console.error('[Sapphire] Failed to open the drive:', err))
    return () => { current = false }
  }, [activeSessionId])

  // The compared drives, likewise
  useEffect(() => {
    const { setCompareSessions, activeSession } = useStore.getState()
    if (compareSessionIds.length === 0) { setCompareSessions([]); return }
    let current = true
    Promise.all(compareSessionIds.map((id) => (activeSession?.id === id ? activeSession : loadDrive(id))))
      .then((loaded) => { if (current) setCompareSessions(loaded.filter((s) => s !== null)) })
      .catch((err) => console.error('[Sapphire] Failed to open the compared drives:', err))
    return () => { current = false }
  }, [compareSessionIds])

  // RS paint accent → CSS custom properties on the document root. Tailwind utilities (`text-accent`,
  // `bg-accent/10`, …) and `colors.accent` (a var()) resolve them at runtime, so this repaints everything.
  useEffect(() => {
    const accent = rsThemes[themeId] ?? rsThemes.cyan
    const root = document.documentElement.style
    root.setProperty('--color-accent', accent)
    root.setProperty('--color-accent-d', shadeHex(accent, -0.25))
    root.setProperty('--color-accent-print', printAccent(accent))
  }, [themeId])

  return (
    <>
      <StorageNotice />
      <Shell />
    </>
  )
}

/** Storage problems the owner has to know about, in plain words (#384). Nothing when storage is fine. */
function StorageNotice() {
  const storage = useStore((s) => s.storage)
  let text: string | null = null
  let action: { label: string; run: () => void } | null = null
  if (storage.state === 'blocked') {
    text = "Close Sapphire's other tabs to finish the update. Sapphire continues by itself once they are closed."
  } else if (storage.state === 'outdated') {
    text = 'Sapphire was updated: reload this tab to keep using it.'
    action = { label: 'Reload', run: () => window.location.reload() }
  } else if (storage.state === 'upgrade-failed') {
    text = `${storage.reason} Your drives are still stored as they were: nothing was changed or lost.`
  }
  if (!text) return null
  return (
    <div role="alert" className="px-4 py-2 text-sm font-mono flex items-center gap-3"
      style={{ background: colors.surf2, color: colors.warn, borderBottom: `1px solid ${colors.brd}` }}>
      <span>{text}</span>
      {action && (
        <button onClick={action.run} className="px-2 py-1 rounded border border-brd text-frost hover:bg-surf3">{action.label}</button>
      )}
      {/* After a failed upgrade, a backup of the old data comes first (#384 ticket 02) */}
      {storage.state === 'upgrade-failed' && <BackupButton label="Back up the old drives" afterFailedUpgrade />}
    </div>
  )
}
