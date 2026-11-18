// Real-browser check of the drive page's one time range (#416 ticket 2), for what jsdom cannot show: Recharts draws no
// chart there, so the brushes are checked here. A production build in a disposable Chromium profile; one fixture drive
// (more than 100 points, so its Trip charts have brushes) imported through the list's Import button; every .png and
// web-font request aborted (no tiles or fonts needed).
// Checks: every Trip brush shows the address's range; a drag on one brush moves every brush, the readout and, once, the
// address (no Back step); "Whole drive" puts every brush back at the chart's ends; the Dashboard's Avg RPM differs between
// a narrowed range and the whole drive; Diagnostics has no brush (it holds no range of its own).
// Playwright is not a project dependency: install it outside the repo, as for scripts/layout-check:
//   T=$(mktemp -d) && (cd $T && npm i playwright@1.48.2 && PLAYWRIGHT_BROWSERS_PATH=$T/b npx playwright install chromium)
//   node_modules/.bin/vite build --outDir $T/dist                                                     # from web/
//   PLAYWRIGHT_BROWSERS_PATH=$T/b PLAYWRIGHT_MODULE=$T/node_modules/playwright/index.mjs \
//     node scripts/range-check/check.mjs $T/dist src/lib/__fixtures__/android-export-rc7/release-tip-can-drive.zip $T/profile
// Exit 0 = every check passed.
import http from 'node:http'
import { readFile } from 'node:fs/promises'
import { join, extname } from 'node:path'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const [dist, zip, profile] = process.argv.slice(2)
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
let failures = 0
const check = (ok, what) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}`); if (!ok) failures++ }

const context = await chromium.launchPersistentContext(profile, { viewport: { width: 1280, height: 900 } })
await context.route(/\.(png|woff2?|ttf)(\?|$)/, (r) => r.abort())
const page = context.pages()[0] ?? await context.newPage()
await page.goto(origin + '/openRS_/')
await page.setInputFiles('#file-input', zip)
await page.waitForFunction(() => location.hash.startsWith('#/drive/'), null, { timeout: 120000 })   // one drive: its page
const drive = await page.evaluate(() => location.hash)
await page.waitForSelector('[role=status][aria-label=Range]', { timeout: 30000 })

/** Each brush's handles relative to its own slide area (0 = the chart's start). */
const offsets = () => page.$$eval('.recharts-brush', (bs) => bs.map((b) => {
  const area = b.querySelector('rect').getBoundingClientRect()
  return [...b.querySelectorAll('.recharts-brush-traveller')].map((t) => Math.round(t.getBoundingClientRect().x - area.x))
}))
const readout = () => page.textContent('[role=status][aria-label=Range]')
const same = (rows) => rows.every((r) => Math.abs(r[0] - rows[0][0]) <= 1 && Math.abs(r[1] - rows[0][1]) <= 1)

await page.getByRole('button', { name: 'Trip', exact: true }).click()
await page.waitForFunction(() => document.querySelectorAll('.recharts-brush').length >= 2, null, { timeout: 30000 })
const whole = await offsets()
console.log(`brushes on Trip: ${whole.length}; whole drive offsets ${JSON.stringify(whole[0])}`)
check(whole.length >= 2, 'Trip draws two or more brushes for a drive of more than 100 points')
check(same(whole), 'on the whole drive, every brush is at the same place')

// an address with a range: every brush shows it
await page.evaluate((d) => { location.hash = `${d}/trip?range=60000-120000` }, drive)
await page.waitForFunction(() => document.querySelector('[role=status][aria-label=Range]')?.textContent?.startsWith('Range: 1:00 – 2:00'), null, { timeout: 15000 })
const ranged = await offsets()
console.log(`range 1:00–2:00 offsets ${JSON.stringify(ranged)}`)
check(same(ranged), 'the address\'s range: every brush shows the same window')
check(ranged[0][0] > whole[0][0] + 5 && ranged[0][1] < whole[0][1] - 5, 'the window is inside the chart, not its ends')

// a drag on the first brush: every brush, the readout and, once at the drag's end, the address
const before = { hash: await page.evaluate(() => location.hash), steps: await page.evaluate(() => history.length), text: await readout() }
const [left] = await page.$$('.recharts-brush .recharts-brush-traveller')
await left.scrollIntoViewIfNeeded()                                             // a mouse acts only on screen
const b = await left.boundingBox()
await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2)
await page.mouse.down()
await page.mouse.move(b.x - 60, b.y + b.height / 2, { steps: 8 })
const midHash = await page.evaluate(() => location.hash)
await page.mouse.up()
await page.waitForFunction((h) => location.hash !== h, before.hash, { timeout: 15000 })
const dragged = await offsets()
const after = { hash: await page.evaluate(() => location.hash), steps: await page.evaluate(() => history.length), text: await readout() }
console.log(`after drag: ${after.text}; ${after.hash.replace(drive, '<drive>')}; offsets ${JSON.stringify(dragged)}`)
// the dragged brush's handle stays where the mouse let go; the others are drawn at the row the selection holds: up to
// two rows apart (the chart's width over its rows)
const rowPx = whole[0][1] / 420
check(dragged.every((r) => Math.abs(r[0] - dragged[0][0]) <= 2 * rowPx + 1 && Math.abs(r[1] - dragged[0][1]) <= 1),
  'a drag on one brush moves every brush (within two rows of the dragged handle)')
await page.reload()
await page.waitForFunction(() => document.querySelectorAll('.recharts-brush').length >= 2, null, { timeout: 30000 })
const reloaded = await offsets()
console.log(`after a reload at that address: ${JSON.stringify(reloaded[0])}`)
check(same(reloaded) && Math.abs(reloaded[1][0] - dragged[1][0]) <= 1, 'a reload at that address: every brush at the same window, the one the drag set')
check(dragged[0][0] < ranged[0][0] - 5 && Math.abs(dragged[0][1] - ranged[0][1]) <= 1, 'the dragged handle moved; the other stayed')
check(after.text !== before.text, 'the readout follows the drag')
check(midHash === before.hash, 'during the drag the address is not written')
// a brush holds rows: its ends snap to the rows' times, so the range's end is the last row at or before 2:00
const dragged0 = /\/trip\?range=(\d+)-(\d+)$/.exec(after.hash)
check(dragged0 !== null && after.hash !== before.hash && Number(dragged0[1]) < 60000 && Number(dragged0[2]) > 118000 && Number(dragged0[2]) <= 120000,
  'at the drag\'s end the address holds the new range (its start earlier, its end kept)')
check(after.steps === before.steps, 'a range change adds no Back step')

// what the charts plot: the first and last m:ss tick of each chart (Recharts 3 draws tick labels outside the axis group,
// so every <text> of the chart outside its brush, keeping the m:ss ones: only the time axis is written that way)
const plotted = () => page.$$eval('.recharts-wrapper', (ws) => ws.map((w) => {
  const t = [...w.querySelectorAll('text')].filter((e) => !e.closest('.recharts-brush')).map((e) => e.textContent ?? '').filter((x) => /^\d+:\d\d$/.test(x))
  return { brush: w.querySelector('.recharts-brush') !== null, first: t[0] ?? null, last: t[t.length - 1] ?? null }
}))
const sec = (mss) => { const [m, s] = mss.split(':').map(Number); return m * 60 + s }
const draggedFrom = Number(dragged0?.[1] ?? 0) / 1000
const plots = (await plotted()).filter((p) => p.first !== null)
console.log(`plotted after the drag: ${JSON.stringify(plots.map((p) => `${p.brush ? 'B' : '-'} ${p.first}..${p.last}`))}`)
check(plots.filter((p) => p.brush).length >= 2 && plots.every((p) => sec(p.first) >= Math.floor(draggedFrom) && sec(p.last) <= 120),
  'every time chart plots only the range (the brushed ones, and the temperature stack through its sync id)')

// a brush by keyboard: arrow keys on a handle move the range, and the address follows (#389: everything by keyboard)
const keyHandle = (await page.$$('.recharts-brush .recharts-brush-traveller'))[0]
await keyHandle.scrollIntoViewIfNeeded()
await keyHandle.focus()
const beforeKeys = { hash: await page.evaluate(() => location.hash), text: await readout(), steps: await page.evaluate(() => history.length) }
await page.keyboard.press('ArrowLeft')
await page.keyboard.press('ArrowLeft')
await page.waitForFunction((t) => document.querySelector('[role=status][aria-label=Range]')?.textContent !== t, beforeKeys.text, { timeout: 15000 })
await page.waitForFunction((h) => location.hash !== h, beforeKeys.hash, { timeout: 3000 }).catch(() => {})
const afterKeys = { hash: await page.evaluate(() => location.hash), text: await readout(), steps: await page.evaluate(() => history.length) }
console.log(`keys: ${beforeKeys.text} -> ${afterKeys.text}; ${afterKeys.hash.replace(drive, '<drive>')}`)
check(afterKeys.text !== beforeKeys.text, 'arrow keys on a brush handle move the range')
check(afterKeys.hash !== beforeKeys.hash && /\?range=\d+-\d+$/.test(afterKeys.hash) && afterKeys.steps === beforeKeys.steps,
  'after arrow keys the address holds the new range, with no Back step')
await page.reload()
await page.waitForFunction(() => document.querySelectorAll('.recharts-brush').length >= 2, null, { timeout: 30000 })
check(await readout() === afterKeys.text, 'a reload keeps the range the keys set')

// Dashboard's numbers follow the range
const avgRpm = () => page.$eval('main', (m) => [...m.querySelectorAll('span')].find((s) => s.textContent === 'Avg RPM')?.parentElement?.textContent)
const onTrip = await page.evaluate(() => location.hash)
await page.getByRole('button', { name: 'Dashboard', exact: true }).click()
await page.waitForFunction(() => [...document.querySelectorAll('span')].some((s) => s.textContent === 'Avg RPM'), null, { timeout: 15000 })
const rangedRpm = await avgRpm()
check(await page.evaluate(() => location.hash) === onTrip.replace('/trip?', '?'), 'a change of view keeps the range')

// "Whole drive": every brush back at the ends, the address without a range, the Dashboard's stored figures
await page.getByRole('button', { name: 'Whole drive', exact: true }).click()
await page.waitForFunction((d) => location.hash === d, drive, { timeout: 15000 })
const wholeRpm = await avgRpm()
console.log(`Avg RPM: in the range "${rangedRpm}", whole drive "${wholeRpm}"`)
check(rangedRpm !== wholeRpm, 'the Dashboard\'s Avg RPM differs between the range and the whole drive')
await page.getByRole('button', { name: 'Trip', exact: true }).click()
await page.waitForFunction(() => document.querySelectorAll('.recharts-brush').length >= 2, null, { timeout: 30000 })
const reset = await offsets()
check(JSON.stringify(reset) === JSON.stringify(whole), '"Whole drive" puts every brush back at the chart\'s ends')

// Diagnostics holds no range of its own
await page.getByRole('button', { name: 'Diagnostics', exact: true }).click()
// (this fixture drive has no diagnostic log: the view says so, and draws no chart at all)
await page.waitForFunction(() => document.querySelector('button[aria-pressed="true"]')?.textContent === 'Diagnostics', null, { timeout: 15000 })
await page.waitForFunction(() => document.querySelectorAll('.recharts-brush').length === 0, null, { timeout: 15000 }).catch(() => {})
check(await page.$$eval('.recharts-brush', (bs) => bs.length) === 0, 'Diagnostics draws no brush')
const diagText = await page.evaluate(() => document.querySelector('.old-view')?.textContent ?? '')
console.log(`Diagnostics shows: "${diagText.slice(0, 80)}"`)
check(/No Diagnostic Data|Session Events/i.test(diagText), 'Diagnostics is shown')

await context.close(); server.close()
console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
process.exit(failures === 0 ? 0 : 1)
