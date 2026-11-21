import { describe, it, expect } from 'vitest'
import changelog from '../../CHANGELOG.md?raw'
import { RELEASE } from './whatsNew'
import { releaseTitles } from './changelogSection'

// #416 ticket 4: "What's new" reads the changelog section whose heading names the release it speaks of,
// not simply the newest section, so slice 2's dialog keeps showing slice 2 while slice 3's section grows.

const INVENTED = [
  '# Changelog', '', 'Opening lines.', '', '---', '',
  '## Something later (slice 3)', '', '### Added', '- **A later thing** — text. ([#1](x))', '',
  '### Changed', '- **A later change** — text. ([#1](x))', '',
  '## Something earlier (slice 2)', '', '### Added', '- **An earlier thing** — text. ([#2](x))',
  '- **A second earlier thing** — text. ([#2](x))', '', '### Fixed', '- **An earlier fix** — text. ([#2](x))', '',
  '### Changed', '- **An earlier change** — text. ([#2](x))', '', '### Removed', '- **An earlier removal** — text. ([#2](x))', '',
  '## Repairs (slice 1)', '', '### Fixed', '- **A repair** — text. ([#3](x))', '',
].join('\n')

describe('#416 ticket 4: What\'s new reads its own release\'s section', () => {
  it('the named release\'s section, not the newest one: its Added and Changed titles, in order', () => {
    expect(releaseTitles(INVENTED, 'slice-2')).toEqual({
      added: ['An earlier thing', 'A second earlier thing'], changed: ['An earlier change'],
    })
  })

  it('the newest section, when it is the one named', () => {
    expect(releaseTitles(INVENTED, 'slice-3')).toEqual({ added: ['A later thing'], changed: ['A later change'] })
  })

  it('the last section in the file has an end too', () => {
    expect(releaseTitles(INVENTED, 'slice-1')).toEqual({ added: [], changed: [] })
  })

  it('a release with no section: none (not the newest section)', () => {
    expect(releaseTitles(INVENTED, 'slice-4')).toBeNull()
    expect(releaseTitles(INVENTED, 'slice-')).toBeNull()
  })

  it('the release What\'s new speaks of has its section in web/CHANGELOG.md, with titles to show', () => {
    const titles = releaseTitles(changelog, RELEASE)
    expect(titles).not.toBeNull()
    expect(titles!.added.length).toBeGreaterThan(0)
  })
})
