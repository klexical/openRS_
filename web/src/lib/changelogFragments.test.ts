import { describe, it, expect } from 'vitest'
import changelog from '../../CHANGELOG.md?raw'
import { foldFragments, readFragment, readFragments, ISSUE_URL, type Fragment } from './changelogFragments'
import { CHANGELOG_TEXT } from './changelogText'

// Changelog fragments (web/changelog.d/README.md): entries are one file each, folded into the newest slice section, so
// pull requests to main stop conflicting in web/CHANGELOG.md. A fold gives the bytes appending the lines by hand gives.

const entry = (n: number, title: string, text = 'What changed, and why.') => `- **${title}** — ${text} ([#${n}](${ISSUE_URL}${n}))`
const frag = (path: string, text: string): Fragment => {
  const r = readFragment(path, text)
  if (!r.fragment) throw new Error(r.problems.join('; '))
  return r.fragment
}
// every file under web/changelog.d/, so a stray file outside a section folder is seen too
const ALL = import.meta.glob('../../changelog.d/**', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

describe('changelog fragments', () => {
  it('every fragment in web/changelog.d/ is well formed, and only README.md sits beside the section folders', () => {
    expect(readFragments(ALL).problems).toEqual([])
  })

  it('the changelog the site shows is the file with the fragments folded in', () => {
    expect(CHANGELOG_TEXT).toBe(foldFragments(changelog, readFragments(ALL).fragments))
  })

  it('the real changelog: its newest section\'s last entries taken out as fragments fold back byte for byte', () => {
    const lines = changelog.split('\n')
    const head = lines.findIndex((l) => l.startsWith('## '))
    const end = lines.findIndex((l, i) => i > head && l.startsWith('## '))
    const taken: Fragment[] = []
    const last = [...lines.keys()].filter((i) => i > head && i < end && lines[i].startsWith('- ') && lines[i + 1] === '')
    for (const i of last.reverse()) {                              // bottom-up: the last entry under each ### heading
      const n = /\[#(\d+)\]/.exec(lines[i])![1]
      const title = lines.slice(head, i).reverse().find((l) => l.startsWith('### '))!.slice(4).toLowerCase()
      taken.push(frag(`changelog.d/${title}/${n}-taken.md`, lines[i] + '\n'))
      lines.splice(i, 1)
    }
    expect(taken.length).toBeGreaterThanOrEqual(2)
    expect(foldFragments(lines.join('\n'), taken)).toBe(changelog)
  })

  it('by issue number then slug, after the list\'s entries; a missing list is made in the order Added, Fixed, Changed, Removed', () => {
    const base = ['# Changelog', '', 'Intro.', '', '---', '', '## Later (slice 4)', '', '### Fixed', entry(300, 'Old'), '',
      '## Earlier (slice 3)', '', '### Added', entry(1, 'Ancient'), ''].join('\n')
    const out = foldFragments(base, [
      frag('changelog.d/fixed/42-b.md', entry(42, 'B') + '\n'),
      frag('changelog.d/fixed/42-a.md', entry(42, 'A') + '\n' + entry(42, 'A2') + '\n'),
      frag('changelog.d/fixed/7-z.md', entry(7, 'Z') + '\n'),
      frag('changelog.d/removed/9-gone.md', entry(9, 'Gone') + '\n'),
      frag('changelog.d/added/11-new.md', entry(11, 'New') + '\n'),
    ])
    expect(out).toBe(['# Changelog', '', 'Intro.', '', '---', '', '## Later (slice 4)', '',
      '### Added', entry(11, 'New'), '',
      '### Fixed', entry(300, 'Old'), entry(7, 'Z'), entry(42, 'A'), entry(42, 'A2'), entry(42, 'B'), '',
      '### Removed', entry(9, 'Gone'), '',
      '## Earlier (slice 3)', '', '### Added', entry(1, 'Ancient'), ''].join('\n'))
  })

  it('no fragment: the file unchanged', () => {
    expect(foldFragments(changelog, [])).toBe(changelog)
  })

  it.each([
    ['changelog.d/fixed/0042-x.md', entry(42, 'X') + '\n', 'the name is not'],
    ['changelog.d/fixed/42_x.md', entry(42, 'X') + '\n', 'the name is not'],
    ['changelog.d/improved/42-x.md', entry(42, 'X') + '\n', 'not in a section folder'],
    ['changelog.d/notes.md', 'text\n', 'not in a section folder'],
    ['changelog.d/fixed/42-x.md', '', 'empty'],
    ['changelog.d/fixed/42-x.md', entry(42, 'X'), 'no newline'],
    ['changelog.d/fixed/42-x.md', entry(42, 'X') + '\r\n', 'carriage return'],
    ['changelog.d/fixed/42-x.md', entry(42, 'X') + '\n\n' + entry(42, 'Y') + '\n', 'blank line'],
    ['changelog.d/fixed/42-x.md', `Fixed a thing. ([#42](${ISSUE_URL}42))\n`, 'not an entry line'],
    ['changelog.d/fixed/42-x.md', entry(42, 'X') + ' \n', 'trailing space'],
    ['changelog.d/fixed/42-x.md', '- **X** — y. ([#42](https://github.com/someone/else/issues/42))\n', 'expected'],
    ['changelog.d/fixed/42-x.md', entry(42, 'X') + ' (#7)\n', 'bare'],
    ['changelog.d/fixed/42-x.md', entry(41, 'X') + '\n', 'no link to issue #42'],
  ])('refused: %s (%s)', (path, text, reason) => {
    const r = readFragment(path, text)
    expect(r.fragment).toBeNull()
    expect(r.problems.join('\n')).toContain(reason)
  })
})
