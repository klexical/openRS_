import { lazyChunk } from '../ui/lazyChunk'

// Settings at `#/settings` (#384 tickets 03, 07): loaded when first opened, not on the first visit (the 300 kB budget).
const Screen = lazyChunk(() => import('../settings/SettingsScreen').then((m) => m.SettingsScreen), 'Settings')

export function SettingsPage() {
  return <Screen />
}
