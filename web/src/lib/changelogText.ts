// web/CHANGELOG.md as the live site shows it: the file with every fragment under web/changelog.d/ folded in at build
// time (lib/changelogFragments.ts), so an entry merged as a fragment reaches "What's new" before a slice's last
// delivery writes it into the file. A malformed fragment fails the build's tests (changelogFragments.test.ts), not here.
import changelog from '../../CHANGELOG.md?raw'
import { foldFragments, readFragments } from './changelogFragments'

const FILES = import.meta.glob('../../changelog.d/*/*.md', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

export const CHANGELOG_TEXT = foldFragments(changelog, readFragments(FILES).fragments)
