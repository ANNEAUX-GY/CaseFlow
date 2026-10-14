// v3 手册剩余截图：民警视角（我的案件 / 我的待办 / 待办反馈）
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import fs from 'node:fs'

const OUT = 'D:/案件指派demo/docs/manual-v3/shots'
fs.mkdirSync(OUT, { recursive: true })
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
const waitFor = async (p, s, t = 15000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < t) { if (await p.evaluate((x) => !!document.querySelector(x), s)) return true; await wait(400) }
  return false
}

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--no-sandbox'],
})

const setSession = async (p, u, pw, ct) => p.evaluate(async (u, pw, ct) => {
  const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: pw }) })
  const j = await r.json()
  if (j.code !== 0) throw new Error('登录失败 ' + u + ': ' + j.msg)
  const { token, ...ui } = j.data
  localStorage.setItem('cf_token', token)
  localStorage.setItem('cf_user', JSON.stringify(ui))
  if (ct) localStorage.setItem('cf_case_type', ct); else localStorage.removeItem('cf_case_type')
}, u, pw, ct)

const goto = async (p, hash) => { await p.goto('http://127.0.0.1:5173/#' + hash, { waitUntil: 'networkidle2' }); await p.reload({ waitUntil: 'networkidle2' }) }
const shot = async (p, name, ms = 2400) => { await wait(ms); await p.screenshot({ path: `${OUT}/${name}.png` }); console.log('✓', name) }

const errs = []
const p = await b.newPage()
p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
await p.setViewport({ width: 1440, height: 900 })
await p.goto('http://127.0.0.1:5173/#/login', { waitUntil: 'networkidle2' })
await setSession(p, 'test1', '123456', 'CRIMINAL')

await goto(p, '/my-cases')
await waitFor(p, '.el-table__row, .cf-empty', 12000)
await shot(p, '19-my-cases', 2600)

await goto(p, '/my-todos')
await waitFor(p, '.el-table__row, .cf-empty', 12000)
await shot(p, '20-my-todos', 2600)

// 待办反馈弹窗
const opened = await p.evaluate(() => {
  const btns = [...document.querySelectorAll('button')].filter((x) => /提交反馈|反馈/.test(x.innerText))
  if (btns[0]) { btns[0].click(); return true }
  return false
})
if (opened) {
  await waitFor(p, '.el-dialog', 10000)
  await shot(p, '21-todo-feedback', 2000)
  await p.keyboard.press('Escape')
  await wait(700)
} else {
  console.log('!! 未找到提交反馈按钮')
}

// 我的案件详情（民警视角）
await goto(p, '/my-cases')
await waitFor(p, '.el-table__row', 12000)
await p.evaluate(() => {
  const rows = [...document.querySelectorAll('.el-table__row')]
  const r = rows[0]
  const btn = [...r.querySelectorAll('button')].find((x) => /详情/.test(x.innerText))
  ;(btn || r).click()
})
await waitFor(p, '.el-drawer', 12000)
await shot(p, '22-my-case-detail', 2400)

// 到期提醒（民警）
await p.keyboard.press('Escape')
await wait(700)
await goto(p, '/reminders')
await shot(p, '23-my-reminders', 2200)

await p.close()
await b.close()
console.log('\n=== 页面错误 ===')
console.log(errs.length ? errs.join('\n') : '（无）')
