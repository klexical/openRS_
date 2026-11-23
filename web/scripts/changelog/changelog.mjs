// changelog.mjs — web/CHANGELOG.md's fragments (web/changelog.d/README.md): check, print or fold them, with the same
// code the build folds them with (src/lib/changelogFragments.ts, plain erasable TypeScript, run by Node's own type
// stripping: Node 22.18 or later).
//   node scripts/changelog/changelog.mjs check   every fragment well formed
//   node scripts/changelog/changelog.mjs print   the changelog with the fragments folded in, to stdout; no file changed
//   node scripts/changelog/changelog.mjs fold    writes that changelog and deletes the fragments (a slice's last delivery)
// Exit status: 0 good, 1 a refusal (each reason on its own line), 2 a usage error.
import { readdirSync, readFileSync, rmdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { foldFragments, readFragments } from '../../src/lib/changelogFragments.ts'

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const DIR = join(WEB, 'changelog.d')
const FILE = join(WEB, 'CHANGELOG.md')
const cmd = process.argv[2]
if (process.argv.length !== 3 || !['check', 'print', 'fold'].includes(cmd)) {
  console.error('usage: node scripts/changelog/changelog.mjs check | print | fold')
  process.exit(2)
}

const files = {}
const stray = []
for (const name of readdirSync(DIR)) {
  const p = join(DIR, name)
  if (!statSync(p).isDirectory()) { if (name !== 'README.md') stray.push(`changelog.d/${name}: not a section folder or README.md`); continue }
  for (const fn of readdirSync(p)) files[`changelog.d/${name}/${fn}`] = readFileSync(join(p, fn), 'utf8')
}
const { fragments, problems } = readFragments(files)
problems.unshift(...stray)
let out
try { out = foldFragments(readFileSync(FILE, 'utf8'), fragments) } catch (e) { problems.push(String(e.message)) }
if (problems.length) {
  for (const p of problems) console.log(`refused: ${p}`)
  process.exit(1)
}
if (cmd === 'check') console.log(`changelog: ${fragments.length} fragment(s), all well formed`)
else if (cmd === 'print') process.stdout.write(out)
else {
  writeFileSync(FILE, out)
  for (const f of fragments) rmSync(join(WEB, f.path))
  for (const name of readdirSync(DIR)) {
    const p = join(DIR, name)
    if (statSync(p).isDirectory() && readdirSync(p).length === 0) rmdirSync(p)
  }
  console.log(`changelog: folded ${fragments.length} fragment(s) into CHANGELOG.md`)
}
