// Real-browser check of the route map when its background does not load (#384 ticket 09; owner, 2026-11-14): Chromium
// on a production build, an invented fixture drive with GPS imported through the list's Import button.
// Playwright is not a project dependency — install it anywhere outside the repo (as for scripts/idb-check):
//   T=$(mktemp -d) && (cd $T && npm i playwright@1.48.2 && PLAYWRIGHT_BROWSERS_PATH=$T/b npx playwright install chromium)
//   node_modules/.bin/vite build --outDir $T/dist                                                     # from web/
//   PLAYWRIGHT_BROWSERS_PATH=$T/b PLAYWRIGHT_MODULE=$T/node_modules/playwright/index.mjs \
//     node scripts/map-check/check.mjs $T/dist src/lib/__fixtures__/android-exports/drive-gps-companions.zip
// Exit 0 = every check passed. Count checks with `grep -c '^PASS'`.
// Ticket 09, "when the map background does not load", in a real browser (Chromium) on a production build. An invented
// fixture drive with GPS is imported; its Dashboard's route map is opened twice:
//   1. every tile request fails: the map says "Map background unavailable", the route is still drawn, and Trip's charts draw;
//   2. tiles are served (a 1×1 PNG from this script, so no map server is asked): the line is not shown.
// No request leaves this machine: everything not from the local server is answered here or aborted.
//   PLAYWRIGHT_MODULE=… node map-09.mjs <dist> <zip with GPS>
import http from 'node:http'
import { readFile } from 'node:fs/promises'
import { join, extname } from 'node:path'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const [dist, zip] = process.argv.slice(2)
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
const server = http.createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://x').pathname.replace(/^\/openRS_/, '').replace(/\/$/, '/index.html')
    const p = join(dist, decodeURIComponent(path))
    res.writeHead(200, { 'content-type': types[extname(p)] ?? 'application/octet-stream' }).end(await readFile(p))
  } catch { res.writeHead(404).end() }
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const origin = `http://127.0.0.1:${server.address().port}`
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')
let failures = 0
const check = (ok, what) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}`); if (!ok) failures++ }
const browser = await chromium.launch()
for (const tiles of ['failing', 'served']) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  const page = await ctx.newPage()
  const errors = []; page.on('pageerror', (e) => errors.push(String(e)))
  let tileAsks = 0
  await page.route((url) => !url.href.startsWith(origin), (r) => {
    if (/tile\.openstreetmap\.org/.test(r.request().url())) {
      tileAsks++
      return tiles === 'failing' ? r.abort() : r.fulfill({ status: 200, contentType: 'image/png', body: PNG })
    }
    return r.abort()
  })
  await page.goto(origin + '/openRS_/')
  await page.waitForSelector('text=No drives stored in this browser yet.', { timeout: 15000 })
  await page.getByRole('button', { name: 'Import' }).click()
  await page.setInputFiles('#file-input', zip)
  await page.waitForFunction(() => location.hash.startsWith('#/drive/'), null, { timeout: 60000 })
  await page.locator('.leaflet-container').waitFor({ timeout: 15000 })
  await page.waitForTimeout(2500)
  const line = await page.getByText('Map background unavailable').count()
  const route = await page.locator('.leaflet-overlay-pane path').count()
  if (tiles === 'failing') {
    check(tileAsks > 0, `tiles failing: tiles were asked for (${tileAsks}) and failed`)
    check(line === 1, 'tiles failing: the map says "Map background unavailable"')
    check(route > 0, `tiles failing: the route is drawn (${route} paths)`)
    await page.getByRole('button', { name: 'Trip' }).click()
    await page.waitForTimeout(1500)
    const charts = await page.locator('.recharts-surface').count()
    check(charts > 0, `tiles failing: Trip's charts draw (${charts})`)
  } else {
    check(tileAsks > 0, `tiles served: tiles were asked for (${tileAsks}) and served here`)
    check(line === 0, 'tiles served: no "Map background unavailable"')
    check(route > 0, `tiles served: the route is drawn (${route} paths)`)
  }
  check(errors.length === 0, `tiles ${tiles}: no page errors (${errors.length})`)
  await ctx.close()
}
await browser.close(); server.close()
console.log(failures ? `${failures} FAILED` : 'ALL PASSED'); process.exit(failures ? 1 : 0)
