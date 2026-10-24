// 管理员改案件名 · 端到端自检（2026-10-24）
// 用户需求：管理员新增案件后要能对案件名做修改。
// 改动：案件管理列表行加了「编辑」按钮，直接打开建案弹窗的编辑模式（原来要 详情→右上角编辑案件）。
//
// 断言两层：
//   1) 列表行有「编辑」且能打开弹窗、案件名称回填正确；
//   2) 改名保存后，列表与详情都显示新名（看 DOM）。
// 另外反向断言：工作台等没接 @edit 的页面**不能**出现「编辑」死按钮。
//
// 用法：node scripts/cf-e2e-case-rename.mjs [baseUrl]   默认 http://127.0.0.1:5173
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const OUT = 'D:/盯办/_e2e_shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const STAMP = new Date().toISOString().replace(/[:.]/g, '-').slice(11, 19)

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

  const api = (method, path, body, token) => p.evaluate(async (m, pt, bd, tk) => {
    const r = await fetch('/api' + pt, {
      method: m,
      headers: { 'Content-Type': 'application/json', ...(tk ? { 'X-Token': tk } : {}) },
      body: bd === undefined ? undefined : JSON.stringify(bd)
    })
    return r.json()
  }, method, path, body, token)

  await p.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
  const login = await api('POST', '/auth/login', { username: 'boss', password: 'admin123' })
  const tok = (login.data || {}).token
  const userInfo = (login.data || {}).userInfo || login.data || {}
  checkTrue('管理员登录', !!tok)

  const OLD = 'E2E改名-原名'
  const NEW = 'E2E改名-新名'
  const mk = await api('POST', '/cases', {
    name: OLD, caseType: 'CRIMINAL', status: 'PENDING_ASSIGN', filingNo: 'E2E-REN-001'
  }, tok)
  const caseId = (mk.data || {}).id
  checkTrue('建测试案件', !!caseId)

  await p.evaluate((tk, ui) => {
    localStorage.setItem('cf_token', tk)
    localStorage.setItem('cf_user', JSON.stringify(ui))
  }, tok, userInfo)
  await p.goto(BASE + '/#/cases', { waitUntil: 'networkidle2' })
  // 关键：上面只是改了 hash，文档没重载，Pinia 的 userInfo 还是空 → isFullAccess=false，
  // 「指派/编辑/删除」全不渲染（只有「详情」）。必须 reload 让 store 重新读 localStorage。
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(2500)

  // #/cases 会先重定向到类型选择页，点「查看全部」进列表
  await p.evaluate(async () => {
    if (!location.hash.startsWith('#/case-type')) return
    const all = [...document.querySelectorAll('button, a, div, span')]
      .find((x) => (x.textContent || '').trim() === '查看全部')
    if (all) all.click()
  })
  await sleep(2500)

  const search = async (kw) => {
    const ok = await p.evaluate(async (k) => {
      const input = document.querySelector('input[placeholder="案件名 / 编号 / 备注"]')
      if (!input) return false
      input.focus()
      input.value = k
      input.dispatchEvent(new Event('input', { bubbles: true }))
      await new Promise((r) => setTimeout(r, 300))
      input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
      await new Promise((r) => setTimeout(r, 2200))
      return true
    }, kw)
    return ok
  }
  await search(OLD)

  // ---- 1) 行内有「编辑」按钮 ----
  const rowHasEdit = await p.evaluate((n) => {
    const row = [...document.querySelectorAll('.el-table__row')].find((r) => (r.textContent || '').includes(n))
    if (!row) return { found: false }
    const btns = [...row.querySelectorAll('button')].map((x) => (x.textContent || '').trim())
    return { found: true, btns }
  }, OLD)
  checkTrue('列表找到测试案件', rowHasEdit.found, JSON.stringify(rowHasEdit))
  checkTrue('该行有「编辑」按钮', !!(rowHasEdit.btns || []).includes('编辑'), JSON.stringify(rowHasEdit.btns))

  // ---- 2) 点编辑 → 弹窗回填原名 ----
  const opened = await p.evaluate((n) => {
    const row = [...document.querySelectorAll('.el-table__row')].find((r) => (r.textContent || '').includes(n))
    if (!row) return false
    const btn = [...row.querySelectorAll('button')].find((x) => (x.textContent || '').trim() === '编辑')
    if (!btn) return false
    btn.click()
    return true
  }, OLD)
  checkTrue('点击行内「编辑」', opened)
  await sleep(2000)

  const dlg = await p.evaluate(() => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"])')
    if (!d) return null
    const items = [...d.querySelectorAll('.el-form-item')]
    const nameItem = items.find((x) => {
      const lb = x.querySelector('.el-form-item__label')
      return lb && (lb.textContent || '').trim() === '案件名称'
    })
    const input = nameItem ? nameItem.querySelector('input') : null
    return {
      title: (d.querySelector('.el-dialog__title') || {}).textContent || '',
      name: input ? input.value : null,
      disabled: input ? (input.disabled || input.readOnly) : null
    }
  })
  checkTrue('弹窗为编辑模式（标题不含「新增」）', !!dlg && !dlg.title.includes('新增'), dlg ? dlg.title : 'null')
  checkTrue('案件名称回填原名', !!dlg && dlg.name === OLD, dlg ? String(dlg.name) : 'null')
  checkTrue('案件名称输入框可编辑（非只读）', !!dlg && dlg.disabled === false, dlg ? String(dlg.disabled) : 'null')
  await p.screenshot({ path: `${OUT}/case-rename-dialog-${STAMP}.png` })

  // ---- 3) 改名 + 保存 ----
  const saved = await p.evaluate(async (n) => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"])')
    if (!d) return false
    const items = [...d.querySelectorAll('.el-form-item')]
    const nameItem = items.find((x) => {
      const lb = x.querySelector('.el-form-item__label')
      return lb && (lb.textContent || '').trim() === '案件名称'
    })
    const input = nameItem && nameItem.querySelector('input')
    if (!input) return false
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(input, n)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await new Promise((r) => setTimeout(r, 400))
    const ok = [...d.querySelectorAll('button')].find((x) => (x.textContent || '').trim() === '保存')
    if (!ok) return false
    ok.click()
    return true
  }, NEW)
  checkTrue('弹窗内改名并保存', saved)
  await sleep(2800)

  // ---- 4) 列表显示新名 ----
  await search(NEW)
  const listed = await p.evaluate((n) => {
    const rows = [...document.querySelectorAll('.el-table__row')]
    const hit = rows.find((r) => (r.textContent || '').includes(n))
    return { count: rows.length, hit: !!hit, text: hit ? (hit.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80) : '' }
  }, NEW)
  checkTrue('列表显示新案件名', listed.hit, JSON.stringify(listed))

  // ---- 5) 接口层复核（DB 真落库了，不是只改了界面）----
  const detail = await api('GET', `/cases/${caseId}`, undefined, tok)
  checkTrue('详情接口读到新名', (detail.data || {}).name === NEW, (detail.data || {}).name)
  await p.screenshot({ path: `${OUT}/case-rename-list-${STAMP}.png` })

  // ---- 6) 反向断言：没接 @edit 的页面不能有「编辑」死按钮 ----
  await p.goto(BASE + '/#/dashboard', { waitUntil: 'networkidle2' })
  await sleep(3000)
  const dashEdit = await p.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-table__row')]
    if (!rows.length) return { rows: 0, hasEdit: false }
    const hasEdit = rows.some((r) => [...r.querySelectorAll('button')].some((x) => (x.textContent || '').trim() === '编辑'))
    return { rows: rows.length, hasEdit }
  })
  checkTrue('工作台表格没有「编辑」死按钮', dashEdit.hasEdit === false, JSON.stringify(dashEdit))

  // ---- 清理 ----
  await api('DELETE', `/cases/${caseId}`, undefined, tok)
  checkTrue('无页面 JS 报错', errs.length === 0, errs.slice(0, 3).join(' | '))
  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  await b.close()
  process.exit(fail === 0 ? 0 : 1)
}

run().catch((e) => { console.error('E2E 异常：', e); process.exit(1) })
