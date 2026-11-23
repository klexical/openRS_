// Changelog fragments: each web/CHANGELOG.md entry is written as its own file under web/changelog.d/, so two pull
// requests never edit the same lines of the changelog (web/changelog.d/README.md). The build folds them in, so the live
// site's "What's new" reads the folded text; a slice's last delivery writes the fold into the file
// (`node scripts/changelog.mjs fold`). Pure, and erasable TypeScript only, so that script imports it as it is.

export const SECTIONS = ['added', 'fixed', 'changed', 'removed'] as const
export type Section = (typeof SECTIONS)[number]
export interface Fragment { section: Section; issue: number; slug: string; path: string; lines: string[] }

const TITLE: Record<Section, string> = { added: 'Added', fixed: 'Fixed', changed: 'Changed', removed: 'Removed' }
const NAME = /^([1-9][0-9]{0,5})-([a-z0-9]+(?:-[a-z0-9]+)*)\.md$/
const ENTRY = /^- \*\*[^*].*?\*\* — \S/
const LINK = /\[#([0-9]+)\]\(([^)\s]*)\)/g
const BARE = /\(#[0-9]+\)|\b(?:[Ff]ixes|[Cc]loses|[Rr]esolves) #[0-9]+/
export const ISSUE_URL = 'https://github.com/klexical/openRS_/issues/'

/** Reads one fragment from its path (anything ending in changelog.d/<section>/<name>) and text, with every problem found. */
export function readFragment(path: string, text: string): { fragment: Fragment | null; problems: string[] } {
  const parts = path.split('/')
  const [dir, name] = [parts[parts.length - 2] ?? '', parts[parts.length - 1] ?? '']
  const rel = `changelog.d/${dir}/${name}`
  const problems: string[] = []
  const section = (SECTIONS as readonly string[]).includes(dir) ? (dir as Section) : null
  if (!section) problems.push(`${rel}: not in a section folder (${SECTIONS.join(', ')})`)
  const m = NAME.exec(name)
  if (!m) problems.push(`${rel}: the name is not <issue number>-<lower-case-slug>.md`)
  if (!text) problems.push(`${rel}: empty`)
  else if (text.includes('\r')) problems.push(`${rel}: carriage return`)
  else if (!text.endsWith('\n')) problems.push(`${rel}: the last line has no newline`)
  if (problems.length || !section || !m) return { fragment: null, problems }
  const lines = text.slice(0, -1).split('\n')
  lines.forEach((line, i) => {
    const where = `${rel}:${i + 1}`
    if (!line.trim()) { problems.push(`${where}: blank line`); return }
    if (!ENTRY.test(line)) problems.push(`${where}: not an entry line ('- **Short title** — Description …')`)
    if (line !== line.trimEnd()) problems.push(`${where}: trailing space`)
    for (const [, n, url] of line.matchAll(LINK)) {
      if (url !== ISSUE_URL + n) problems.push(`${where}: [#${n}] links ${url}, expected ${ISSUE_URL + n}`)
    }
    if (BARE.test(line)) problems.push(`${where}: a bare (#N) or closing-keyword reference; use [#N](${ISSUE_URL}N)`)
  })
  if (!lines.some((line) => [...line.matchAll(LINK)].some(([, n]) => n === m[1]))) {
    problems.push(`${rel}: no link to issue #${m[1]}, the number in its name`)
  }
  return { fragment: problems.length ? null : { section, issue: Number(m[1]), slug: m[2], path: rel, lines }, problems }
}

/**
 * The changelog with the fragments folded into its first section (`## …`, the slice in progress): per section, by issue
 * number then slug, after that section's last line; a missing section is made in the order Added, Fixed, Changed,
 * Removed. The same bytes as appending those lines by hand.
 */
export function foldFragments(changelog: string, fragments: Fragment[]): string {
  if (fragments.length === 0) return changelog
  const lines = changelog.split('\n')
  const head = lines.findIndex((l) => l.startsWith('## '))
  if (head < 0) throw new Error('web/CHANGELOG.md: no section heading (## ) to fold into')
  let end = lines.findIndex((l, i) => i > head && l.startsWith('## '))
  if (end < 0) end = lines.length
  while (end > head + 1 && (lines[end - 1] === '' || lines[end - 1] === '---')) end--
  const order = (l: string) => SECTIONS.findIndex((s) => l === `### ${TITLE[s]}`)
  for (const sec of SECTIONS) {
    const mine = fragments.filter((f) => f.section === sec)
      .sort((a, b) => a.issue - b.issue || (a.slug < b.slug ? -1 : a.slug > b.slug ? 1 : 0))
    if (mine.length === 0) continue
    const add = mine.flatMap((f) => f.lines)
    const heads: number[] = []
    for (let i = head + 1; i < end; i++) if (lines[i].startsWith('### ')) heads.push(i)
    const at = heads.find((i) => lines[i] === `### ${TITLE[sec]}`)
    if (at !== undefined) {
      let stop = heads.find((i) => i > at) ?? end
      while (stop > at + 1 && lines[stop - 1] === '') stop--
      lines.splice(stop, 0, ...add)
      end += add.length
      continue
    }
    const later = heads.find((i) => order(lines[i]) > SECTIONS.indexOf(sec))
    const block = later === undefined ? ['', `### ${TITLE[sec]}`, ...add] : [`### ${TITLE[sec]}`, ...add, '']
    lines.splice(later ?? end, 0, ...block)
    end += block.length
  }
  return lines.join('\n')
}

/** Every fragment of a glob result ({ path: text }), or the problems that stop the fold. */
export function readFragments(files: Record<string, string>): { fragments: Fragment[]; problems: string[] } {
  const fragments: Fragment[] = []
  const problems: string[] = []
  for (const path of Object.keys(files).sort()) {
    if (path.endsWith('/changelog.d/README.md')) continue
    const r = readFragment(path, files[path])
    if (r.fragment) fragments.push(r.fragment)
    problems.push(...r.problems)
  }
  return { fragments, problems }
}
