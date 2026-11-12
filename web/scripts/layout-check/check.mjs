// Real-browser check of Sapphire's layout at phone width (#344: the bottom navigation bar below md, 768 px): a production
// build in a disposable Chromium profile per viewport; fixture drives imported through the Import panel; every .png and
// web-font request aborted (no tiles or fonts needed; deterministic text metrics). The three panels that show the route
// map (Dashboard, Trip, Compare with every imported drive) are measured and screenshotted at every viewport.
// At 375 × 812, on each panel: no horizontal page or panel scroll, no element reaching past the screen edge, the
// navigation is not a column beside the content, every map at least 300 px wide with its attribution at most two lines
// and its colour-mode buttons inside it; Compare with three or more drives; all seven panels reachable from the navigation.
// At 768 × 1024 (tablet) and 1280 × 800 the element rects are written, to compare two builds.
// Playwright is not a project dependency — install it anywhere outside the repo (as for scripts/idb-check):
//   T=$(mktemp -d) && (cd $T && npm i playwright@1.48.2 && PLAYWRIGHT_BROWSERS_PATH=$T/b npx playwright install chromium)
//   node_modules/.bin/vite build --outDir $T/dist                                                     # from web/
//   F=src/lib/__fixtures__/android-export-rc7
//   PLAYWRIGHT_BROWSERS_PATH=$T/b PLAYWRIGHT_MODULE=$T/node_modules/playwright/index.mjs \
//     node scripts/layout-check/check.mjs $T/dist \
//       $F/release-tip-can-drive.zip,$F/release-pressure-gaps-drive.zip,src/lib/__fixtures__/android-exports/drive-gps-companions.zip \
//     $T/profiles $T/out                    # writes $T/out-rects.json and $T/out-<panel>-<w>x<h>.png
// Exit 0 = every check passed.
import http from 'node:http'
import { readFile, writeFile } from 'node:fs/promises'
import { join, extname } from 'node:path'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const [dist, zipList, profiles, out] = process.argv.slice(2)
const zips = zipList.split(',')
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

const measure = (page) => page.evaluate(() => {
  const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return [Math.round(b.left), Math.round(b.top), Math.round(b.width), Math.round(b.height)] }
  const nav = document.querySelector('nav'), main = document.querySelector('main'), header = document.querySelector('header')
  const maps = [...document.querySelectorAll('.leaflet-container')].map((mapEl) => {
    const wrap = mapEl.parentElement
    const attr = mapEl.querySelector('.leaflet-control-attribution')
    const lh = attr ? (parseFloat(getComputedStyle(attr).lineHeight) || 16.8) : 16.8
    return { map: box(mapEl), selector: box(wrap.children[1]), legend: box(wrap.children[2]), attribution: box(attr),
      attributionLines: attr ? Math.round(attr.getBoundingClientRect().height / lh) : null }
  })
  // Visible elements in the panel (outside the Leaflet panes and controls, which the map clips) that reach past the
  // viewport: the root is overflow-hidden, so such content is cut off without any scroll to show it (QA's probe, #344).
  // Leaf elements only (a group's box is its children's). Accepted exception (#344 review, N1): Recharts text up to 2 px
  // past the edge — a chart's peak label drawn just right of its plot; moving it would move every desktop chart.
  const clipped = [], accepted = []
  for (const el of main.querySelectorAll('*')) {
    if (el.closest('.leaflet-pane, .leaflet-control-container') || el.children.length) continue
    const b = el.getBoundingClientRect(); if (!b.width || !b.height) continue
    const past = Math.max(b.right - window.innerWidth, -b.left)
    if (past <= 0.5) continue
    const list = el.closest('.recharts-wrapper') && /^(text|tspan)$/i.test(el.tagName) && past <= 2.5 ? accepted : clipped
    list.push(`${el.tagName.toLowerCase()}.${String(el.className?.baseVal ?? el.className ?? '').split(' ')[0]} ${Math.round(b.left)}..${Math.round(b.right)} "${(el.textContent ?? '').trim().slice(0, 24)}"`)
  }
  return {
    clipped, accepted,
    docScroll: document.documentElement.scrollWidth - window.innerWidth,
    mainScroll: main.scrollWidth - main.clientWidth,
    nav: box(nav), header: box(header), main: box(main), maps,
    navItems: [...nav.querySelectorAll('button')].filter((b) => b.offsetParent !== null).length,
  }
})

async function show(page, panel, sessionId) {
  await page.evaluate(([p, id]) => localStorage.setItem('sapphire_view', JSON.stringify({ activePanel: p, activeSessionId: id })), [panel, sessionId])
  await page.reload()
  await page.waitForSelector('main', { timeout: 15000 })
  await page.waitForTimeout(800)
}

for (const [w, h] of [[375, 812], [768, 1024], [1280, 800]]) {
  const vp = `${w}x${h}`
  const ctx = await chromium.launchPersistentContext(join(profiles, vp), { headless: true, viewport: { width: w, height: h } })
  const page = ctx.pages()[0] ?? await ctx.newPage()
  const errors = []; page.on('pageerror', (e) => errors.push(e.message))
  await page.route('**/*.png', (r) => r.abort())
  await page.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.abort())
  await page.goto(origin + '/openRS_/')
  for (const zip of zips) {
    await show(page, 'import', null)
    await page.waitForSelector('#file-input', { state: 'attached', timeout: 15000 })
    await page.setInputFiles('#file-input', zip)
    await page.waitForFunction(() => /Imported/.test(document.body.innerText), null, { timeout: 15000 })
    await page.waitForTimeout(1500)
  }
  const sessionId = await page.evaluate(() => JSON.parse(localStorage.getItem('sapphire_view') ?? '{}').activeSessionId)
  results[vp] = {}
  for (const panel of ['dashboard', 'trip', 'compare']) {
    await show(page, panel, sessionId)
    if (panel === 'compare') {
      // Select every imported drive in the session picker (the buttons above the comparison), in name order
      const names = await page.evaluate(() => [...document.querySelectorAll('main button')]
        .map((b) => b.textContent ?? '').filter((t) => /Trip —/.test(t)).sort())   // name order: the same base every run
      for (const n of names) await page.locator('main button', { hasText: n.slice(0, 40) }).first().click()
    }
    await page.waitForSelector('.leaflet-container', { timeout: 15000 }).catch(() => {})
    await page.waitForTimeout(1200)
    const r = await measure(page)
    results[vp][panel] = r
    await page.screenshot({ path: `${out}-${panel}-${vp}.png` })
    if (w === 375) {
      const [nl, , nw, nh] = r.nav, [ml] = r.main
      check(r.docScroll <= 0, `375 ${panel}: no horizontal page scroll (document overflow ${r.docScroll} px)`)
      check(r.mainScroll <= 0, `375 ${panel}: no horizontal scroll in the panel (main overflow ${r.mainScroll} px)`)
      check(r.clipped.length === 0, `375 ${panel}: nothing reaches past the screen edge (${r.clipped.length}: ${r.clipped.slice(0, 4).join(', ')})`)
      if (r.accepted.length) console.log(`NOTE  375 ${panel}: accepted (N1, chart text ≤ 2 px past the edge): ${r.accepted.join(', ')}`)
      check(!(nh > nw && nl + nw <= ml + 1), `375 ${panel}: the navigation is not a column beside the content (nav ${r.nav}, main ${r.main})`)
      check(r.maps.length > 0, `375 ${panel}: a route map is shown (${r.maps.length})`)
      r.maps.forEach((m, i) => {
        check(m.map[2] >= 300, `375 ${panel} map ${i + 1}: at least 300 px wide (${m.map[2]} px)`)
        check(m.attributionLines !== null && m.attributionLines <= 2, `375 ${panel} map ${i + 1}: attribution at most two lines (${m.attributionLines})`)
        const [sl, , sw] = m.selector, [pl, , pw] = m.map
        check(sl >= pl && sl + sw <= pl + pw, `375 ${panel} map ${i + 1}: colour-mode buttons inside the map (buttons ${m.selector}, map ${m.map})`)
      })
      if (panel === 'compare') check(r.maps.length >= 3, `375 compare: three or more drives compared (${r.maps.length} maps)`)
      if (panel === 'trip') check(r.navItems >= 7, `375: all seven panels are reachable from the navigation (${r.navItems} buttons)`)
    }
  }
  check(errors.length === 0, `${vp}: no page errors (${errors.length})`)
  await ctx.close()
}
server.close()
await writeFile(`${out}-rects.json`, JSON.stringify(results, null, 1))
console.log(failures === 0 ? 'ALL PASS' : `FAILURES: ${failures}`)
process.exit(failures === 0 ? 0 : 1)
