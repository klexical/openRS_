// Real-browser check of Sapphire's storage module (src/lib/storage.ts, #384) against IndexedDB in disposable Chromium
// profiles, one per scenario:
//   1. a batch survives reload + browser restart; a quota failure (async, after earlier writes succeeded) and a clone
//      failure (put throws) store nothing, pre-existing records unchanged (the #204 batch rule, now on storeImported);
//   2. the upgrade from version 1: three sessions open as version 2 with every field, point and event, same ids;
//   3. the failed upgrade: a record that cannot convert leaves version 1 exactly as it was;
//   4. two tabs: an old tab holding version 1 blocks the upgrade until it closes; a tab on version 2 closes when a newer
//      version asks and reports "outdated".
// Invented data only (and the repository's own invented export fixtures).
// Playwright is not a project dependency — install it anywhere outside the repo:
//   T=$(mktemp -d) && (cd $T && npm i playwright@1.48.2 && PLAYWRIGHT_BROWSERS_PATH=$T/b npx playwright install chromium)
//   IDB_CHECK_OUT=$T/dist node_modules/.bin/vite build --config scripts/idb-check/vite.config.mjs      # from web/
//   PLAYWRIGHT_BROWSERS_PATH=$T/b PLAYWRIGHT_MODULE=$T/node_modules/playwright/index.mjs \
//     node scripts/idb-check/check.mjs $T/dist $T/profiles                                             # fresh folder
import http from 'node:http'
import { readFile } from 'node:fs/promises'
import { join, extname } from 'node:path'

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')

const [dist, profiles] = process.argv.slice(2)
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

async function newPage(ctx) {
  const page = await ctx.newPage()
  page.on('pageerror', (e) => console.log('pageerror:', e.message))
  await page.goto(origin + '/')
  await page.waitForFunction(() => window.ready === true)
  return page
}
async function open(name) {
  const ctx = await chromium.launchPersistentContext(join(profiles, name), { headless: true })
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
      r.addEventListener('success', () => window.idbLog.push(`put ok ${this.name} ${v.id}`))
      return r
    }
  })
  return { ctx, page }
}

const T0 = 4_000_000_000_000   // an invented time (2096)
const pre = (id, name, lat = 10) => ({ id, name, importedAt: T0, tags: [],
  trip: { points: [0, 1].map((i) => ({ ts: T0 + i * 1000, lat: lat + i / 1000, lng: 20, speedKph: 40, rpm: 2000 })),
    summary: { distanceKm: 1, durationMs: 1000, peakRpm: 2000, peakSpeedKph: 40, peakEvents: [], modeBreakdown: {} } },
  diagnostics: null, meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', generatedAt: '' } })
const dump = (page) => page.evaluate(async () =>
  (await window.sapphire.listDrives())
    .map((d) => JSON.stringify({ id: d.id, name: d.name, points: d.pointCount, peakRpm: d.summary?.peakRpm ?? null }))
    .sort())

// ── 1. A batch survives reload and restart; a failed batch stores nothing ──
let { ctx, page } = await open('1-batch')
check((await dump(page)).length === 0, 'batch: fresh profile, database empty')
await page.evaluate(async ([a, b]) => { await window.sapphire.storeImported([a]); await window.sapphire.storeImported([b]) },
  [pre('pre-1', 'Earlier drive', 10), pre('pre-2', 'Other drive', 11)])
const batchIds = await page.evaluate(async () => {
  const blob = await (await fetch('./batch-typed-manifest.zip')).blob()
  const sessions = await window.sapphire.importBatchZip(new File([blob], 'batch.zip'))
  sessions.forEach((s, i) => { s.id = `batch-${i}` })
  const out = await window.sapphire.storeImported(sessions)
  return out.stored.map((s) => s.id)
})
await page.reload(); await page.waitForFunction(() => window.ready === true)
const afterReload = await dump(page)
await ctx.close()
;({ ctx, page } = await open('1-batch'))
const afterRestart = await dump(page)
console.log('  after reload :', afterReload.join(' | '))
check(afterReload.length === 4 && batchIds.length === 2 && batchIds.every((id) => afterReload.some((r) => r.includes(`"id":"${id}"`))), 'batch: survives a page reload')
check(JSON.stringify(afterRestart) === JSON.stringify(afterReload), 'batch: survives a browser restart (same records)')
check(afterRestart.some((r) => r.includes('"peakRpm":6700')) && afterRestart.some((r) => r.includes('"peakRpm":6800')), 'batch: persisted drives keep their profile peaks (6700 / 6800)')
const baseline = afterRestart

const cdp = await ctx.newCDPSession(page)
const usage = await page.evaluate(async () => (await navigator.storage.estimate()).usage)
await cdp.send('Storage.overrideQuotaForOrigin', { origin, quotaSize: usage + 256 * 1024 })
const asyncResult = await page.evaluate(async ([p1]) => {
  window.idbLog = []
  const big = { ...p1, id: 'q-big', name: 'too big', big: 'x'.repeat(8 * 1024 * 1024) }
  try {
    await window.sapphire.storeImported([{ ...p1, id: 'q-small', name: 'small', trip: { ...p1.trip, points: p1.trip.points.map((p) => ({ ...p, lat: p.lat + 4 })) } }, big])
    return 'resolved'
  } catch (e) { return `rejected ${e.name}` }
}, [pre('pre-1', 'Earlier drive', 12)])
const asyncLog = await page.evaluate(() => window.idbLog)
await cdp.send('Storage.overrideQuotaForOrigin', { origin })
console.log('  quota failure:', asyncResult, JSON.stringify(asyncLog))
await ctx.close()
;({ ctx, page } = await open('1-batch'))
const afterAsync = await dump(page)
check(asyncResult.startsWith('rejected'), 'quota failure: storeImported rejects')
check(asyncLog.includes('put ok drives q-small'), 'quota failure: an earlier write had succeeded inside the transaction')
check(JSON.stringify(afterAsync) === JSON.stringify(baseline), 'quota failure: after reopening, records identical to before (no q-small / q-big)')

const syncResult = await page.evaluate(async ([p2]) => {
  try {
    await window.sapphire.storeImported([{ ...p2, id: 'c-ok', name: 'ok' }, { ...p2, id: 'c-bad', fn: () => 1, trip: { ...p2.trip, points: p2.trip.points.map((p) => ({ ...p, lat: p.lat + 5 })) } }])
    return 'resolved'
  } catch (e) { return `rejected ${e.name}` }
}, [pre('pre-2', 'Other drive', 13)])
await page.waitForTimeout(300)
await ctx.close()
;({ ctx, page } = await open('1-batch'))
const afterSync = await dump(page)
check(syncResult === 'rejected DataCloneError', 'clone failure: storeImported rejects with the clone error')
check(JSON.stringify(afterSync) === JSON.stringify(baseline), 'clone failure: after reopening, records identical to before (no c-ok)')
await ctx.close()

// ── 2. The upgrade from version 1 ──
;({ ctx, page } = await open('2-upgrade'))
const v1 = [
  { ...pre('both', 'Drive and session', 20), diagnostics: { canInventory: [], fpsTimeline: [], sessionEvents: [{ ts: 1, type: 'STATE', message: 'invented' }], decodeTrace: [], probeResults: [], dtcResults: [] }, futureField: { kept: [1, 2] } },
  pre('drive-only', 'Drive alone', 21),
  { ...pre('session-only', 'Session alone'), trip: null, diagnostics: { canInventory: [], fpsTimeline: [], sessionEvents: [{ ts: 2, type: 'SESSION', message: 'invented' }], decodeTrace: [], probeResults: [], dtcResults: [] } },
]
await page.evaluate((s) => window.sapphire.writeV1(s), v1)
const up = await page.evaluate(async (orig) => {
  const drives = await window.sapphire.listDrives()
  const loaded = await Promise.all(orig.map((s) => window.sapphire.loadDrive(s.id)))
  // key order is not content: compare with sorted keys
  const canon = (v) => JSON.stringify(v, (_, x) => (x && typeof x === 'object' && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort(([p], [q]) => p.localeCompare(q))) : x))
  return { ids: drives.map((d) => d.id).sort(), same: loaded.map((l, i) => canon(l) === canon(orig[i])), status: window.sapphire.storageStatus().state }
}, v1)
await page.reload(); await page.waitForFunction(() => window.ready === true)
const raw2 = await page.evaluate(() => window.sapphire.readRaw())
check(up.status === 'ready' && JSON.stringify(up.ids) === JSON.stringify(['both', 'drive-only', 'session-only']), 'upgrade: the three version-1 sessions are listed in version 2 with the same ids')
check(up.same.every(Boolean), 'upgrade: every field, point and event reads back as stored (a field this slice does not show included)')
check(raw2.version === 2 && JSON.stringify(raw2.stores.sort()) === JSON.stringify(['diagnostics', 'drives', 'points']), 'upgrade: version 2 with three stores; the old store is gone')
await ctx.close()

// ── 3. The failed upgrade ──
;({ ctx, page } = await open('3-failed-upgrade'))
const bad = [pre('good', 'Good', 30), { ...pre('bad', 'Bad', 31), trip: { ...pre('bad', 'Bad').trip, points: 'not an array' } }]
await page.evaluate((s) => window.sapphire.writeV1(s), bad)
const failed = await page.evaluate(async () => {
  try { await window.sapphire.listDrives(); return { rejected: false } } catch { return { rejected: true, status: window.sapphire.storageStatus() } }
})
await page.reload(); await page.waitForFunction(() => window.ready === true)
const raw3 = await page.evaluate(() => window.sapphire.readRaw())
check(failed.rejected && failed.status.state === 'upgrade-failed' && /couldn't update/.test(failed.status.reason), 'failed upgrade: rejected, status "upgrade-failed" in plain words')
check(raw3.version === 1 && JSON.stringify(raw3.stores) === JSON.stringify(['sessions']), 'failed upgrade: still version 1, only the old store')
check(JSON.stringify([...raw3.records.sessions].sort((a, b) => a.id.localeCompare(b.id))) === JSON.stringify([...bad].sort((a, b) => a.id.localeCompare(b.id))), 'failed upgrade: version 1 records exactly as they were')
await ctx.close()

// ── 4. Two tabs ──
;({ ctx, page } = await open('4-two-tabs'))
await page.evaluate((s) => window.sapphire.writeV1(s), [pre('held', 'Held', 40)])
await page.evaluate(() => window.sapphire.holdOpen(1))                  // tab A: an old Sapphire, never closes on request
const tabB = await newPage(ctx)
await tabB.evaluate(() => { window.listing = window.sapphire.listDrives().then((d) => d.map((x) => x.id)) })
await tabB.waitForFunction(() => window.sapphire.storageStatus().state === 'blocked', null, { timeout: 5000 }).catch(() => {})
const whileBlocked = await tabB.evaluate(() => window.sapphire.storageStatus().state)
await page.evaluate(() => window.sapphire.release())                   // tab A closes
const listedAfter = await tabB.evaluate(() => window.listing)
const statusAfter = await tabB.evaluate(() => window.sapphire.storageStatus().state)
check(whileBlocked === 'blocked', 'two tabs: the new tab reports "blocked" while the old tab holds version 1')
check(JSON.stringify(listedAfter) === JSON.stringify(['held']) && statusAfter === 'ready', 'two tabs: the upgrade finishes by itself once the old tab closes')
// tab B now holds version 2; tab A asks for a newer version (another Sapphire after this one)
const newer = await page.evaluate(() => window.sapphire.holdOpen(3))
await tabB.waitForFunction(() => window.sapphire.storageStatus().state === 'outdated', null, { timeout: 5000 }).catch(() => {})
const oldTabState = await tabB.evaluate(() => window.sapphire.storageStatus().state)
check(newer === 3 && oldTabState === 'outdated', 'two tabs: a tab on version 2 closes its connection when a newer version asks, and reports "outdated"')
await page.evaluate(() => window.sapphire.release())
await ctx.close()

// ── 5. The upgrade with no free quota (observation): Chromium charges the move into version 2 at its net size ──
// Observed 2026-11-14: with the quota set below the current usage, Chromium still completes the upgrade, while the same
// override stops an ordinary write (scenario 1). The old store is deleted in the same transaction, so the move is
// about neutral at commit. The failure path itself (any failed open → "upgrade-failed") is tested in
// src/lib/storage.test.ts with a database newer than this Sapphire.
;({ ctx, page } = await open('5-upgrade-quota'))
await page.evaluate(() => window.sapphire.writeInventedV1(3, 3600))
const cdp5 = await ctx.newCDPSession(page)
const usage5 = await page.evaluate(async () => (await navigator.storage.estimate()).usage)
await cdp5.send('Storage.overrideQuotaForOrigin', { origin, quotaSize: Math.max(0, usage5 - 64 * 1024) })
const quota5 = await page.evaluate(async () => {
  try { const d = await window.sapphire.listDrives(); return { rejected: false, drives: d.length } } catch { return { rejected: true, status: window.sapphire.storageStatus() } }
})
await cdp5.send('Storage.overrideQuotaForOrigin', { origin })
await page.reload(); await page.waitForFunction(() => window.ready === true)
const raw5 = await page.evaluate(() => window.sapphire.readRaw())
console.log('  no free quota, observed:', JSON.stringify(quota5), 'then version', raw5.version, 'points records', raw5.records.points?.length ?? 0)
check(quota5.rejected ? (quota5.status.state === 'upgrade-failed' && raw5.version === 1) : (raw5.version === 2 && raw5.records.points.length === 3 && raw5.records.points.every((p) => p.trip.points.length === 3600)),
  'no free quota: the upgrade either completes with every point, or fails in plain words leaving version 1')
await ctx.close()

// ── 6. Timing (not a pass / fail): the one-time upgrade of 50 one-hour drives, and the list read after it ──
for (const [label, rate] of [['desktop', 1], ['CPU slowed 4x (emulation)', 4]]) {
  ;({ ctx, page } = await open(`6-timing-${rate}`))
  await page.evaluate(() => window.sapphire.writeInventedV1(50, 3600))
  const cdp6 = await ctx.newCDPSession(page)
  await page.reload(); await page.waitForFunction(() => window.ready === true)
  if (rate > 1) await cdp6.send('Emulation.setCPUThrottlingRate', { rate })
  const t = await page.evaluate(async () => {
    const t0 = performance.now(); const d = await window.sapphire.listDrives(); const t1 = performance.now()
    window.sapphire.closeStorage()
    const t2 = performance.now(); const d2 = await window.sapphire.listDrives(); const t3 = performance.now()
    return { drives: d.length, upgradeMs: Math.round(t1 - t0), listedAfter: d2.length, listMs: Math.round(t3 - t2) }
  })
  console.log(`  timing, ${label}: upgrade of ${t.drives} one-hour drives ${t.upgradeMs} ms; list of ${t.listedAfter} after it (fresh connection) ${t.listMs} ms`)
  check(t.drives === 50 && t.listedAfter === 50, `timing, ${label}: all 50 drives upgraded and listed`)
  await ctx.close()
}

server.close()
console.log(failures ? `${failures} FAILED` : 'ALL PASSED')
process.exit(failures ? 1 : 0)
