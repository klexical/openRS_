// The changelog section "What's new" shows (#416 ticket 4, owner Q84 / Q87): the one whose heading names the release the
// dialog speaks of ("## … (slice 2)" for 'slice-2'), not simply the newest section, so a later slice's section can open
// and grow before its release name moves. Pure: the dialog hands it web/CHANGELOG.md's text.

export interface ReleaseTitles { added: string[]; changed: string[] }

/** The entry titles under "Added" and "Changed" of [release]'s section, in the changelog's order; null when it has none. */
export function releaseTitles(changelog: string, release: string): ReleaseTitles | null {
  const n = /^slice-(\d+)$/.exec(release)?.[1]
  if (!n) return null
  const lines = changelog.split('\n')
  const start = lines.findIndex((l) => l.startsWith('## ') && l.trimEnd().endsWith(`(slice ${n})`))
  if (start < 0) return null
  const out: ReleaseTitles = { added: [], changed: [] }
  let into: string[] | null = null
  for (const line of lines.slice(start + 1)) {
    if (line.startsWith('## ')) break                                 // the next section
    const h = /^### (\w+)/.exec(line)
    if (h) { into = h[1] === 'Added' ? out.added : h[1] === 'Changed' ? out.changed : null; continue }
    const m = /^- \*\*(.+?)\*\*/.exec(line)
    if (m && into) into.push(m[1])
  }
  return out
}
