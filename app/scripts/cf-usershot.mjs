// 账号管理 + 注册页：部门改为「从已有部门中选择」下拉 核对
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import fs from 'fs'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/案件指派demo/shots'
fs.mkdirSync(OUT, { recursive: true })

const run = async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  })
  const page = await browser.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text()) })
  await page.setViewport({ width: 1440, height: 950 })

  // 注册页（未登录）：部门应为下拉且带已有部门
  await page.goto(`${BASE}/#/register`, { waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 2200))
  const reg = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.cf-register .el-form-item')]
    const it = items.find((i) => i.innerText.trim().startsWith('所属部门'))
    const c = it?.querySelector('.el-form-item__content')
    return { isSelect: !!c?.querySelector('.el-select'), isInput: !!c?.querySelector('input[type=text]') }
  })
  console.log('注册页「所属部门」:', JSON.stringify(reg))
  await page.evaluate(() => {
    const items = [...document.querySelectorAll('.cf-register .el-form-item')]
    items.find((i) => i.innerText.trim().startsWith('所属部门'))?.querySelector('.el-select')?.click()
  })
  await new Promise((r) => setTimeout(r, 800))
  const ropts = await page.evaluate(() =>
    [...document.querySelectorAll('.el-select-dropdown__item')].map((i) => i.innerText.trim()).filter(Boolean))
  console.log('注册页部门候选:', JSON.stringify(ropts))
  await page.screenshot({ path: `${OUT}/register-dept.png` })

  // 登录后看账号管理
  await page.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'boss', password: 'admin123' })
    })
    const j = await r.json()
    const { token, ...userInfo } = j.data
    localStorage.setItem('cf_token', token)
    localStorage.setItem('cf_user', JSON.stringify(userInfo))
  })
  await page.goto(`${BASE}/#/users`, { waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 2200))
  // 打开「新建账号」弹窗
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => x.innerText.includes('新建账号'))
    b?.click()
  })
  await new Promise((r) => setTimeout(r, 1200))
  // 展开「新建员工档案」
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.el-dialog button, .el-dialog a')].find((x) => x.innerText.includes('新建员工档案'))
    b?.click()
  })
  await new Promise((r) => setTimeout(r, 900))
  const dlgFields = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.el-dialog .el-form-item')]
    return items.map((i) => ({
      label: i.innerText.trim().split('\n')[0].slice(0, 8),
      isSelect: !!i.querySelector('.el-form-item__content .el-select')
    }))
  })
  console.log('账号弹窗字段:', JSON.stringify(dlgFields))
  await page.screenshot({ path: `${OUT}/users-newemp.png` })
  // 打开弹窗里的「部门」下拉
  await page.evaluate(() => {
    const items = [...document.querySelectorAll('.el-dialog .el-form-item')]
    const depts = items.filter((i) => i.innerText.trim().startsWith('部门'))
    depts[0]?.querySelector('.el-select')?.click()
  })
  await new Promise((r) => setTimeout(r, 800))
  const uopts = await page.evaluate(() =>
    [...document.querySelectorAll('.el-select-dropdown__item')].map((i) => i.innerText.trim()).filter(Boolean))
  console.log('账号弹窗部门候选:', JSON.stringify(uopts))
  await page.screenshot({ path: `${OUT}/users-dept-dropdown.png` })

  console.log('错误:', errs.length ? errs.slice(0, 6) : '无')
  await browser.close()
}
run().catch((e) => { console.error('FAILED', e); process.exit(1) })
