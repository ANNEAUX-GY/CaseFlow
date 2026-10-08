// 移除移动端后的桌面端回归：主要页面能否正常渲染 + 关键布局是否还在
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import fs from 'fs'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/案件指派demo/shots'
fs.mkdirSync(OUT, { recursive: true })

const run = async () => {
  const b = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage']
  })
  const p = await b.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 180)) })
  await p.setViewport({ width: 1440, height: 950 })

  await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  const ok = await p.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'boss', password: 'admin123' })
    })
    const j = await r.json()
    if (j.code !== 0) return { ok: false, msg: j.msg }
    const { token, ...ui } = j.data
    localStorage.setItem('cf_token', token)
    localStorage.setItem('cf_user', JSON.stringify(ui))
    localStorage.setItem('cf_case_type', 'CRIMINAL')
    return { ok: true }
  })
  if (!ok.ok) throw new Error('登录失败')

  const pages = [
    ['/dashboard', '工作台'], ['/watch', '案件盯办'], ['/todos', '待办总览'],
    ['/cases', '案件管理'], ['/org', '员工图谱'], ['/users', '账号管理'], ['/categories', '类别管理']
  ]
  console.log('页面渲染检查：')
  for (const [hash, name] of pages) {
    await p.goto(`${BASE}/#${hash}`, { waitUntil: 'networkidle2' })
    await p.reload({ waitUntil: 'networkidle2' })
    await new Promise((r) => setTimeout(r, 2200))
    const m = await p.evaluate(() => {
      const aside = document.querySelector('.cf-aside')
      const panelHead = document.querySelector('.cf-panel__head')
      const cs = panelHead ? getComputedStyle(panelHead) : null
      return {
        hash: location.hash,
        aside: !!aside,
        asideW: aside ? Math.round(aside.getBoundingClientRect().width) : 0,
        // 面板头高度：删掉移动端折行规则后应回到写死的 44px
        panelHeadH: panelHead ? Math.round(panelHead.getBoundingClientRect().height) : 0,
        panelHeadMinH: cs?.minHeight,
        panels: document.querySelectorAll('.cf-panel').length,
        tables: document.querySelectorAll('.el-table').length,
        cards: document.querySelectorAll('.cf-ccard, .cf-ucard, .cf-card-list').length,
        burger: !!document.querySelector('.cf-burger'),
        deviceChip: !!document.querySelector('.cf-device-chip'),
        text: document.body.innerText.replace(/\s+/g, ' ').length,
        overflowX: document.documentElement.scrollWidth - window.innerWidth,
        empty: document.body.innerText.trim().length < 40
      }
    })
    const bad = []
    if (!m.aside) bad.push('侧栏缺失')
    if (m.panels === 0) bad.push('无面板')
    if (m.cards > 0) bad.push('仍有卡片版式')
    if (m.burger || m.deviceChip) bad.push('仍有移动端控件')
    if (m.overflowX > 0) bad.push('横向溢出 ' + m.overflowX)
    if (m.empty) bad.push('页面空白')
    console.log('  %s %-8s 侧栏%s 面板%s 头高%s 表格%s %s',
      bad.length ? '❌' : '✓', hash, m.aside ? m.asideW + 'px' : '无',
      m.panels, m.panelHeadH, m.tables, bad.length ? '← ' + bad.join('；') : '')
    await p.screenshot({ path: `${OUT}/desk-${hash.replace(/\//g, '')}.png` })
  }

  // 抽屉（案件详情）仍要能打开
  await p.goto(`${BASE}/#/cases`, { waitUntil: 'networkidle2' })
  await p.reload({ waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 2500))
  const drawer = await p.evaluate(() => {
    const row = document.querySelector('.el-table__row')
    const btn = row && [...row.querySelectorAll('button')].find((x) => /详情/.test(x.innerText))
    if (btn) btn.click(); else if (row) row.click()
    return !!row
  })
  await new Promise((r) => setTimeout(r, 2500))
  const dm = await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-drawer')].find((x) => x.offsetParent !== null)
    return d ? { w: Math.round(d.getBoundingClientRect().width), title: d.innerText.split('\n')[0].slice(0, 30) } : null
  })
  console.log('\n案件详情抽屉：', JSON.stringify(dm))
  await p.screenshot({ path: `${OUT}/desk-drawer.png` })

  // 普通民警端
  await p.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'test1', password: 'e2e123456' })
    })
    const j = await r.json()
    const { token, ...ui } = j.data
    localStorage.setItem('cf_token', token); localStorage.setItem('cf_user', JSON.stringify(ui))
    localStorage.setItem('cf_case_type', 'CRIMINAL')
  })
  for (const [hash, name] of [['/my-cases', '我的案件'], ['/my-todos', '我的待办'], ['/reminders', '到期提醒']]) {
    await p.goto(`${BASE}/#${hash}`, { waitUntil: 'networkidle2' })
    await p.reload({ waitUntil: 'networkidle2' })
    await new Promise((r) => setTimeout(r, 2000))
    const m = await p.evaluate(() => ({
      panels: document.querySelectorAll('.cf-panel').length,
      cards: document.querySelectorAll('.cf-ccard, .cf-ucard').length,
      overflowX: document.documentElement.scrollWidth - window.innerWidth
    }))
    console.log('  %s %-12s 面板%s %s', (m.panels && !m.cards && m.overflowX <= 0) ? '✓' : '❌',
      hash, m.panels, m.cards ? '（仍有卡片）' : '')
    await p.screenshot({ path: `${OUT}/desk${hash.replace(/\//g, '')}.png` })
  }

  console.log('\n错误：', errs.length ? errs.slice(0, 6) : '无')
  await b.close()
}
run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })