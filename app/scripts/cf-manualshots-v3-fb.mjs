// v3 手册补图：待办反馈弹窗（从案件详情进入，点「提交反馈」）
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
const b = await puppeteer.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: 'new', args: ['--no-sandbox'] })
const setSession = async (p, u, pw, ct) => p.evaluate(async (u, pw, ct) => {
  const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: pw }) })
  const j = await r.json()
  const { token, ...ui } = j.data
  localStorage.setItem('cf_token', token); localStorage.setItem('cf_user', JSON.stringify(ui))
  if (ct) localStorage.setItem('cf_case_type', ct)
}, u, pw, ct)
const goto = async (p, hash) => { await p.goto('http://127.0.0.1:5173/#' + hash, { waitUntil: 'networkidle2' }); await p.reload({ waitUntil: 'networkidle2' }) }

const p = await b.newPage()
await p.setViewport({ width: 1440, height: 900 })
await p.goto('http://127.0.0.1:5173/#/login', { waitUntil: 'networkidle2' })
await setSession(p, 'test1', '123456', 'CRIMINAL')

await goto(p, '/my-cases')
await waitFor(p, '.el-table__row', 12000)
await p.evaluate(() => {
  const rows = [...document.querySelectorAll('.el-table__row')]
  const btn = [...rows[0].querySelectorAll('button')].find((x) => /详情/.test(x.innerText))
  if (btn) btn.click()
})
await waitFor(p, '.el-drawer', 12000)
await wait(1600)
const ok = await p.evaluate(() => {
  const btns = [...document.querySelectorAll('.el-drawer button')].filter((x) => /提交反馈/.test(x.innerText))
  if (btns[0]) { btns[0].click(); return true }
  return false
})
console.log('feedback button found:', ok)
if (ok) {
  await waitFor(p, '.el-dialog', 10000)
  await wait(1800)
  await p.screenshot({ path: `${OUT}/21-todo-feedback.png` })
  console.log('✓ 21-todo-feedback')
}
await b.close()
