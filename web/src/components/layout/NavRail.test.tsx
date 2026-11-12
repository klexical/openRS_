import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { useStore } from '../../store'
import { NavRail } from './NavRail'
import { Shell } from './Shell'

// #344: below the phone breakpoint (Tailwind md, 768 px) the navigation is a full-width bar under the panels, not a fixed
// column beside them; md and up keep the rail. jsdom does no layout or media queries, so this checks the classes that
// apply below md (no responsive prefix) and the md: classes that restore the rail; scripts/layout-check measures pixels.

vi.mock('../panels/DashboardPanel', () => ({ DashboardPanel: () => null }))
vi.mock('../panels/TripPanel', () => ({ TripPanel: () => null }))
vi.mock('../panels/DiagnosticsPanel', () => ({ DiagnosticsPanel: () => null }))
vi.mock('../panels/ComparePanel', () => ({ ComparePanel: () => null }))
vi.mock('../panels/SessionsPanel', () => ({ SessionsPanel: () => null }))
vi.mock('../panels/ImportPanel', () => ({ ImportPanel: () => null }))
vi.mock('../panels/SettingsPanel', () => ({ SettingsPanel: () => null }))

const classes = (el: Element) => (el.getAttribute('class') ?? '').split(/\s+/).filter(Boolean)
/** Classes in effect below md: those without a breakpoint prefix. */
const belowMd = (el: Element) => classes(el).filter((c) => !/^(sm|md|lg|xl|2xl):/.test(c))

beforeEach(() => { useStore.setState({ activePanel: 'trip', navExpanded: true }) })
afterEach(cleanup)

describe('navigation at phone width (#344)', () => {
  for (const expanded of [true, false]) {
    it(`is a full-width row below md, the rail from md (${expanded ? 'expanded' : 'collapsed'})`, () => {
      useStore.setState({ navExpanded: expanded })
      const { container } = render(<NavRail />)
      const nav = container.querySelector('nav')!
      const small = belowMd(nav)
      expect(small).toContain('w-full')
      expect(small.filter((c) => /^w-/.test(c) && c !== 'w-full')).toEqual([])      // no fixed column width
      expect(small).toContain('flex-row')
      expect(small).not.toContain('flex-col')
      // md and up: the rail as before
      expect(classes(nav)).toContain('md:flex-col')
      expect(classes(nav)).toContain(expanded ? 'md:w-48' : 'md:w-14')
    })
  }

  it('shows icons only below md; every item is named for assistive technology', () => {
    const { container } = render(<NavRail />)
    const items = [...container.querySelectorAll('nav button[aria-label]')]
    expect(items.map((b) => b.getAttribute('aria-label'))).toEqual(
      ['Dashboard', 'Trip', 'Diagnostics', 'Compare', 'Sessions', 'Import', 'Settings'])
    for (const b of items) {
      const label = [...b.querySelectorAll('span')].find((s) => s.textContent === b.getAttribute('aria-label'))!
      expect(belowMd(label)).toContain('hidden')
      expect(classes(label)).toContain('md:inline')
    }
  })

  it('puts the navigation under the panels below md and beside them from md', () => {
    const { container } = render(<Shell />)
    const root = container.firstElementChild!
    expect(belowMd(root)).toContain('flex-col-reverse')                  // the nav (first child) ends up at the bottom
    expect(classes(root)).toContain('md:flex-row')
    expect(root.firstElementChild?.tagName).toBe('NAV')
  })
})
