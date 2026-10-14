// 为 v3 用户使用手册截取系统真实界面（一页一图，照着找位置）
// 输出：D:/案件指派demo/docs/manual-v3/shots/
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import fs from 'node:fs'

const OUT = 'D:/案件指派demo/docs/manual-v3/shots'
fs.mkdirSync(OUT, { recursive: true })

const wait = (ms) => new Promise((r) => setTimeout(r, ms))
const waitFor = async (p, s, t = 15000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < t) {
    if (await p.evaluate((x) => !!document.querySelector(x), s)) return true
    await wait(400)
  }
  return false
}

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
  args: ['--no-sandbox'],
})

const setSession = async (p, u, pw, caseType) => p.evaluate(async (u, pw, ct) => {
  const r = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: pw }),
  })
  const j = await r.json()
  if (j.code !== 0) throw new Error('登录失败 ' + u + ': ' + j.msg)
  const { token, ...ui } = j.data
  localStorage.setItem('cf_token', token)
  localStorage.setItem('cf_user', JSON.stringify(ui))
  if (ct) localStorage.setItem('cf_case_type', ct)
  else localStorage.removeItem('cf_case_type')
}, u, pw, caseType)

const goto = async (p, hash) => {
  await p.goto('http://127.0.0.1:5173/#' + hash, { waitUntil: 'networkidle2' })
  await p.reload({ waitUntil: 'networkidle2' })
}

const shot = async (p, name, ms = 2200) => {
  await wait(ms)
  await p.screenshot({ path: `${OUT}/${name}.png` })
  console.log('✓', name)
}

const errs = []

// ============ 1. 登录页（未登录） ============
{
  const p = await b.newPage()
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  await p.setViewport({ width: 1440, height: 900 })
  await p.goto('http://127.0.0.1:5173/#/login', { waitUntil: 'networkidle2' })
  await shot(p, '01-login', 2600)
  await p.close()
}

// ============ 管理层（boss） ============
{
  const p = await b.newPage()
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  await p.setViewport({ width: 1440, height: 900 })
  await p.goto('http://127.0.0.1:5173/#/login', { waitUntil: 'networkidle2' })
  await setSession(p, 'boss', 'admin123', 'CRIMINAL')

  // 工作台
  await goto(p, '/dashboard')
  await waitFor(p, '.cf-echart')
  await shot(p, '02-dashboard', 3000)

  // 案件类型选择页
  await goto(p, '/case-type')
  await shot(p, '03-case-type', 2200)

  // 刑事小类卡片页
  await goto(p, '/case-boards?category=CRIMINAL')
  await shot(p, '04-boards-criminal', 2200)

  // 案件管理（刑事）
  await goto(p, '/cases')
  await waitFor(p, '.el-table__row')
  await shot(p, '05-caselist-criminal', 2600)

  // 案件详情抽屉
  await p.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-table__row')]
    const r = rows.find((x) => /CA-/.test(x.innerText)) || rows[0]
    const btn = [...r.querySelectorAll('button')].find((x) => /详情/.test(x.innerText))
    ;(btn || r).click()
  })
  await waitFor(p, '.el-drawer', 12000)
  await shot(p, '06-case-detail', 2400)

  // 新建案件弹窗
  await p.keyboard.press('Escape')
  await wait(900)
  await p.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => /新建案件/.test(x.innerText))
    if (b) b.click()
  })
  await waitFor(p, '.el-dialog', 10000)
  await shot(p, '07-case-create', 2000)
  await p.keyboard.press('Escape')
  await wait(800)

  // 指派弹窗
  await p.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-table__row')]
    const r = rows.find((x) => /指派/.test(x.innerText))
    if (r) {
      const btn = [...r.querySelectorAll('button')].find((x) => /指派/.test(x.innerText))
      if (btn) btn.click()
    }
  })
  await waitFor(p, '.el-dialog', 10000)
  await shot(p, '08-case-assign', 2000)
  await p.keyboard.press('Escape')
  await wait(800)

  // 案件盯办（刑事）
  await goto(p, '/watch')
  await waitFor(p, '.cf-echart')
  await shot(p, '09-watch-criminal', 3000)

  // 盯办详情抽屉（刑事）
  await p.evaluate(() => {
    const btns = [...document.querySelectorAll('.el-table__row button')].filter((x) => /详情|侦查详情/.test(x.innerText))
    if (btns[0]) btns[0].click()
  })
  await waitFor(p, '.el-drawer', 12000)
  await shot(p, '10-watch-drawer-criminal', 2400)
  await p.keyboard.press('Escape')
  await wait(800)

  // 待办总览
  await goto(p, '/todos')
  await waitFor(p, '.el-table__row, .cf-empty', 12000)
  await shot(p, '11-todos-overview', 2400)

  // 到期提醒
  await goto(p, '/deadlines')
  await shot(p, '12-reminders', 2400)

  // 员工图谱
  await goto(p, '/org')
  await waitFor(p, '.el-tree, .cf-org', 12000)
  await shot(p, '13-org', 2600)

  // 账号管理
  await goto(p, '/users')
  await shot(p, '14-users', 2400)

  // 类别管理
  await goto(p, '/categories')
  await shot(p, '15-categories', 2200)

  // 行政案件列表
  await setSession(p, 'boss', 'admin123', 'ADMINISTRATIVE')
  await goto(p, '/cases')
  await waitFor(p, '.el-table__row')
  await shot(p, '16-caselist-admin', 2600)

  // 行政盯办
  await goto(p, '/watch')
  await waitFor(p, '.cf-echart')
  await shot(p, '17-watch-admin', 3000)

  await waitFor(p, '.el-table__row', 10000)
  await p.evaluate(() => {
    const btns = [...document.querySelectorAll('.el-table__row button')].filter((x) => /详情|侦查详情/.test(x.innerText))
    if (btns[0]) btns[0].click()
  })
  await waitFor(p, '.el-drawer', 12000)
  await shot(p, '18-watch-drawer-admin', 2400)

  await p.close()
}

// ============ 民警（staff） ============
{
  const p = await b.newPage()
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  await p.setViewport({ width: 1440, height: 900 })
  await p.goto('http://127.0.0.1:5173/#/login', { waitUntil: 'networkidle2' })
  await setSession(p, 'test1', '123456', 'CRIMINAL')

  // 我的案件
  await goto(p, '/my-cases')
  await waitFor(p, '.el-table__row, .cf-empty', 12000)
  await shot(p, '19-my-cases', 2600)

  // 我的待办
  await goto(p, '/my-todos')
  await waitFor(p, '.el-table__row, .cf-empty', 12000)
  await shot(p, '20-my-todos', 2600)

  // 待办反馈弹窗
  await p.evaluate(() => {
    const btns = [...document.querySelectorAll('button')].filter((x) => /提交反馈|反馈/.test(x.innerText))
    if (btns[0]) btns[0].click()
  })
  await waitFor(p, '.el-dialog', 10000)
  await shot(p, '21-todo-feedback', 2000)

  await p.close()
}

await b.close()
console.log('\n=== 页面错误 ===')
console.log(errs.length ? errs.join('\n') : '（无）')
