// 嫌疑人强制措施编辑 · 端到端自检（2026-10-24）
// 用法：node scripts/cf-e2e-suspect-measure.mjs   （后端 8080 需已启动，jar 内为最新前端）
//
// 覆盖用户本轮需求：可对现有嫌疑人编辑「是否执行强制措施 / 何种强制措施 / 起止时间」，
// 并显示在嫌疑人栏。判据分两层：
//   交互层 —— 编辑按钮在嫌疑人栏每行存在；点开弹窗回显措施与起止时间；
//   落库层 —— 改完保存后列表 tag 与日期如实更新（看 DOM，不只看 API）。
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = 'http://127.0.0.1:8080'
const OUT = 'D:/盯办/_e2e_shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let pass = 0
let fail = 0
const checkTrue = (name, cond, extra = '') => {
  if (cond) { pass++; console.log('  OK   ' + name) }
  else { fail++; console.log('  FAIL ' + name + (extra ? '\n       ' + extra : '')) }
}

const run = async () => {
  const b = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new',
    args: ['--no-sandbox', '--window-size=1440,950']
  })
  const p = await b.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 200)) })
  await p.setViewport({ width: 1440, height: 950 })

  // 页面内直连后端 API（同源 /api）
  const api = (method, path, body, token) => p.evaluate(async (m, pt, bd, tk) => {
    const r = await fetch('/api' + pt, {
      method: m,
      headers: { 'Content-Type': 'application/json', ...(tk ? { 'X-Token': tk } : {}) },
      body: bd === undefined ? undefined : JSON.stringify(bd)
    })
    return r.json()
  }, method, path, body, token)

  // ---- 准备：登录 + 建测试案 + 加带措施的嫌疑人 ----
  await p.goto(BASE + '/api/', { waitUntil: 'networkidle2' })
  const login = await api('POST', '/auth/login', { username: 'boss', password: 'admin123' })
  const tok = (login.data || {}).token
  const userInfo = (login.data || {}).userInfo || login.data || {}
  checkTrue('管理员登录', !!tok)

  // 把登录态写进 localStorage（SPA 路由守卫读 cf_token / cf_user），再刷新进列表页
  await p.evaluate((tk, ui) => {
    localStorage.setItem('cf_token', tk)
    localStorage.setItem('cf_user', JSON.stringify(ui))
  }, tok, userInfo)
  await p.goto(BASE + '/api/#/cases', { waitUntil: 'networkidle2' })
  await sleep(3000)

  const mk = await api('POST', '/cases', {
    name: 'E2E嫌疑人措施验证', caseType: 'CRIMINAL', status: 'PENDING_ASSIGN', filingNo: 'E2E-SUS-001'
  }, tok)
  const caseId = (mk.data || {}).id
  checkTrue('建测试案件', !!caseId)

  const add = await api('POST', `/cases/${caseId}/suspects`, {
    name: '测试嫌疑人', gender: 'MALE', idCard: '440106200001041234', phone: '13000000000',
    measure: 'DETENTION', measureStartAt: '2026-10-01', measureEndAt: '2026-10-31'
  }, tok)
  checkTrue('新增带措施嫌疑人', add.code === 0)

  // ---- 打开案件详情 ----
  // 注意：进 #/cases 会先被重定向到 #/case-type 类型选择页（2026-10-14 交互），
  // 先点「查看全部」进列表，再找「详情」。
  await sleep(2000)
  const enteredList = await p.evaluate(async () => {
    if (!location.hash.startsWith('#/case-type')) return true
    const all = [...document.querySelectorAll('button, a, div, span')]
      .find((x) => (x.textContent || '').trim() === '查看全部')
    if (!all) return false
    all.click()
    return true
  })
  checkTrue('通过类型选择页进入列表', enteredList)
  await sleep(3000)

  // 列表第一行点「详情」（先用关键词把测试案件筛出来，避免翻页）
  const opened = await p.evaluate(async () => {
    // 1) 关键词框输入案件名 + 回车
    const input = document.querySelector('input[placeholder="案件名 / 编号 / 备注"]')
    if (!input) return 'no-search-input'
    input.focus()
    input.value = 'E2E嫌疑人措施验证'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await new Promise((r) => setTimeout(r, 300))
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    await new Promise((r) => setTimeout(r, 2500))
    // 2) 第一行的「详情」
    const btn = [...document.querySelectorAll('button')].find((x) => (x.textContent || '').trim() === '详情')
    if (!btn) return 'no-detail-btn'
    btn.click()
    return 'clicked'
  })
  checkTrue('搜索并打开测试案件详情', opened === 'clicked', opened)
  await sleep(3500)

  // ---- 交互层 1：嫌疑人栏每行有「编辑」按钮 ----
  const hasEdit = await p.evaluate(() => {
    // 必须限定在详情抽屉内：列表行的「嫌疑人」列也显示嫌疑人姓名，会误匹配
    const scope = document.querySelector('.el-drawer__body') || document.body
    const rows = [...scope.querySelectorAll('.el-table__row')]
    for (const r of rows) {
      const txt = r.textContent || ''
      if (txt.includes('测试嫌疑人')) {
        return [...r.querySelectorAll('button')].some((x) => (x.textContent || '').includes('编辑'))
      }
    }
    return false
  })
  checkTrue('嫌疑人行有「编辑」按钮', hasEdit)

  // ---- 交互层 2：点编辑，弹窗回显 ----
  const dialogInfo = await p.evaluate(() => {
    const scope = document.querySelector('.el-drawer__body') || document.body
    const rows = [...scope.querySelectorAll('.el-table__row')]
    const row = rows.find((r) => (r.textContent || '').includes('测试嫌疑人'))
    if (!row) return { clicked: false }
    const btn = [...row.querySelectorAll('button')].find((x) => (x.textContent || '').includes('编辑'))
    if (!btn) return { clicked: false }
    btn.click()
    return { clicked: true }
  })
  checkTrue('点击编辑按钮', dialogInfo.clicked)
  await sleep(1200)

  const dlg = await p.evaluate(() => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"])')
    if (!d) return null
    const title = (d.querySelector('.el-dialog__title') || {}).textContent || ''
    const bodyText = d.textContent || ''
    // 措施 select 的回显值：必须定位到「当前强制措施」那一行的 select
    // （弹窗里有性别/措施两个 select，取第一个会抓到性别）
    const items = [...d.querySelectorAll('.el-form-item')]
    // label 文案是「强制措施」（4 字）——用「当前强制措施」会超出 92px 的 label 栏折行
    const measureItem = items.find((x) => {
      const lb = x.querySelector('.el-form-item__label')
      return lb && (lb.textContent || '').trim() === '强制措施'
    })
    // 注意 Element Plus 2.9+ 的 DOM：第一个 .el-select__selected-item 是隐藏的 input 容器
    // （is-hidden），真正的回显文本在后面的 selected-item 里，取最后一个文本非空的
    let selText = ''
    if (measureItem) {
      const cands = [...measureItem.querySelectorAll('.el-select__selected-item')]
        .filter((x) => !x.classList.contains('el-select__input-wrapper'))
        .map((x) => (x.textContent || '').trim())
        .filter(Boolean)
      selText = cands.length ? cands[cands.length - 1] : ''
    }
    // 两个日期输入框
    const dates = [...d.querySelectorAll('.el-date-editor input')].map((i) => i.value || '')
    return { title, selText, dates, hasMeasureLabel: !!measureItem, bodyLen: bodyText.length }
  })
  checkTrue('弹窗标题为「编辑嫌疑人」', !!dlg && dlg.title.includes('编辑'), dlg ? dlg.title : 'null')
  checkTrue('弹窗含「强制措施」项', !!dlg && dlg.hasMeasureLabel)
  checkTrue('措施回显「拘留」', !!dlg && dlg.selText.includes('拘留'), dlg ? dlg.selText : 'null')
  checkTrue('起止时间回显', !!dlg && dlg.dates[0] === '2026-10-01' && dlg.dates[1] === '2026-10-31',
    dlg ? JSON.stringify(dlg.dates) : 'null')
  await p.screenshot({ path: OUT + '/suspect-edit-dialog.png' })

  // ---- 交互层 3：在弹窗里改成「逮捕」+ 新起止，保存 ----
  const saved = await p.evaluate(async () => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"])')
    if (!d) return false
    // 点开措施下拉，选「逮捕」
    const sel = d.querySelector('.el-select')
    sel.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    sel.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    await new Promise((r) => setTimeout(r, 600))
    const opts = [...document.querySelectorAll('.el-select-dropdown:not([style*="display: none"]) .el-select-dropdown__item')]
    const arrest = opts.find((o) => (o.textContent || '').includes('逮捕'))
    if (!arrest) return false
    arrest.click()
    await new Promise((r) => setTimeout(r, 400))
    // 两个日期框
    const dates = [...d.querySelectorAll('.el-date-editor input')]
    if (dates.length < 2) return false
    const setDate = (input, val) => {
      input.focus()
      input.value = ''
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.value = val
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      input.blur()
    }
    setDate(dates[0], '2026-09-01')
    setDate(dates[1], '2026-11-30')
    await new Promise((r) => setTimeout(r, 300))
    // 点保存
    const ok = [...d.querySelectorAll('button')].find((x) => (x.textContent || '').includes('保存'))
    if (!ok) return false
    ok.click()
    return true
  })
  checkTrue('弹窗内改措施并保存', saved)
  await sleep(2500)

  // ---- 落库层：列表显示「逮捕」+ 新起止 ----
  const rowInfo = await p.evaluate(() => {
    const scope = document.querySelector('.el-drawer__body') || document.body
    const rows = [...scope.querySelectorAll('.el-table__row')]
    const row = rows.find((r) => (r.textContent || '').includes('测试嫌疑人'))
    if (!row) return null
    const t = row.textContent || ''
    return { text: t.replace(/\s+/g, ' ').trim().slice(0, 200) }
  })
  checkTrue('嫌疑人栏显示「逮捕」', !!rowInfo && rowInfo.text.includes('逮捕'), rowInfo ? rowInfo.text : 'null')
  checkTrue('嫌疑人栏显示新起止 2026-09-01 → 2026-11-30', !!rowInfo && rowInfo.text.includes('2026-09-01') && rowInfo.text.includes('2026-11-30'),
    rowInfo ? rowInfo.text : 'null')
  await p.screenshot({ path: OUT + '/suspect-row-measure.png' })
  // 截图后立即再读一次 DOM：核对「截图内容」与「DOM 实时状态」是否一致
  const afterShot = await p.evaluate(() => {
    const scope = document.querySelector('.el-drawer__body') || document.body
    const row = [...scope.querySelectorAll('.el-table__row')].find(r => (r.textContent||'').includes('测试嫌疑人'))
    return row ? row.textContent.replace(/\s+/g,' ').trim() : 'ROW NOT FOUND'
  })
  console.log('  [调试] 截图后 DOM 行文本:', afterShot)

  // ---- 清理测试数据 ----
  await api('DELETE', `/cases/${caseId}`, undefined, tok)

  checkTrue('无页面 JS 报错', errs.length === 0, errs.slice(0, 3).join(' | '))
  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  await b.close()
  process.exit(fail === 0 ? 0 : 1)
}

run().catch((e) => { console.error('E2E 异常：', e); process.exit(1) })
