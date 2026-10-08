// 员工图谱：组织树层级色 + 部门待核标记 + 部门下拉 核对截图
// ESM 不认 NODE_PATH，只能用绝对路径引 puppeteer-core
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import fs from 'fs'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/案件指派demo/shots'
fs.mkdirSync(OUT, { recursive: true })

const login = async (page) => {
  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  const res = await page.evaluate(async () => {
    const body = JSON.stringify({ username: 'boss', password: 'admin123' })
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body
    })
    const j = await r.json()
    if (j.code !== 0) return { ok: false, msg: j.msg }
    const { token, ...userInfo } = j.data
    localStorage.setItem('cf_token', token)
    localStorage.setItem('cf_user', JSON.stringify(userInfo))
    return { ok: true }
  })
  if (!res.ok) throw new Error('登录失败: ' + res.msg)
}

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
  await login(page)

  await page.goto(`${BASE}/#/org`, { waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 2500))
  await page.screenshot({ path: `${OUT}/org-list.png` })

  // 读取组织树每一行的文本 + 色点颜色，验证配色
  const rows = await page.evaluate(() => {
    const out = []
    document.querySelectorAll('.cf-org__row').forEach((r) => {
      const dot = r.querySelector('.cf-org__dot')
      out.push({
        text: r.innerText.replace(/\s+/g, ' ').trim(),
        dot: dot ? getComputedStyle(dot).backgroundColor : null,
        flag: r.querySelector('.cf-org__flag') ? r.querySelector('.cf-org__flag').innerText : null
      })
    })
    return out
  })
  console.log('== 列表组织树 ==')
  rows.forEach((r) => console.log(` dot=${r.dot} flag=${r.flag || '-'} | ${r.text}`))

  const legend = await page.evaluate(() => document.querySelector('.cf-org__legend')?.innerText.replace(/\s+/g, ' '))
  console.log('图例:', legend)
  const foot = await page.evaluate(() => document.querySelector('.cf-org__foot')?.innerText.replace(/\s+/g, ' '))
  console.log('底栏:', foot)

  // 点第一个员工，检查右侧表单：部门应为下拉、上级候选应按层级过滤
  await page.evaluate(() => document.querySelector('.cf-org__row')?.click())
  await new Promise((r) => setTimeout(r, 1200))
  const form = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.cf-detail .el-form-item')]
    const pick = (label) => {
      const it = items.find((i) => i.innerText.trim().startsWith(label))
      if (!it) return null
      const c = it.querySelector('.el-form-item__content')
      return {
        label,
        isSelect: !!c?.querySelector('.el-select'),
        isInput: !!c?.querySelector('input[type=text]'),
        text: c?.innerText.replace(/\s+/g, ' ').trim().slice(0, 160)
      }
    }
    return { parent: pick('上级'), dept: pick('部门'), title: pick('职务') }
  })
  console.log('== 表单 ==')
  console.log(JSON.stringify(form, null, 2))
  await page.screenshot({ path: `${OUT}/org-form.png` })

  // 打开「上级」下拉看候选
  if (form.parent?.isSelect) {
    await page.evaluate(() => {
      const items = [...document.querySelectorAll('.cf-detail .el-form-item')]
      const it = items.find((i) => i.innerText.trim().startsWith('上级'))
      it?.querySelector('.el-select')?.click()
    })
    await new Promise((r) => setTimeout(r, 900))
    const opts = await page.evaluate(() =>
      [...document.querySelectorAll('.el-select-dropdown__item')].map((i) => i.innerText.trim()).filter(Boolean))
    console.log('上级候选:', JSON.stringify(opts))
    await page.keyboard.press('Escape')
    await new Promise((r) => setTimeout(r, 400))
  }
  // 打开「部门」下拉看选项
  if (form.dept?.isSelect) {
    await page.evaluate(() => {
      const items = [...document.querySelectorAll('.cf-detail .el-form-item')]
      const it = items.find((i) => i.innerText.trim().startsWith('部门'))
      it?.querySelector('.el-select')?.click()
    })
    await new Promise((r) => setTimeout(r, 900))
    const dopts = await page.evaluate(() =>
      [...document.querySelectorAll('.el-select-dropdown__item')].map((i) => i.innerText.trim()).filter(Boolean))
    console.log('部门候选:', JSON.stringify(dopts))
    await page.screenshot({ path: `${OUT}/org-dept-dropdown.png` })
    await page.keyboard.press('Escape')
    await new Promise((r) => setTimeout(r, 400))
  }

  // 切到树状图
  await page.evaluate(() => {
    const btns = [...document.querySelectorAll('.cf-org__list, .cf-toolbar')]
    const b = [...document.querySelectorAll('.el-radio-button__inner')].find((x) => x.innerText.includes('树状图'))
    b?.click()
  })
  await new Promise((r) => setTimeout(r, 2500))
  await page.screenshot({ path: `${OUT}/org-chart.png` })
  const chartNodes = await page.evaluate(() => {
    const cv = document.querySelector('.cf-org__canvas canvas')
    return cv ? { w: cv.width, h: cv.height } : null
  })
  console.log('树状图画布:', JSON.stringify(chartNodes))

  // 手机端
  await page.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true })
  await page.goto(`${BASE}/#/org`, { waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 2200))
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  console.log('手机端横向溢出:', overflow)
  await page.screenshot({ path: `${OUT}/org-mobile.png`, fullPage: false })

  console.log('错误:', errs.length ? errs.slice(0, 8) : '无')
  await browser.close()
}
run().catch((e) => { console.error('FAILED', e); process.exit(1) })
