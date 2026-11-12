// Real-browser check of Sapphire's layout at phone width (the bottom navigation bar below md, 768 px): a production build
// in a disposable Chromium profile per viewport; one fixture drive imported through the Import panel; every .png and
// web-font request aborted (no tiles or fonts needed; deterministic text metrics). The Trip panel is then measured.
// At 375 × 812: no horizontal page or panel scroll, the navigation is not a column beside the content, the map is at least
// 300 px wide, its attribution at most two lines, the colour-mode buttons inside the map, all seven panels reachable.
// At 768 × 1024 (tablet) and 1280 × 800 the element rects and a screenshot are written, to compare two builds.
// Playwright is not a project dependency — install it anywhere outside the repo (as for scripts/idb-check):
//   T=$(mktemp -d) && (cd $T && npm i playwright@1.48.2 && PLAYWRIGHT_BROWSERS_PATH=$T/b npx playwright install chromium)
//   node_modules/.bin/vite build --outDir $T/dist                                                     # from web/
//   PLAYWRIGHT_BROWSERS_PATH=$T/b PLAYWRIGHT_MODULE=$T/node_modules/playwright/index.mjs \
//     node scripts/layout-check/check.mjs $T/dist src/lib/__fixtures__/android-export-rc7/release-tip-can-drive.zip \
//     $T/profiles $T/out                                    # writes $T/out-rects.json and $T/out-trip-<w>x<h>.png
// Exit 0 = every check passed.
import http from 'node:http'
import { readFile, writeFile } from 'node:fs/promises'
import { join, extname } from 'node:path'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const [dist, zip, profiles, out] = process.argv.slice(2)
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
const results = {}; let failures = 0
const check = (ok, what) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}`); if (!ok) failures++ }
for (const [w, h] of [[375, 812], [768, 1024], [1280, 800]]) {
  const ctx = await chromium.launchPersistentContext(join(profiles, `${w}x${h}`), { headless: true, viewport: { width: w, height: h } })
  const page = ctx.pages()[0] ?? await ctx.newPage()
  const errors = []; page.on('pageerror', (e) => errors.push(e.message))
  await page.route('**/*.png', (r) => r.abort())
  await page.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.abort())   // deterministic text metrics across runs
  await page.goto(origin + '/openRS_/')
  await page.evaluate(() => localStorage.setItem('sapphire_view', JSON.stringify({ activePanel: 'import', activeSessionId: null })))
  await page.reload()
  await page.waitForSelector('#file-input', { state: 'attached', timeout: 15000 })
  await page.setInputFiles('#file-input', zip)
  await page.waitForFunction(() => /Imported/.test(document.body.innerText), null, { timeout: 15000 })
  await page.waitForTimeout(1500)
  const id = await page.evaluate(() => JSON.parse(localStorage.getItem('sapphire_view') ?? '{}').activeSessionId)
  await page.evaluate((sid) => localStorage.setItem('sapphire_view', JSON.stringify({ activePanel: 'trip', activeSessionId: sid })), id)
  await page.reload()
  await page.waitForSelector('.leaflet-control-attribution', { timeout: 15000 })
  await page.waitForTimeout(1500)
  const r = await page.evaluate(() => {
    const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return [Math.round(b.left), Math.round(b.top), Math.round(b.width), Math.round(b.height)] }
    const mapEl = document.querySelector('.leaflet-container'); const wrap = mapEl.parentElement
    const nav = document.querySelector('nav'), main = document.querySelector('main'), header = document.querySelector('header')
    const attr = document.querySelector('.leaflet-control-attribution')
    const lh = parseFloat(getComputedStyle(attr).lineHeight) || 16.8
    return {
      docScroll: document.documentElement.scrollWidth - window.innerWidth,
      mainScroll: main.scrollWidth - main.clientWidth,
      nav: box(nav), header: box(header), main: box(main), map: box(mapEl),
      selector: box(wrap.children[1]), legend: box(wrap.children[2]), attribution: box(attr),
      attributionLines: Math.round(attr.getBoundingClientRect().height / lh),
      navItems: [...nav.querySelectorAll('button')].filter((b) => b.offsetParent !== null).length,
      title: header?.textContent?.replace(/\s+/g, ' ').trim(),
    }
  })
  r.pageErrors = errors
  results[`${w}x${h}`] = r
  await page.screenshot({ path: `${out}-trip-${w}x${h}.png` })
  if (w === 375) {
    const [nl, nt, nw, nh] = r.nav, [ml, mt, mw] = r.main
    const navBeside = nh > nw && nl + nw <= ml + 1
    check(r.docScroll <= 0, `375: no horizontal page scroll (document overflow ${r.docScroll} px)`)
    check(r.mainScroll <= 0, `375: no horizontal scroll in the panel (main overflow ${r.mainScroll} px)`)
    check(!navBeside, `375: the navigation is not a column beside the content (nav ${r.nav}, main ${r.main})`)
    check(r.map[2] >= 300, `375: the map is at least 300 px wide (${r.map[2]} px)`)
    check(r.attributionLines <= 2, `375: the attribution is at most two lines (${r.attributionLines})`)
    const [sl, , sw] = r.selector, [pl, , pw] = r.map
    check(sl >= pl && sl + sw <= pl + pw, `375: the colour-mode buttons stay inside the map (buttons ${r.selector}, map ${r.map})`)
    check(r.navItems >= 7, `375: all seven panels are reachable from the navigation (${r.navItems} buttons)`)
    check(errors.length === 0, `375: no page errors (${errors.length})`)
  } else {
    check(errors.length === 0, `${w}: no page errors (${errors.length})`)
  }
  await ctx.close()
}
server.close()
await writeFile(`${out}-rects.json`, JSON.stringify(results, null, 1))
console.log(failures === 0 ? 'ALL PASS' : `FAILURES: ${failures}`)
process.exit(failures === 0 ? 0 : 1)
