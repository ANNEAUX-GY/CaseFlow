// 精确核对：滚动条是否真的可见 + 同排两面板是否仍等高
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = 'http://127.0.0.1:5173'
const N = 24
const OUT = 'D:/案件指派demo/shots'

const run = async () => {
  const b = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new', args: ['--no-sandbox']
  })
  const page = await b.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  await page.setViewport({ width: 1440, height: 950 })
  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  await page.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'boss', password: 'admin123' })
    })
    const j = await r.json(); const { token, ...ui } = j.data
    localStorage.setItem('cf_token', token); localStorage.setItem('cf_user', JSON.stringify(ui))
    localStorage.setItem('cf_case_type', 'CRIMINAL')
  })

  const made = await page.evaluate(async (n) => {
    const token = localStorage.getItem('cf_token')
    const call = async (m, p, body) => {
      const r = await fetch('/api' + p, {
        method: m, headers: { 'Content-Type': 'application/json', 'X-Token': token },
        body: body ? JSON.stringify(body) : undefined
      })
      return r.json()
    }
    const emps = [], cases = []
    for (let i = 1; i <= n; i++) {
      const e = await call('POST', '/employees', {
        name: 'ZZS' + i, employeeNo: 'ZZSCR' + i, dept: '滚动测试',
        title: '组员', policeGroup: 'NONE', sortNo: 800 + i, status: 1 })
      if (e.code === 0) emps.push(e.data.id)
      const c = await call('POST', '/cases', {
        sourceType: 'MANUAL', name: 'ZZS案' + i, receivedAt: '2026-10-01',
        caseType: 'CRIMINAL', priority: 'C' })
      if (c.code === 0) cases.push(c.data.id)
    }
    for (let i = 0; i < cases.length; i++) {
      await call('POST', '/cases/' + cases[i] + '/assign', { caseId: cases[i], ownerId: emps[i], memberIds: [] })
    }
    return { emps, cases }
  }, N)
  console.log('造数：员工', made.emps.length, '案件', made.cases.length)

  await page.goto(`${BASE}/#/dashboard`, { waitUntil: 'networkidle2' })
  await page.reload({ waitUntil: 'networkidle2' })
  for (let i = 0; i < 30 && !(await page.evaluate(() => !!document.querySelector('.cf-echart-box'))); i++) {
    await new Promise((r) => setTimeout(r, 400))
  }
  await new Promise((r) => setTimeout(r, 1800))

  const m = await page.evaluate(() => {
    const box = document.querySelector('.cf-echart-box')
    const cs = getComputedStyle(box)
    // 同一 el-row 里的另一个面板（案件状态分布）
    const row = box.closest('.el-col').parentElement
    const cols = [...row.querySelectorAll(':scope > .el-col')]
    const heights = cols.map((c) => {
      const p = c.querySelector('.cf-panel')
      return p ? Math.round(p.getBoundingClientRect().height) : null
    })
    return {
      // 滚动条占位：offsetWidth - clientWidth > 0 说明是经典滚动条（有固定槽位）
      scrollbarGutter: box.offsetWidth - box.clientWidth,
      overflowY: cs.overflowY,
      scrollbarWidth: cs.scrollbarWidth,
      scrollable: box.scrollHeight > box.clientHeight + 2,
      clientH: box.clientHeight, scrollH: box.scrollHeight,
      sameRowPanelHeights: heights,
      equalHeight: heights.length === 2 && heights[0] === heights[1],
      canvasH: box.querySelector('canvas')?.height,
      // 页面本身不该被撑出横向滚动
      pageOverflowX: document.documentElement.scrollWidth - window.innerWidth
    }
  })
  console.log('工作台核对 =', JSON.stringify(m, null, 1))

  // 鼠标悬停到图上（Windows 上滚动条常是悬停/滚动时才显形），再截图看
  const bb = await page.evaluate(() => {
    const r = document.querySelector('.cf-echart-box').getBoundingClientRect()
    return { x: r.x + r.width - 6, y: r.y + r.height / 2 }
  })
  await page.mouse.move(bb.x, bb.y)
  await new Promise((r) => setTimeout(r, 600))
  await page.mouse.wheel({ deltaY: 120 })
  await new Promise((r) => setTimeout(r, 600))
  await page.screenshot({ path: `${OUT}/owner-scrollbar.png` })

  console.log('错误:', errs.length ? errs.slice(0, 4) : '无')

  const cleaned = await page.evaluate(async (ids) => {
    const token = localStorage.getItem('cf_token')
    const call = async (m, p) => (await fetch('/api' + p, { method: m, headers: { 'X-Token': token } })).json()
    let c = 0, e = 0
    for (const x of ids.cases) if ((await call('DELETE', '/cases/' + x)).code === 0) c++
    for (const x of ids.emps) if ((await call('DELETE', '/employees/' + x)).code === 0) e++
    return { c, e }
  }, { cases: made.cases, emps: made.emps })
  console.log('清理：案件', cleaned.c, '员工', cleaned.e)
  await b.close()
}
run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })