// 回归：人少时（当前 7 人）不该出现滚动条，版面与改动前一致
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
const BASE = 'http://127.0.0.1:5173'
const b = await puppeteer.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: 'new', args: ['--no-sandbox'] })
const p = await b.newPage()
const errs = []
p.on('pageerror', e => errs.push('PAGEERROR: ' + e.message))
await p.setViewport({ width: 1440, height: 950 })
await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
await p.evaluate(async () => {
  const r = await fetch('/api/auth/login', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:'boss',password:'admin123'}) })
  const j = await r.json(); const { token, ...ui } = j.data
  localStorage.setItem('cf_token', token); localStorage.setItem('cf_user', JSON.stringify(ui)); localStorage.setItem('cf_case_type','CRIMINAL')
})
await p.goto(`${BASE}/#/dashboard`, { waitUntil: 'networkidle2' })
await p.reload({ waitUntil: 'networkidle2' })
for (let i=0;i<30 && !(await p.evaluate(()=>!!document.querySelector('.cf-echart-box')));i++) await new Promise(r=>setTimeout(r,400))
await new Promise(r=>setTimeout(r,1800))
const m = await p.evaluate(() => {
  const box = document.querySelector('.cf-echart-box')
  const row = box.closest('.el-col').parentElement
  const hs = [...row.querySelectorAll(':scope > .el-col')].map(c => { const x=c.querySelector('.cf-panel'); return x?Math.round(x.getBoundingClientRect().height):null })
  return {
    scrollable: box.scrollHeight > box.clientHeight + 2,
    clientH: box.clientHeight, scrollH: box.scrollHeight,
    canvasH: box.querySelector('canvas')?.height,
    hintShown: document.querySelectorAll('.cf-dash__chart-hint').length > 0,
    sameRow: hs, equal: hs.length===2 && hs[0]===hs[1],
    overflowX: document.documentElement.scrollWidth - window.innerWidth
  }
})
console.log('人少时的表现 =', JSON.stringify(m, null, 1))
await p.screenshot({ path: 'D:/案件指派demo/shots/owner-normal.png' })
console.log('错误:', errs.length ? errs : '无')
await b.close()
