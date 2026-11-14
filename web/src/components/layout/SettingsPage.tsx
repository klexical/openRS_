import { lazyChunk } from '../ui/lazyChunk'

// Settings at `#/settings` (#384 ticket 03): today's settings, unchanged; tickets 07 and 08 restyle them.
const Panel = lazyChunk(() => import('../panels/SettingsPanel').then((m) => m.SettingsPanel), 'Settings')

export function SettingsPage() {
  return (
    <section className="a-page" aria-labelledby="settings-title">
      <h1 id="settings-title">Settings</h1>
      <div className="old-view"><Panel /></div>
    </section>
  )
}
