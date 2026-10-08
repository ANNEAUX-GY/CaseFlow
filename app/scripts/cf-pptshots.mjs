// 为汇报 PPT 截取系统真实界面素材（比 AI 生成图更贴合实际）
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const OUT = 'D:/案件指派demo/ppt/案件指派系统技术汇报/assets'
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
const waitFor = async (p, s, t = 15000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < t) { if (await p.evaluate((x) => !!document.querySelector(x), s)) return true; await wait(400) }
  return false
}

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
  args: ['--no-sandbox']
})

const login = async (p, u, pw) => p.evaluate(async (u, pw) => {
  const r = await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: pw })
  })
  const j = await r.json()
  if (j.code !== 0) throw new Error('登录失败 ' + u + ': ' + j.msg)
  const { token, ...ui } = j.data
  localStorage.setItem('cf_token', token)
  localStorage.setItem('cf_user', JSON.stringify(ui))
  localStorage.setItem('cf_case_type', 'CRIMINAL')
}, u, pw)

const goto = async (p, hash) => { await p.goto(`http://127.0.0.1:5173/#${hash}`, { waitUntil: 'networkidle2' }); await p.reload({ waitUntil: 'networkidle2' }) }

// ---- 管理层：工作台 ----
const p = await b.newPage()
const errs = []
p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
await p.setViewport({ width: 1440, height: 900 })
await p.goto('http://127.0.0.1:5173/#/login', { waitUntil: 'networkidle2' })
await login(p, 'boss', 'admin123')
await goto(p, '/dashboard')
await waitFor(p, '.cf-echart')
await wait(2800)
await p.screenshot({ path: `${OUT}/ui-dashboard.png` })
console.log('✓ 工作台')

// ---- 管理层：案件管理 + 详情抽屉（待办面板）----
await goto(p, '/cases')
await wait(2600)
await p.evaluate(() => {
  const r = [...document.querySelectorAll('.el-table__row')].find((x) => x.innerText.includes('CA-20260930-011'))
  const btn = [...r.querySelectorAll('button')].find((x) => /详情/.test(x.innerText))
  btn ? btn.click() : r.click()
})
await waitFor(p, '.cf-todo__cards')
await wait(1600)
await p.screenshot({ path: `${OUT}/ui-case-detail.png` })
console.log('✓ 案件详情抽屉')

// ---- 盯办 ----
await p.keyboard.press('Escape')
await wait(600)
await goto(p, '/watch')
await wait(2800)
await p.screenshot({ path: `${OUT}/ui-watch.png` })
console.log('✓ 案件盯办')

// ---- 员工图谱（组织树）----
await goto(p, '/org')
await waitFor(p, '.cf-org__tree')
await wait(1600)
await p.screenshot({ path: `${OUT}/ui-org.png` })
console.log('✓ 员工图谱')

// ---- 待办详情浮窗（汇报弹窗）----
await goto(p, '/cases')
await wait(2400)
await p.evaluate(() => {
  const r = [...document.querySelectorAll('.el-table__row')].find((x) => x.innerText.includes('CA-20260930-011'))
  const btn = [...r.querySelectorAll('button')].find((x) => /详情/.test(x.innerText))
  btn ? btn.click() : r.click()
})
await waitFor(p, '.cf-todo__cards')
await wait(1400)
// 打开一个带反馈的任务详情
await p.evaluate(() => {
  const b = [...document.querySelectorAll('button')].find((x) => x.innerText.includes('详情·子任务'))
  if (b) { b.click(); return }
  const card = document.querySelector('.cf-todo__card')
  card?.querySelector('.cf-todo__card-body')?.click()
})
await waitFor(p, '.cf-td__sec')
await wait(1400)
await p.screenshot({ path: `${OUT}/ui-todo-detail.png` })
console.log('✓ 待办详情浮窗')

// ---- 民警视角 ----
const p2 = await b.newPage()
await p2.setViewport({ width: 1440, height: 900 })
await p2.goto('http://127.0.0.1:5173/#/login', { waitUntil: 'networkidle2' })
await login(p2, 'test1', 'e2e123456')
await goto(p2, '/my-cases')
await wait(2600)
await p2.screenshot({ path: `${OUT}/ui-staff-cases.png` })
console.log('✓ 民警-我的案件')
await goto(p2, '/my-todos')
await wait(2400)
await p2.screenshot({ path: `${OUT}/ui-staff-todos.png` })
console.log('✓ 民警-我的待办')

console.log('错误:', errs.length ? errs.slice(0, 5) : '无')
await b.close()
