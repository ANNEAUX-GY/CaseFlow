// 建案表单「文字显示不全」回归（2026-10-12）
//
// 起因：用户框出的两处——「来源类型」显示成「手...」、「时间节点」显示成「变更羁押期限时」。
// 根因不是文案太长，是 el-col 的栅格比例给得太窄（760px 弹窗下 6/24 列扣掉 92px label
// 只剩 ~80px 给控件），文字被 overflow 裁掉。
//
// 本脚本把「显示不全」变成可测的数：
//   可用宽 = 文字元素**实际渲染出来的宽度**（select 的占位元素被 flex 压到多宽就是多少）
//   需要宽 = canvas 按同样字体量出的文本像素宽
//   需要 > 可用 → 截断
//
// 两个坑（都踩过）：
//   1. 不能用 scrollWidth：文字被 overflow:hidden 裁掉时 scrollWidth 仍等于 clientWidth，
//      select 永远测不出截断。
//   2. 不能按「控件宽 - padding - 图标」倒推可用宽：el-select 的 .el-select__selection
//      是 flex 容器，里面还塞了一个隐藏的 input 容器，两者分宽度，倒推会把可用宽算高
//      （我第一版就这么写的，旧布局明明截断了却报 ok）。
//
// 另外要把「填入最长预设值后」也测一遍——占位符短、值长的字段（时间节点）
// 只看默认状态是测不出来的。
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/盯办/_e2e_shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let pass = 0
let fail = 0
const checkTrue = (name, ok, extra = '') => {
  if (ok) { pass++; console.log(`  OK   ${name}`) } else { fail++; console.log(`  FAIL ${name}  ${extra}`) }
}

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
  args: ['--no-sandbox', '--window-size=1500,1000']
})
const page = await browser.newPage()
await page.setViewport({ width: 1500, height: 1000 })

const errs = []
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()) })
page.on('pageerror', (e) => errs.push(String(e)))

// ---- 登录 + 塞完整用户态 ----
await page.goto(`${BASE}/#/login`, { waitUntil: 'domcontentloaded' })
await sleep(900)
const auth = await page.evaluate(async (base) => {
  const res = await fetch(base + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'boss', password: 'admin123' })
  })
  return res.json()
}, BASE)
await page.evaluate((d) => {
  localStorage.setItem('cf_token', d.token)
  localStorage.setItem('cf_user', JSON.stringify({
    username: d.username, role: d.role, employeeId: d.employeeId ?? null,
    name: d.name, isFullAccess: d.isFullAccess
  }))
  localStorage.setItem('cf_case_type', 'CRIMINAL')
}, auth.data)

const openDialog = async () => {
  await page.goto(`${BASE}/#/cases`, { waitUntil: 'domcontentloaded' })
  await page.reload({ waitUntil: 'networkidle2' })
  await sleep(1600)
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((x) => x.innerText.includes('新建案件'))
    btn && btn.click()
  })
  await sleep(900)
}

/** 量一遍弹窗里每个表单项：可用宽 vs 需要宽 */
const measure = () => page.evaluate(() => {
  const cv = document.createElement('canvas')
  const ctx = cv.getContext('2d')
  const w = (e) => (e ? e.getBoundingClientRect().width : 0)
  const out = []
  for (const item of document.querySelectorAll('.cf-dialog .el-form-item')) {
    const label = (item.querySelector('.el-form-item__label')?.innerText || '').replace(/\s+/g, ' ').trim()
    if (!label) continue

    let text = ''
    let avail = 0
    const cs = ctx
    const wrap = item.querySelector('.el-select__wrapper')
    if (wrap) {
      const sel = wrap.querySelector('.el-select__selection')
      // 第一个 selected-item 是隐藏的 input 容器（文字为空），必须跳过
      const holder = [...sel.querySelectorAll('.el-select__selected-item')]
        .find((x) => !x.classList.contains('el-select__input-wrapper'))
      if (!holder) continue
      text = (holder.innerText || '').trim()
      avail = w(holder)
      cs.font = `${getComputedStyle(holder).fontSize} ${getComputedStyle(holder).fontFamily}`
    } else {
      const inp = item.querySelector('input.el-input__inner')
      if (!inp) continue
      text = inp.value || inp.placeholder || ''
      const ics = getComputedStyle(inp)
      avail = w(inp) - parseFloat(ics.paddingLeft) - parseFloat(ics.paddingRight)
      cs.font = `${ics.fontSize} ${ics.fontFamily}`
    }
    if (!text) continue
    const need = ctx.measureText(text).width
    out.push({
      label, avail: Math.round(avail), need: Math.round(need),
      truncated: need > avail + 0.5, text: text.slice(0, 24)
    })
  }
  return out
})

const show = (title, rows) => {
  console.log(`  ${title}`)
  rows.forEach((x) => console.log(
    `    ${x.truncated ? '截断!!' : 'ok    '} ${x.label.padEnd(6)} 可视=${String(x.avail).padStart(4)} 需要=${String(x.need).padStart(4)}  "${x.text}"`
  ))
}

// ================= A. 默认（占位/默认值）状态不截断 =================
console.log('\n=== A. 弹窗默认状态：所有控件文字完整显示 ===')
await openDialog()
const dlgW = await page.evaluate(() => {
  const d = document.querySelector('.cf-dialog')
  return d ? Math.round(d.getBoundingClientRect().width) : 0
})
console.log(`  弹窗宽 = ${dlgW}px`)
checkTrue('A0 弹窗已打开且宽度不小于 900px', dlgW >= 900, `实际 ${dlgW}`)

const def = await measure()
show('默认状态', def)
const badDefault = def.filter((x) => x.truncated)
checkTrue('A1 默认状态无截断', badDefault.length === 0,
  JSON.stringify(badDefault.map((x) => `${x.label} 可视${x.avail}/需要${x.need}`)))

// 用户框出的两处，单独点名断言（防止将来又被改窄）
const srcType = def.find((x) => x.label === '来源类型')
checkTrue('A2「来源类型」完整显示「手工录入」',
  !!srcType && !srcType.truncated && srcType.text === '手工录入',
  JSON.stringify(srcType))

// ================= B. 填入最长预设值后仍不截断 =================
console.log('\n=== B. 填入最长预设值后仍不截断 ===')
await page.evaluate(() => {
  const find = (kw) => [...document.querySelectorAll('.cf-dialog .el-form-item')]
    .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes(kw))
  const input = find('时间节点')?.querySelector('input')
  if (input) {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    // 预设里最长的一个节点名
    setter.call(input, '取保候审期限届满时间')
    input.dispatchEvent(new Event('input', { bubbles: true }))
  }
  find('来源类型')?.querySelector('input')?.click()
})
await sleep(700)
await page.evaluate(() => {
  const opt = [...document.querySelectorAll('.el-select-dropdown__item')]
    .find((x) => x.offsetParent !== null && x.innerText.trim() === '手工录入')
  opt && opt.click()
})
await sleep(700)

const filled = await measure()
show('填入最长值后', filled)
const badFilled = filled.filter((x) => x.truncated)
checkTrue('B1 填入最长值后无截断', badFilled.length === 0,
  JSON.stringify(badFilled.map((x) => `${x.label} 可视${x.avail}/需要${x.need}`)))

const node = filled.find((x) => x.label === '时间节点')
checkTrue('B2「时间节点」完整显示十字长节点名',
  !!node && !node.truncated && node.text === '取保候审期限届满时间', JSON.stringify(node))

// ================= C. 窄屏不溢出 =================
console.log('\n=== C. 窄屏（1280 / 1024 / 768）不溢出 ===')
for (const vw of [1280, 1024, 768]) {
  await page.setViewport({ width: vw, height: 1000 })
  await sleep(700)
  const r = await page.evaluate(() => {
    const d = document.querySelector('.cf-dialog')
    if (!d) return null
    const box = d.getBoundingClientRect()
    // 有没有元素横向溢出弹窗
    const over = [...d.querySelectorAll('.el-form-item__content')]
      .filter((c) => c.scrollWidth > c.clientWidth + 1).length
    return { left: Math.round(box.left), right: Math.round(box.right), w: Math.round(box.width), vw: window.innerWidth, over }
  })
  console.log(`  ${vw}px → 弹窗 ${r && r.w}px，溢出项 ${r && r.over}`)
  checkTrue(`C · ${vw}px 视口下弹窗不横向溢出`,
    !!r && r.left >= -1 && r.right <= r.vw + 1 && r.over === 0, JSON.stringify(r))
}
await page.setViewport({ width: 1500, height: 1000 })
await sleep(500)

await page.screenshot({ path: `${OUT}/ff-nowidth.png` })

// ================= D. 控制台无报错 =================
const realErrs = errs.filter((e) => !e.includes('Failed to load resource'))
checkTrue('D1 控制台无报错', realErrs.length === 0, JSON.stringify(realErrs.slice(0, 3)))

console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
await browser.close()
process.exit(fail ? 1 : 0)