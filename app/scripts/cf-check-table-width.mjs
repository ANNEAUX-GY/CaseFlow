// 临时校验：案件列表在 1366 / 1100 两种窗口宽度下的列宽与「操作」列是否完整
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import { mkdirSync } from 'node:fs'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/案件指派demo/docs/manual-v4/shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
mkdirSync(OUT, { recursive: true })

const run = async () => {
  const b = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new', args: ['--no-sandbox']
  })
  const p = await b.newPage()
  await p.setViewport({ width: 1366, height: 900, deviceScaleFactor: 1.4 })
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
    localStorage.setItem('cf_case_type', 'CRIMINAL')
  })
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(600)

  for (const [w, path] of [[1366, '/#/cases'], [1600, '/#/cases'], [1920, '/#/cases'], [1100, '/#/cases']]) {
    await p.setViewport({ width: w, height: 900, deviceScaleFactor: 1.4 })
    await p.goto(`${BASE}${path}`, { waitUntil: 'networkidle2' })
    await sleep(2300)
    const info = await p.evaluate(() => {
      const tbl = document.querySelector('.el-table')
      const wrap = tbl?.querySelector('.el-scrollbar__wrap') || tbl?.querySelector('.el-table__body-wrapper .el-scrollbar__wrap')
      const heads = [...document.querySelectorAll('.el-table__header th')].map((t) => ({
        t: t.innerText.trim(), w: Math.round(t.getBoundingClientRect().width)
      }))
      const ops = [...document.querySelectorAll('.el-table__fixed-right .el-table__row, .el-table__body tr')]
      const firstRowBtns = [...document.querySelectorAll('.el-table__body tr')][0]
        ? [...document.querySelectorAll('.el-table__body tr')][0].querySelectorAll('button').length : 0
      const fixedBtns = document.querySelectorAll('.el-table__fixed-right button').length
      return {
        tableW: Math.round(tbl.getBoundingClientRect().width),
        scrollW: wrap ? wrap.scrollWidth : null,
        clientW: wrap ? wrap.clientWidth : null,
        heads, firstRowBtns, fixedBtns
      }
    })
    console.log(`\n[${w}px ${path}] 表格宽=${info.tableW} 内容宽=${info.scrollW} 可视宽=${info.clientW}`
      + ` 首行按钮=${info.firstRowBtns} 固定列按钮=${info.fixedBtns}`)
    console.log('  列宽 =', info.heads.map((h) => `${h.t}:${h.w}`).join(' | '))
    const tag = path.replace(/[#\/]/g, '')
    await p.screenshot({ path: `${OUT}/tmp-${tag}-${w}.png` })
    console.log(`  ✓ tmp-${tag}-${w}.png`)
  }
  await b.close()
}
run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
