// 端到端：列表里的「嫌疑人」列展示（2026-10-09）
//
// 展示口径：
//   有嫌疑人的案件显示姓名；多个显示「张三等N人」（N = 总人数，含首位本人）；
//   无嫌疑人显示「—」；多人时悬停浮层给出完整名单。
//
// 覆盖两处列表模板：案件管理（通用组件 CaseTable）与案件盯办（WatchView 自带表格）——
// 两者是两套模板，容易只改一处。
//
// 【测试数据自管】脚本自己造「多人」场景、跑完删掉，所以在任何数据状态下都能重跑，
// 也不会往真实库里留下假嫌疑人。断言比对的是**由接口实时算出的期望值**，
// 不是写死的「张三等3人」——真实数据变了测试也不会假失败。
//
// 用法：node scripts/cf-e2e-suspects.mjs   （前端 5173 与后端 8080 需已启动）
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/盯办/_e2e_shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 补齐用的临时嫌疑人姓名（跑完即删，不与真实数据混淆） */
const FILLERS = ['自检甲', '自检乙']

let pass = 0
let fail = 0
const check = (name, got, want) => {
  const g = JSON.stringify(got)
  const w = JSON.stringify(want)
  if (g === w) { pass++; console.log('  OK   ' + name) }
  else { fail++; console.log('  FAIL ' + name + '\n       got  ' + g + '\n       want ' + w) }
}
const checkIn = (name, got, sub) => {
  if (String(got).includes(sub)) { pass++; console.log('  OK   ' + name) }
  else { fail++; console.log('  FAIL ' + name + '\n       got  ' + JSON.stringify(got) + '\n       应包含 ' + sub) }
}

/** 与前端 utils/format.js 的 suspectLabel 同一口径 */
const expectLabel = (list) => {
  if (!list.length) return '—'
  const first = String(list[0]?.name || '').trim()
  if (list.length === 1) return first || '1 人'
  return (first || '未具名') + '等' + list.length + '人'
}

const run = async () => {
  const b = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new',
    args: ['--no-sandbox', '--window-size=1600,950']
  })
  const p = await b.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 200)) })
  await p.setViewport({ width: 1600, height: 950 })

  // 登录。类型选择留给各场景自己设：案件管理要先看全量，案件盯办受门控必须先选。
  await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  await p.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'boss', password: 'admin123' })
    })
    const j = await r.json()
    const { token, ...ui } = j.data
    localStorage.setItem('cf_token', token)
    localStorage.setItem('cf_user', JSON.stringify(ui))
    localStorage.removeItem('cf_case_type')
  })
  const setCaseType = (v) => p.evaluate((t) => {
    if (t) localStorage.setItem('cf_case_type', t)
    else localStorage.removeItem('cf_case_type')
  }, v)

  /** 用页面内 fetch 调接口（同源走 Vite 代理，避开沙箱对本地回环的代理干扰） */
  const api = (path, method = 'GET', body) => p.evaluate(async (p2, m, bd) => {
    const r = await fetch(p2, {
      method: m,
      headers: { 'X-Token': localStorage.getItem('cf_token'), 'Content-Type': 'application/json' },
      body: bd ? JSON.stringify(bd) : undefined
    })
    return r.json()
  }, '/api' + path, method, body)

  // ============ 准备：造出「多人」场景，并算出各案件的期望展示 ============
  console.log('\n[准备] 读出当前数据并补齐「多人」场景（跑完会删掉补的内容）')
  const before = (await api('/cases?page=1&size=100')).data.list
  const criminal = before.filter((c) => c.caseType === 'CRIMINAL')

  const addedIds = []
  // 挑一个刑事案件补到 3 人（优先有嫌疑人的，首位保持真实数据里的那个人）
  const multi = criminal.find((c) => (c.suspectCount || 0) >= 1) || criminal[0]
  const need = 3 - (multi.suspects || []).length
  for (let i = 0; i < need; i++) {
    const r = await api(`/cases/${multi.id}/suspects`, 'POST', { name: FILLERS[i], gender: 'MALE' })
    const list = r.data || []
    const created = list.find((s) => s.name === FILLERS[i])
    if (created) addedIds.push(created.id)
  }
  console.log(`  多人场景 = ${multi.caseNo}（补 ${need} 人，共 3 人）`)

  // 盯办默认落在「初查」子页签（无强制措施 + 在办），对照案件必须落在这个范围内，
  // 否则行根本不出现在表格里（刑拘案件在另一个页签）。
  const isInitialOpen = (c) => (!c.caseMeasure || c.caseMeasure === 'NONE')
    && !['DONE', 'CANCELLED'].includes(c.status)
  const zeroCands = criminal.filter((c) => c.caseNo !== multi.caseNo
    && isInitialOpen(c) && (c.suspectCount || 0) === 0)

  // 「单人」场景：给一个 0 人初查案件补 1 人；另一个 0 人案件留作「—」对照
  let one = null
  if (zeroCands[0]) {
    const r = await api(`/cases/${zeroCands[0].id}/suspects`, 'POST', { name: '自检单人', gender: 'MALE' })
    const created = (r.data || []).find((s) => s.name === '自检单人')
    if (created) addedIds.push(created.id)
    one = { caseNo: zeroCands[0].caseNo, name: '自检单人' }
  }
  const zero = zeroCands[1]

  const after = (await api('/cases?page=1&size=100')).data.list
  const byNo = {}
  after.forEach((c) => { byNo[c.caseNo] = c })
  const wantMulti = expectLabel(byNo[multi.caseNo].suspects)
  console.log(`  期望：${multi.caseNo} → ${wantMulti}`
    + (one ? ` ｜ ${one.caseNo} → ${one.name}` : '')
    + (zero ? ` ｜ ${zero.caseNo} → —` : ''))

  /** 读当前表格：按表头定位「嫌疑人」列，返回 { 案件编号: 列文本 } */
  const readSuspectColumn = () => p.evaluate(() => {
    const heads = [...document.querySelectorAll('.el-table__header th')].map((x) => x.innerText.trim())
    const idx = heads.indexOf('嫌疑人')
    // 案件编号所在列也要**按表头找**，不能想当然写死 tds[0]：
    // 2026-10-09 加了「重点」星标列，它排在最前面，tds[0] 就从编号变成了星标，
    // 用 tds[0] 取编号会一行都对不上（rows 直接空掉）。
    const noIdx = heads.indexOf('编号')
    const out = { _idx: idx, _noIdx: noIdx, _heads: heads, rows: {} }
    if (idx < 0 || noIdx < 0) return out
    for (const tr of document.querySelectorAll('.el-table__row')) {
      const tds = tr.querySelectorAll('td')
      const no = (tds[noIdx]?.innerText || '').trim().split('\n')[0]
      if (no) out.rows[no] = (tds[idx]?.innerText || '').trim()
    }
    return out
  })

  /**
   * 等表格稳定后再读：页面加载会先渲染一帧旧数据（类型筛选生效前），
   * 立刻读取会拿到上一次的行，断言就会假失败。
   */
  const readStable = async (ms = 9000) => {
    let prev = null
    let t = await readSuspectColumn()
    for (let i = 0; i < ms / 300; i++) {
      const key = JSON.stringify(t.rows)
      if (prev === key && Object.keys(t.rows).length) return t
      prev = key
      await sleep(300)
      t = await readSuspectColumn()
    }
    return t
  }

  try {
    // ================= A. 案件管理（CaseTable 通用组件） =================
    console.log('\n[A] 案件管理列表「嫌疑人」列')
    await setCaseType('CRIMINAL')   // /cases 受类型门控，先选定类型
    await p.goto(`${BASE}/#/cases`, { waitUntil: 'networkidle2' })
    await sleep(1500)
    let t = await readStable()
    console.log('  表头 =', t._heads.join(' | '))
    check('存在「嫌疑人」列', t._idx >= 0, true)
    console.log('  行数 =', Object.keys(t.rows).length)
    check('多人案件显示「首位等N人」', t.rows[multi.caseNo], wantMulti)
    if (one) check('单人案件显示姓名', t.rows[one.caseNo], one.name)
    if (zero) check('无嫌疑人案件显示「—」', t.rows[zero.caseNo], '—')
    await p.screenshot({ path: `${OUT}/S1-caselist.png` })

    console.log('\n[B] 多人悬停出完整名单')
    // 坐标要取在文字 span 上，不能取 td 中心：列宽 140 而文字只有 ~60px 且左对齐，
    // td 中心已落到文字右外侧，hover 不会触发。
    await p.mouse.move(5, 5)
    await sleep(200)
    const box = await p.evaluate((no) => {
      const heads = [...document.querySelectorAll('.el-table__header th')].map((x) => x.innerText.trim())
      const noIdx = heads.indexOf('编号')
      for (const tr of document.querySelectorAll('.el-table__row')) {
        const tds = [...tr.querySelectorAll('td')]
        if (!(tds[noIdx]?.innerText || '').trim().startsWith(no)) continue
        const span = tds.map((td) => td.querySelector('.cf-suspect')).find(Boolean)
        if (!span) return null
        const r = span.getBoundingClientRect()
        return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
      }
      return null
    }, multi.caseNo)
    check('拿到多人单元格坐标', !!box, true)
    if (box) {
      // 从远处逐步移进来：一步到位时平台可能不派发 mouseenter
      await p.mouse.move(box.x - 60, box.y)
      await sleep(150)
      await p.mouse.move(box.x - 25, box.y)
      await sleep(150)
      await p.mouse.move(box.x, box.y)
      await sleep(1500)
      // Element Plus 的 tooltip 浮层是 .el-popper.is-dark.el-tooltip（没有 role 属性，
      // 别用 [role="tooltip"] 去选，会一个都选不到）；页面另有一堆隐藏的 select 下拉，按可见性过滤
      const tip = await p.evaluate(() => [...document.querySelectorAll('.el-popper.is-dark.el-tooltip')]
        .filter((e) => e.style.display !== 'none' && e.offsetParent !== null)
        .map((e) => e.innerText.trim()).filter(Boolean).join(' | '))
      console.log('  tooltip =', JSON.stringify(tip))
      const names = (byNo[multi.caseNo].suspects || []).map((s) => s.name)
      names.forEach((n) => checkIn(`tooltip 含「${n}」`, tip, n))
      await p.screenshot({ path: `${OUT}/S2-tooltip.png` })
    }

    // ================= C. 案件盯办（自带模板） =================
    console.log('\n[C] 案件盯办列表「嫌疑人」列')
    await p.goto(`${BASE}/#/watch`, { waitUntil: 'networkidle2' })
    await sleep(1800)
    t = await readStable()
    check('存在「嫌疑人」列', t._idx >= 0, true)
    console.log('  表头 =', t._heads.join(' | '))
    console.log('  行 =', JSON.stringify(t.rows))
    check('盯办·多人案件显示「首位等N人」', t.rows[multi.caseNo], wantMulti)
    if (one) check('盯办·单人案件显示姓名', t.rows[one.caseNo], one.name)
    if (zero) check('盯办·无嫌疑人案件显示「—」', t.rows[zero.caseNo], '—')
    await p.screenshot({ path: `${OUT}/S3-watch.png` })

    // ================= D. 列宽不应把姓名挤成省略号 =================
    console.log('\n[D] 列宽检查（姓名不应被截断隐藏）')
    const clipped = await p.evaluate(() => {
      const heads = [...document.querySelectorAll('.el-table__header th')].map((x) => x.innerText.trim())
      const idx = heads.indexOf('嫌疑人')
      const bad = []
      for (const tr of document.querySelectorAll('.el-table__row')) {
        const span = tr.querySelectorAll('td')[idx]?.querySelector('.cf-suspect')
        if (span && span.scrollWidth > span.clientWidth + 2) bad.push(span.innerText.trim())
      }
      return bad
    })
    check('无被省略号截断的嫌疑人文本', clipped, [])
  } finally {
    // ============ 清理：删掉本次补的临时嫌疑人 ============
    for (const id of addedIds) {
      await api(`/cases/suspects/${id}`, 'DELETE')
    }
    console.log(`\n[清理] 已删除 ${addedIds.length} 条临时嫌疑人`)
  }

  console.log('\n控制台错误:', errs.length ? errs.slice(0, 6) : '无')
  if (errs.length) fail++
  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  await b.close()
  process.exit(fail ? 1 : 0)
}

run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
