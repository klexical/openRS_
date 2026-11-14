import { hrefOf, type Address } from '../../lib/address'

// The two top-level places (#384 ticket 03): a bar at the bottom on a phone, a narrow rail on a desktop (index.css,
// .look-a). Real links: the address is the place, so a link opens it, Back returns, and every place works by keyboard.
const places: { label: string; to: Address; icon: React.ReactNode }[] = [
  { label: 'Drives', to: { place: 'drives' }, icon: <path d="M4 6h16M4 12h16M4 18h10" /> },
  { label: 'Settings', to: { place: 'settings' },
    icon: <><path d="M4 7h16M4 17h16" /><circle cx="9" cy="7" r="2.5" /><circle cx="15" cy="17" r="2.5" /></> },
]

export function Places({ current }: { current: Address }) {
  // a drive belongs to Drives: the place stays marked while one is open
  const here = current.place === 'settings' ? 'settings' : 'drives'
  return (
    <nav className="a-places" aria-label="Places">
      <span className="a-brand" aria-hidden="true">Sapphire</span>
      {places.map((p) => (
        <a key={p.label} className="a-place" href={hrefOf(p.to)} aria-current={p.to.place === here ? 'page' : undefined}>
          <svg className="a-ico" viewBox="0 0 24 24" aria-hidden="true">{p.icon}</svg>
          <span>{p.label}</span>
        </a>
      ))}
    </nav>
  )
}
