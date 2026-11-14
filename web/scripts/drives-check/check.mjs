// Real-browser check of the list of drives and the addresses (#384 ticket 03), on a production build in a disposable
// Chromium profile per run. Invented data only: the drives are generated here (a batch export of N one-hour drives in
// 2096, 1 Hz, invented coordinates) and imported through the list's Import button. Every .png and web-font request is
// aborted (no tiles or fonts needed).
// Checks: Sapphire opens on the list; reload, back and forward follow the address; an unknown id shows the list with a
// plain message; every action of these screens by keyboard alone (Tab / Enter / Space only, no mouse); the places bar at
// the bottom on a phone and a rail on a desktop; no horizontal scroll at 375 px; how long the list takes to appear with
// the drives stored (desktop, and mobile emulation with the CPU slowed 4×: emulation, not a phone).
// Playwright is not a project dependency — install it anywhere outside the repo (as for scripts/idb-check):
//   T=$(mktemp -d) && (cd $T && npm i playwright@1.48.2 && PLAYWRIGHT_BROWSERS_PATH=$T/b npx playwright install chromium)
//   node_modules/.bin/vite build --outDir $T/dist                                                     # from web/
//   PLAYWRIGHT_BROWSERS_PATH=$T/b PLAYWRIGHT_MODULE=$T/node_modules/playwright/index.mjs \
//     node scripts/drives-check/check.mjs $T/dist $T/profiles $T/out      # writes $T/out-*.png and $T/out-timing.json
// Exit 0 = every check passed. Count checks with `grep -c '^PASS'`.
import http from 'node:http'
import { readFile, writeFile, mkdtemp } from 'node:fs/promises'
import { join, extname } from 'node:path'
import JSZip from 'jszip'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const [dist, profiles, out] = process.argv.slice(2)
const N = Number(process.env.DRIVES ?? 50)
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
const server = http.createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://x').pathname.replace(/^\/openRS_/, '').replace(/\/$/, '/index.html')
    const p = join(dist, decodeURIComponent(path))
    res.writeHead(200, { 'content-type': types[extname(p)] ?? 'application/octet-stream' }).end(await readFile(p))
  } catch { res.writeHead(404).end() }
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const origin = `${'http'}://127.0.0.1:${server.address().port}`
const home = origin + '/openRS_/'
let failures = 0
const check = (ok, what) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}`); if (!ok) failures++ }

// ── Invented drives: one batch export, N one-hour drives, one every two days from 1 Jan 2096 (local time) ──
async function batchZip(n) {
  const zip = new JSZip()
  const files = []
  for (let i = 0; i < n; i++) {
    const start = new Date(2096, 0, 1 + 2 * i, 9, 0).getTime()
    const rows = ['# openrs_csv_v2', 'timestamp_ms,lat,lng,speed_kph,rpm']
    for (let s = 0; s < 3600; s++) rows.push(`${start + s * 1000},${(10 + i / 100 + s / 1e5).toFixed(6)},${(20 + s / 1e5).toFixed(6)},${60 + (s % 40)},${2500 + (s % 900)}`)
    const name = `drive_${i + 1}_${new Date(start).toISOString().slice(0, 10).replaceAll('-', '')}_090000.csv`
    zip.file(name, rows.join('\n'))
    files.push({ name, type: 'drive_csv' })
  }
  zip.file('manifest.json', JSON.stringify({ files }))
  const dir = await mkdtemp(join(profiles, 'zip-'))
  const path = join(dir, 'invented-batch.zip')
  await writeFile(path, await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }))
  return path
}

async function open(name, viewport, extra = {}) {
  const ctx = await chromium.launchPersistentContext(join(profiles, name), { headless: true, viewport, ...extra })
  const page = ctx.pages()[0] ?? await ctx.newPage()
  const errors = []; page.on('pageerror', (e) => errors.push(e.message))
  await page.route('**/*.png', (r) => r.abort())
  await page.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.abort())
  return { ctx, page, errors }
}
const rows = (page) => page.locator('.a-row').count()
const h1 = (page) => page.locator('main h1').first().innerText()
const settle = (page) => page.waitForTimeout(400)

// ── Timing: the list with N drives, after a full load (desktop) ──
// A script added before the page's own records, on the page's clock, when the last row appears (a MutationObserver);
// read after a full navigation, so the clock starts at that navigation (a hash change alone would not restart it).
const ROWS_AT = (n) => {
  const seen = () => { if (document.querySelectorAll('.a-row').length >= n && !window.__rowsAt) window.__rowsAt = performance.now() }
  new MutationObserver(seen).observe(document, { childList: true, subtree: true })
}
const zipPath = await batchZip(N)
const { ctx, page, errors } = await open('desktop', { width: 1280, height: 800 })
await ctx.addInitScript(ROWS_AT, N)

// ── Empty, then import by the list's button ──
await page.goto(home)
await page.waitForSelector('text=No drives stored in this browser yet.', { timeout: 15000 })
check(await page.evaluate(() => location.hash) === '', 'a first visit opens on the list (no address needed)')
await page.getByRole('button', { name: 'Import' }).click()
await page.waitForSelector('#file-input', { state: 'attached', timeout: 15000 })
await page.setInputFiles('#file-input', zipPath)
await page.waitForFunction(() => /Imported \d+ drives/.test(document.body.innerText), null, { timeout: 120000 })
await page.waitForSelector('.a-row', { timeout: 15000 })
check(await rows(page) === N, `the list shows the ${N} imported drives (${await rows(page)})`)

// ── Order and months ──
const headings = await page.locator('.a-month h2').allTextContents()   // the text, not its capitals on screen
const expectMonths = await page.evaluate((n) => {
  const seen = []
  for (let i = n - 1; i >= 0; i--) {
    const l = new Date(2096, 0, 1 + 2 * i).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
    if (!seen.includes(l)) seen.push(l)
  }
  return seen
}, N)
check(JSON.stringify(headings) === JSON.stringify(expectMonths), `months newest first: ${headings.join(', ')}`)
await page.screenshot({ path: `${out}-list-1280x800.png` })

async function listTime(p) {
  await p.goto(`${home}?load=${Date.now()}`)   // same origin, a new URL: a full navigation (CPU throttling stays)
  await p.waitForFunction(() => window.__rowsAt !== undefined, null, { timeout: 30000 })
  const [at, type, html, dcl] = await p.evaluate(() => {
    const n = performance.getEntriesByType('navigation')[0]
    return [Math.round(window.__rowsAt), n?.type, Math.round(n?.responseEnd ?? NaN), Math.round(n?.domContentLoadedEventEnd ?? NaN)]
  })
  if (type !== 'navigate') throw new Error(`timing: not a full navigation (${type})`)
  // the same clock's own marks beside it: the page's HTML received, and its scripts run (DOMContentLoaded)
  console.log(`NOTE  timing: html at ${html} ms, scripts run at ${dcl} ms, last row at ${at} ms`)
  return at
}
const timing = { desktop: [] }
for (let i = 0; i < 3; i++) timing.desktop.push(await listTime(page))

// ── Addresses: open by keyboard, reload, back and forward, unknown id ──
await page.goto(home)
await page.waitForSelector('.a-row')
// Tab from the top of the page until the first row's link has focus, then Enter (keyboard only)
await page.locator('body').focus()
const firstName = await page.locator('.a-row .a-name').first().innerText()
let reached = false
for (let i = 0; i < 12 && !reached; i++) {
  await page.keyboard.press('Tab')
  reached = await page.evaluate(() => document.activeElement?.classList.contains('a-name') ?? false)
}
check(reached, 'Tab reaches the first row (places, Import, then the rows)')
await page.keyboard.press('Enter')
await page.waitForFunction(() => location.hash.startsWith('#/drive/'))
await settle(page)
const driveHash = await page.evaluate(() => location.hash)
check(await h1(page) === firstName, `Enter on a row opens that drive (${driveHash.slice(0, 16)}…, "${await h1(page)}")`)
// the views of the drive by keyboard: Tab to Trip, Space
let onTrip = false
for (let i = 0; i < 10 && !onTrip; i++) {
  await page.keyboard.press('Tab')
  onTrip = await page.evaluate(() => document.activeElement?.textContent === 'Trip')
}
await page.keyboard.press('Space')
await settle(page)
check(onTrip && await page.getByRole('button', { name: 'Trip' }).getAttribute('aria-pressed') === 'true', 'Tab and Space choose the Trip view of the drive')
await page.reload()
await page.waitForFunction(() => document.querySelector('main h1')?.textContent !== 'Opening the drive…')
check(await h1(page) === firstName && await page.evaluate(() => location.hash) === driveHash, 'a reload on the drive\'s address shows that drive')
await page.goBack()
await page.waitForSelector('.a-row')
check(await page.evaluate(() => location.hash) === '', 'Back returns to the list')
await page.goForward()
await page.waitForFunction(() => location.hash.startsWith('#/drive/'))
await settle(page)
check(await h1(page) === firstName, 'Forward returns to the drive')
// Settings by keyboard: Tab to the place, Enter
await page.goto(home)
await page.waitForSelector('.a-row')
await page.locator('body').focus()
let onSettings = false
for (let i = 0; i < 6 && !onSettings; i++) {
  await page.keyboard.press('Tab')
  onSettings = await page.evaluate(() => document.activeElement?.textContent?.trim() === 'Settings')
}
await page.keyboard.press('Enter')
await page.waitForFunction(() => location.hash === '#/settings')
check(onSettings && await h1(page) === 'Settings', 'Tab and Enter open Settings from the places')
check(await page.locator('nav[aria-label="Places"] a[aria-current="page"]').innerText() === 'Settings', 'the current place is marked')
// the Import button by keyboard
await page.goto(home)
await page.waitForSelector('.a-row')
await page.getByRole('button', { name: 'Import' }).focus()
await page.keyboard.press('Enter')
check(await page.waitForSelector('#file-input', { state: 'attached', timeout: 15000 }).then(() => true, () => false), 'Enter on Import opens the import screen')
await page.goto(home + '#/drive/not-stored-here')
await page.waitForSelector('text=That drive is not stored in this browser.', { timeout: 15000 })
check(await page.evaluate(() => location.hash) === '#/' && await rows(page) === N, 'an unknown id shows the list with a plain message; the address becomes the list')
await page.goto(home + '#/drive/%E0%A4%A')
await page.waitForSelector('.a-row', { timeout: 15000 })
check(await rows(page) === N, 'a malformed address shows the list')
// ── Find a drive (#384 ticket 04): a search kept across a drive and Back; the quick filters by keyboard ──
await page.goto(home)
await page.waitForSelector('.a-row')
await page.getByRole('searchbox', { name: 'Search drives' }).focus()
await page.keyboard.type('2096-04-0')
await page.waitForFunction(() => document.querySelectorAll('.a-row').length === 4)
check(true, 'typing "2096-04-0" in the search leaves the four drives of 2 to 8 April')
await page.locator('.a-row .a-name').first().focus()
await page.keyboard.press('Enter')
await page.waitForFunction(() => location.hash.startsWith('#/drive/'))
await page.goBack()
await page.waitForSelector('.a-row')
check(await page.getByRole('searchbox', { name: 'Search drives' }).inputValue() === '2096-04-0' && await rows(page) === 4,
  `after opening a drive and Back, the search and its rows are still there (${await rows(page)} rows)`)
const laps = page.getByRole('button', { name: 'With laps' })
await laps.focus()
await page.keyboard.press('Space')
await page.waitForSelector('text=No drives match this search.')
check(await laps.getAttribute('aria-pressed') === 'true' && await rows(page) === 0, 'Space on "With laps" filters (none of the invented drives has laps) and says so')
await page.keyboard.press('Enter')
await page.waitForFunction(() => document.querySelectorAll('.a-row').length === 4)
check(await laps.getAttribute('aria-pressed') === 'false', 'Enter on "With laps" again clears the filter')
await page.getByRole('searchbox', { name: 'Search drives' }).fill('')
await page.waitForFunction((n) => document.querySelectorAll('.a-row').length === n, N)

// ── A row far down the list: the drive opens at its top; Back returns the list where it was ──
await page.goto(home)
await page.waitForSelector('.a-row')
// a row about 600 px down: less than a drive's page is tall, so a page that kept the list's scroll would open mid-page
const lastLink = page.locator('.a-row .a-name').nth(7)
await page.evaluate(() => window.scrollTo(0, 0))
await lastLink.evaluate((el) => window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - 200))
const lastName = await lastLink.innerText()
const listY = await page.evaluate(() => window.scrollY)
await lastLink.focus()
await page.keyboard.press('Enter')
await page.waitForFunction(() => location.hash.startsWith('#/drive/'))
await settle(page)
const atTop = await page.evaluate(() => ({ y: window.scrollY, h1: document.querySelector('main h1')?.getBoundingClientRect().top ?? -1, height: document.documentElement.scrollHeight }))
check(listY > 300 && atTop.y === 0 && atTop.h1 >= 0 && atTop.h1 < 200 && await h1(page) === lastName,
  `a row down the list (list scrolled to ${listY} px; the drive's page is ${atTop.height} px tall) opens its drive at the top of the page (scrollY ${atTop.y}, name at ${Math.round(atTop.h1)} px)`)
await page.goBack()
await page.waitForSelector('.a-row')
await settle(page)
const backY = await page.evaluate(() => window.scrollY)
const lastInView = await page.locator('.a-row').nth(7).evaluate((el) => { const r = el.getBoundingClientRect(); return r.bottom > 0 && r.top < window.innerHeight })
check(Math.abs(backY - listY) <= 2 && lastInView, `Back returns the list where it was (scrollY ${backY}, was ${listY})`)

// ── Print: a drive prints on white, with its name, and no rail margin ──
await page.goForward()
await page.waitForFunction(() => location.hash.startsWith('#/drive/'))
await settle(page)
await page.emulateMedia({ media: 'print' })
const printed = await page.evaluate(() => {
  const root = document.querySelector('.look-a'), shell = document.querySelector('.a-shell'), name = document.querySelector('main h1')
  return { bg: getComputedStyle(root).backgroundColor, ink: getComputedStyle(name).color, margin: getComputedStyle(shell).marginLeft,
    nameShown: name.getBoundingClientRect().height > 0, rail: getComputedStyle(document.querySelector('nav[aria-label="Places"]')).display }
})
await page.emulateMedia({ media: 'screen' })
check(printed.bg === 'rgb(255, 255, 255)' && printed.nameShown && printed.margin === '0px' && printed.rail === 'none' && printed.ink !== 'rgb(232, 244, 255)',
  `print: white page, the drive's name shown in dark text, no places, no rail margin (${JSON.stringify(printed)})`)

// ── A session-only entry from a real export fixture (#384 ticket 04): its marker, and its page's not-checked line ──
await page.goto(home)
await page.waitForSelector('.a-row')
await page.getByRole('button', { name: 'Import', exact: true }).click()
await page.waitForSelector('#file-input', { state: 'attached', timeout: 15000 })
await page.setInputFiles('#file-input', 'src/lib/__fixtures__/android-exports/diag-snapshot-release.zip')
await page.waitForFunction(() => location.hash.startsWith('#/drive/'), null, { timeout: 20000 })   // a single import lands on it
await page.waitForSelector('.a-notchecked', { timeout: 15000 })
const snapLine = await page.locator('.a-notchecked').innerText()
check(/^Warnings not checked: this import has no fault-code scan\.$/.test(snapLine), `a diagnostic snapshot's page names what it lacks ("${snapLine}")`)
await page.goto(home)
await page.waitForSelector('.a-row')
const snapRow = page.locator('.a-row.is-session')
check(await snapRow.count() === 1 && /◇\s*Session only/.test(await snapRow.innerText()), 'the snapshot is a session-only row, marked "◇ Session only"')
await page.screenshot({ path: `${out}-list-markers-1280x800.png` })

// ── Look after a drive from its row (#384 ticket 05), by keyboard: rename, ticks, Compare, Delete selected; right-click ──
await page.goto(home)
await page.waitForSelector('.a-row')
const firstRow = page.locator('.a-row').first()
const oldName = await firstRow.locator('.a-name').innerText()
await firstRow.getByRole('button', { name: `Actions for ${oldName}` }).focus()
await page.keyboard.press('Enter')                                   // opens the menu at its first item
const onRename = await page.evaluate(() => document.activeElement?.textContent)
await page.keyboard.press('Enter')                                   // Rename…
await page.waitForFunction(() => document.activeElement?.tagName === 'INPUT')   // the editor has focus, the name selected
await page.keyboard.press('ArrowRight')                              // to the end of the selected name (End does not, on a Mac)
await page.keyboard.type(' (renamed)')
await page.keyboard.press('Enter')
await page.waitForFunction((n) => [...document.querySelectorAll('.a-name')].some((a) => a.textContent === n), `${oldName} (renamed)`)
const focusBackTo = await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))
await page.reload()
await page.waitForSelector('.a-row')
check(onRename === 'Rename…' && focusBackTo === `Actions for ${oldName} (renamed)` && await page.locator('.a-row .a-name').first().innerText() === `${oldName} (renamed)`,
  `keyboard only: Enter opens the row menu, Enter renames, the name is kept after a reload, and focus returns to the menu button`)
// right-click with the mouse opens the row's menu; Escape closes it
await page.locator('.a-row').nth(1).click({ button: 'right', position: { x: 200, y: 20 } })
const rightMenu = await page.locator('[role="menu"]').count()
await page.keyboard.press('Escape')
check(rightMenu === 1 && await page.locator('[role="menu"]').count() === 0, 'right-click on a row opens its menu; Escape closes it')
// tick two rows by keyboard and open Compare
const before = await rows(page)
for (const i of [1, 2]) { await page.locator('.a-row .a-tick').nth(i).focus(); await page.keyboard.press('Space') }
await page.getByRole('button', { name: 'Compare (2)' }).focus()
await page.keyboard.press('Enter')
const compared = await page.waitForSelector('text=/ vs /', { timeout: 15000 }).then(() => true, () => false)
check(compared, 'two ticked rows open Compare (today\'s view, against the first ticked)')
await page.getByRole('button', { name: 'Back to the list' }).click()
await page.waitForSelector('.a-row')
// Delete selected asks first; Tab from Cancel to Delete, Enter
await page.getByRole('button', { name: 'Delete selected (2)' }).focus()
await page.keyboard.press('Enter')
const asked = await page.locator('[role="alertdialog"]').innerText()
const cancelFirst = await page.evaluate(() => document.activeElement?.textContent)
await page.keyboard.press('Tab')
await page.keyboard.press('Enter')
await page.waitForFunction((n) => document.querySelectorAll('.a-row').length === n, before - 2)
await page.reload()
await page.waitForSelector('.a-row')
check(/Delete 2 drives\?/.test(asked) && /cannot be undone/.test(asked) && cancelFirst === 'Cancel' && await rows(page) === before - 2,
  `"Delete selected" asks first (focus on Cancel), deletes the two ticked drives, and they stay gone after a reload (${await rows(page)} rows)`)

check(errors.length === 0, `desktop: no page errors (${errors.length}${errors.length ? ': ' + errors[0] : ''})`)
const rail = await page.locator('nav[aria-label="Places"]').boundingBox()
check(rail && rail.x === 0 && rail.height > rail.width, `1280: the places are a rail on the left (${JSON.stringify(rail)})`)
await ctx.close()

// ── Phone width: the bar at the bottom, no horizontal scroll; timing with mobile emulation and a 4× slower CPU ──
{
  // a profile of its own (mobile emulation needs a new context): it imports the drives again
  const { ctx, page, errors } = await open('phone', { width: 375, height: 812 }, { isMobile: true, hasTouch: true, deviceScaleFactor: 2 })
  await page.goto(home)
  await page.getByRole('button', { name: 'Import' }).click()
  await page.waitForSelector('#file-input', { state: 'attached', timeout: 15000 })
  await page.setInputFiles('#file-input', zipPath)
  await page.waitForFunction(() => /Imported \d+ drives/.test(document.body.innerText), null, { timeout: 120000 })
  await page.waitForSelector('.a-row', { timeout: 15000 })
  const bar = await page.locator('nav[aria-label="Places"]').boundingBox()
  check(bar && Math.round(bar.y + bar.height) === 812 && bar.width === 375, `375: the places are a bar at the bottom (${JSON.stringify(bar)})`)
  for (const [what, hash] of [['list', ''], ['drive', null], ['settings', '#/settings']]) {
    if (hash === null) { await page.locator('.a-row .a-name').first().click(); await page.waitForFunction(() => location.hash.startsWith('#/drive/')) }
    else await page.goto(home + hash)
    await page.waitForTimeout(1200)
    const scroll = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    check(scroll <= 0, `375 ${what}: no horizontal page scroll (${scroll} px)`)
    await page.screenshot({ path: `${out}-${what}-375x812.png` })
  }
  await ctx.addInitScript(ROWS_AT, N)
  // a drive's route map scrolled under the bottom bar: the bar stays on top (Leaflet's panes have z-index 200 to 1000)
  await page.goto(home)
  await page.locator('.a-row .a-name').first().click()
  await page.waitForSelector('.leaflet-container', { timeout: 15000 })
  await page.waitForTimeout(800)
  // a short window puts the bottom bar across the map (the map sits near the top of the drive's page)
  const mapBox = await page.locator('.leaflet-container').first().boundingBox()
  await page.setViewportSize({ width: 375, height: Math.round(mapBox.y + mapBox.height / 2 + 28) })
  await page.waitForTimeout(300)
  const over = await page.evaluate(() => {
    const map = document.querySelector('.leaflet-container'), bar = document.querySelector('nav[aria-label="Places"]')
    const b = bar.getBoundingClientRect(), m = map.getBoundingClientRect()
    // every 4 px of the band where the map and the bar overlap, at five points across: the bar must be what is there
    const top = Math.ceil(Math.max(m.top, b.top)), bottom = Math.floor(Math.min(m.bottom, b.bottom))
    const misses = []
    let samples = 0
    for (let y = top + 1; y < bottom; y += 4) for (let i = 1; i <= 5; i++) {
      const x = Math.round(m.left + (m.width * i) / 6)
      const hit = document.elementFromPoint(x, y)
      samples++
      if (!hit || !bar.contains(hit)) misses.push(`${x},${y} ${hit?.tagName} ${hit?.className?.baseVal ?? hit?.className ?? ''}`)
    }
    return { crosses: m.top < b.bottom && m.bottom > b.top, onTop: samples > 0 && misses.length === 0, samples, misses: misses.slice(0, 3),
      map: [Math.round(m.top), Math.round(m.bottom)], bar: [Math.round(b.top), Math.round(b.bottom)], scrollY: Math.round(window.scrollY) }
  })
  check(over.crosses && over.onTop, `375: a route map under the bottom bar stays beneath it (${JSON.stringify(over)})`)
  await page.screenshot({ path: `${out}-map-under-bar-375.png` })
  await page.setViewportSize({ width: 375, height: 812 })

  const cdp = await ctx.newCDPSession(page)
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 })
  // one keystroke in the search, CPU 4× slower: from the input event to the next frame after the list is re-rendered
  await page.goto(home)
  await page.waitForFunction((n) => document.querySelectorAll('.a-row').length === n, N)
  const keystroke = []
  for (const text of ['2', '20', '209', '2096', '']) {
    keystroke.push(await page.evaluate((t) => new Promise((resolve) => {
      const input = document.querySelector('.a-search input')
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      const t0 = performance.now()
      set.call(input, t)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      requestAnimationFrame(() => requestAnimationFrame(() => resolve(Math.round(performance.now() - t0))))
    }), text))
  }
  timing.keystroke4x = keystroke
  console.log(`TIMING  one search keystroke over ${N} drives, CPU 4× slower (emulation), ms to the frame after: ${keystroke.join(' / ')}`)
  check(Math.max(...keystroke) <= 100, `mobile emulation, CPU 4×: a search keystroke shows its rows within 100 ms (${Math.max(...keystroke)} ms, worst of 5; emulation)`)
  timing.mobile4x = []
  for (let i = 0; i < 3; i++) timing.mobile4x.push(await listTime(page))
  check(errors.length === 0, `phone: no page errors (${errors.length}${errors.length ? ': ' + errors[0] : ''})`)
  await ctx.close()
}

const worst = (a) => Math.max(...a)
console.log(`TIMING  the list with ${N} drives, ms from navigation start to the last row: desktop ${timing.desktop.join(' / ')}; mobile emulation, CPU 4× slower ${timing.mobile4x.join(' / ')} (emulation, not a phone)`)
check(worst(timing.desktop) <= 2000, `desktop: the list appears within 2 s (${worst(timing.desktop)} ms, worst of 3)`)
check(worst(timing.mobile4x) <= 2000, `mobile emulation, CPU 4×: the list appears within 2 s (${worst(timing.mobile4x)} ms, worst of 3; emulation)`)
await writeFile(`${out}-timing.json`, JSON.stringify({ drives: N, ...timing }, null, 1))
server.close()
console.log(failures === 0 ? 'ALL PASS' : `FAILURES: ${failures}`)
process.exit(failures === 0 ? 0 : 1)
