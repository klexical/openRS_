import { useEffect } from 'react'
import { Shell } from './components/layout/Shell'
import { useStore } from './store'
import { useSettings } from './store/settings'
import { onStorageStatus, storageStatus } from './lib/storage'
import { attach, loadList, showDrive } from './lib/driveLibrary'
import { go, useAddress } from './lib/address'
import { rsThemes, shadeHex, printAccent } from './styles/tokens'
import { colors } from './styles/tokens'
import { BackupButton } from './components/ui/LazyBackupControls'

export default function App() {
  const setStorage = useStore((s) => s.setStorage)
  const themeId = useSettings((s) => s.themeId)

  // Storage state → the store, for the messages below (#384)
  useEffect(() => {
    setStorage(storageStatus())
    return onStorageStatus(setStorage)
  }, [setStorage])

  // Load every drive's small record on mount (no points). Nothing is opened by itself: the address names what is shown
  // (#384 ticket 03; the remembered view is retired).
  useEffect(() => { void loadList() }, [])

  // The address names the open drive; the list and Settings open none (and a drive left is let go)
  const address = useAddress()
  const addressedId = address.place === 'drive' ? address.id : null
  useEffect(() => {
    const st = useStore.getState()
    showDrive(addressedId)
    if (address.place !== 'drives') st.setListNotice(null)
    if (address.place !== 'drives' || addressedId !== null) st.setCompareOpen(false)
  }, [address.place, addressedId])

  // The Drive library reads the Current drive and the compared drives as the store names them (#384). An address that
  // names a drive not stored here (deleted, or another browser's link): the list, with a plain message; the address is
  // replaced so Back does not lead to it again. A failed read is not this: the drive's page says so.
  useEffect(() => attach({
    onNotStored: () => {
      useStore.getState().setListNotice('That drive is not stored in this browser.')
      go({ place: 'drives' }, { replace: true })
    },
  }), [])

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
