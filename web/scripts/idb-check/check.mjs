// Real-browser check of Sapphire's batch save (src/lib/db.ts putSessions) against IndexedDB in a disposable
// Chromium profile: a batch survives reload + browser restart; a quota failure (async, after earlier writes
// succeeded) and a clone failure (put throws) roll the whole batch back, pre-existing records unchanged.
// Playwright is not a project dependency — install it anywhere outside the repo:
//   T=$(mktemp -d) && (cd $T && npm i playwright@1.48.2 && PLAYWRIGHT_BROWSERS_PATH=$T/b npx playwright install chromium)
//   IDB_CHECK_OUT=$T/dist node_modules/.bin/vite build --config scripts/idb-check/vite.config.mjs      # from web/
//   PLAYWRIGHT_BROWSERS_PATH=$T/b PLAYWRIGHT_MODULE=$T/node_modules/playwright/index.mjs \
//     node scripts/idb-check/check.mjs $T/dist $T/profile                                              # fresh profile
import http from 'node:http'
import { readFile } from 'node:fs/promises'
import { join, extname } from 'node:path'

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')

const [dist, profile] = process.argv.slice(2)
const types = { '.html': 'text/html', '.js': 'text/javascript', '.zip': 'application/zip' }
const server = http.createServer(async (req, res) => {
  try {
    const path = join(dist, decodeURIComponent(new URL(req.url, 'http://x').pathname.replace(/\/$/, '/index.html')))
    res.writeHead(200, { 'content-type': types[extname(path)] ?? 'application/octet-stream' }).end(await readFile(path))
  } catch { res.writeHead(404).end() }
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const origin = `http://127.0.0.1:${server.address().port}`
let failures = 0
const check = (ok, what) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}`); if (!ok) failures++ }

async function open() {
  const ctx = await chromium.launchPersistentContext(profile, { headless: true })
  const page = ctx.pages()[0] ?? await ctx.newPage()
  page.on('pageerror', (e) => console.log('pageerror:', e.message))
  await page.goto(origin + '/')
  await page.waitForFunction(() => window.ready === true)
  // Observer only: logs put successes and transaction outcomes; production code is untouched.
  await page.evaluate(() => {
    window.idbLog = []
    const put = IDBObjectStore.prototype.put
    IDBObjectStore.prototype.put = function (v, k) {
      const r = put.call(this, v, k)
      r.addEventListener('success', () => window.idbLog.push(`put ok ${v.id}`))
      return r
    }
    const tx = IDBDatabase.prototype.transaction
    IDBDatabase.prototype.transaction = function (...a) {
      const t = tx.apply(this, a)
      if (a[1] === 'readwrite') {
        t.addEventListener('complete', () => window.idbLog.push('tx complete'))
        t.addEventListener('abort', () => window.idbLog.push(`tx abort ${t.error?.name ?? '(no error)'}`))
      }
      return t
    }
  })
  return { ctx, page }
}

const dump = (page) => page.evaluate(async () =>
  (await window.sapphire.getAllSessions())
    .map((s) => JSON.stringify({ id: s.id, name: s.name, points: s.trip?.points.length ?? 0, peakRpm: s.trip?.summary.peakRpm ?? null, big: s.big?.length ?? 0 }))
    .sort())

const pre = (id, name) => ({ id, name, importedAt: 1, tags: [], trip: null, diagnostics: null,
  meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', exportedAt: '' } })

// ── 1. Pre-existing records + a successful batch (real export through the production importer) ──
let { ctx, page } = await open()
check((await dump(page)).length === 0, 'fresh profile: database empty')
await page.evaluate(async ([a, b]) => { await window.sapphire.putSession(a); await window.sapphire.putSession(b) },
  [pre('pre-1', 'Earlier drive'), pre('pre-2', 'Other drive')])
const batchIds = await page.evaluate(async () => {
  const blob = await (await fetch('./batch-typed-manifest.zip')).blob()
  const sessions = await window.sapphire.importBatchZip(new File([blob], 'batch.zip'))
  sessions.forEach((s, i) => { s.id = `batch-${i}` })
  await window.sapphire.putSessions(sessions)
  return sessions.map((s) => s.id)
})
console.log('  batch log:', JSON.stringify(await page.evaluate(() => window.idbLog)))
await page.reload(); await page.waitForFunction(() => window.ready === true)
const afterReload = await dump(page)
await ctx.close()
;({ ctx, page } = await open())
const afterRestart = await dump(page)
console.log('  after reload :', afterReload.join(' | '))
check(afterReload.length === 4 && batchIds.every((id) => afterReload.some((r) => r.includes(`"id":"${id}"`))), 'successful batch survives a page reload')
check(JSON.stringify(afterRestart) === JSON.stringify(afterReload), 'successful batch survives a browser restart (same records)')
check(afterRestart.some((r) => r.includes('"peakRpm":6700')) && afterRestart.some((r) => r.includes('"peakRpm":6800')), 'persisted batch drives keep their profile peaks (6700 / 6800)')
const baseline = afterRestart

// ── 2. Async failure: quota makes a later, large write fail after earlier writes succeeded ──
const cdp = await ctx.newCDPSession(page)
const usage = await page.evaluate(async () => (await navigator.storage.estimate()).usage)
await cdp.send('Storage.overrideQuotaForOrigin', { origin, quotaSize: usage + 256 * 1024 })
const asyncResult = await page.evaluate(async ([p1]) => {
  window.idbLog = []
  const big = { ...p1, id: 'q-big', name: 'too big', big: 'x'.repeat(8 * 1024 * 1024) }
  try {
    await window.sapphire.putSessions([{ ...p1, name: 'CHANGED by failed batch' }, { ...p1, id: 'q-small', name: 'small' }, big])
    return 'resolved'
  } catch (e) { return `rejected ${e.name}` }
}, [pre('pre-1', 'Earlier drive')])
const asyncLog = await page.evaluate(() => window.idbLog)
await cdp.send('Storage.overrideQuotaForOrigin', { origin })
console.log('  async failure:', asyncResult, JSON.stringify(asyncLog))
await ctx.close()
;({ ctx, page } = await open())
const afterAsync = await dump(page)
check(asyncResult.startsWith('rejected'), 'quota failure: putSessions rejects')
check(asyncLog.includes('put ok pre-1') && asyncLog.includes('put ok q-small'), 'quota failure: earlier writes had succeeded inside the transaction')
check(JSON.stringify(afterAsync) === JSON.stringify(baseline), 'quota failure: after reopening, records identical to before (no q-small / q-big, pre-1 unchanged)')

// ── 3. Sync failure: a later record can't be cloned (DataCloneError thrown by put) ──
const syncResult = await page.evaluate(async ([p2]) => {
  window.idbLog = []
  try {
    await window.sapphire.putSessions([{ ...p2, name: 'CHANGED by failed batch' }, { ...p2, id: 'c-ok', name: 'ok' }, { ...p2, id: 'c-bad', fn: () => 1 }])
    return 'resolved'
  } catch (e) { return `rejected ${e.name}` }
}, [pre('pre-2', 'Other drive')])
await page.waitForTimeout(500)
const syncLog = await page.evaluate(() => window.idbLog)
console.log('  sync failure:', syncResult, JSON.stringify(syncLog))
await ctx.close()
;({ ctx, page } = await open())
const afterSync = await dump(page)
console.log('  after reopen :', afterSync.join(' | '))
check(syncResult.startsWith('rejected'), 'clone failure: putSessions rejects')
check(JSON.stringify(afterSync) === JSON.stringify(baseline), 'clone failure: after reopening, records identical to before (no c-ok, pre-2 unchanged)')

await ctx.close()
server.close()
console.log(failures ? `${failures} FAILED` : 'ALL PASSED')
process.exit(failures ? 1 : 0)
