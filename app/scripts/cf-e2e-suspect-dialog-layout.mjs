// 嫌疑人编辑弹窗 · 排版自检（2026-10-24）
// 用户反馈：「起止时间」被挤成一坨，日期和 placeholder 都看不全。
// 判据（不用 scrollWidth 猜，按实测可用宽 + canvas 度量需要宽两遍比对）：
//   1) 弹窗宽度 ≥ 620px
//   2) 两个日期控件可用宽 ≥ 300px
//   3) 日期 input 的文字不溢出（scrollWidth <= clientWidth）
//   4) label「措施开始 / 措施结束」不被裁
// 用法：node scripts/cf-e2e-suspect-dialog-layout.mjs [baseUrl]
//   默认 http://127.0.0.1:5173（vite dev，改完即时生效）
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const OUT = 'D:/盯办/_e2e_shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

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

  // 造一条带措施的案件+嫌疑人（只读排版，测完删）
  const mk = await api('POST', '/cases', {
    name: 'E2E弹窗排版验证', caseType: 'CRIMINAL', status: 'PENDING_ASSIGN', filingNo: 'E2E-LAY-001'
  }, tok)
  const caseId = (mk.data || {}).id
  await api('POST', `/cases/${caseId}/suspects`, {
    name: '排版嫌疑人', gender: 'MALE', idCard: '440106200001041234', phone: '13000000000',
    measure: 'DETENTION', measureStartAt: '2026-10-01', measureEndAt: '2026-10-31'
  }, tok)
  checkTrue('准备测试数据', !!caseId)

  await p.evaluate((tk, ui) => {
    localStorage.setItem('cf_token', tk)
    localStorage.setItem('cf_user', JSON.stringify(ui))
  }, tok, userInfo)
  await p.goto(BASE + '/#/cases', { waitUntil: 'networkidle2' })
  await sleep(3000)

  const entered = await p.evaluate(async () => {
    if (!location.hash.startsWith('#/case-type')) return true
    const all = [...document.querySelectorAll('button, a, div, span')]
      .find((x) => (x.textContent || '').trim() === '查看全部')
    if (!all) return false
    all.click()
    return true
  })
  await sleep(2500)

  const opened = await p.evaluate(async () => {
    const input = document.querySelector('input[placeholder="案件名 / 编号 / 备注"]')
    if (!input) return 'no-search-input'
    input.focus()
    input.value = 'E2E弹窗排版验证'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await new Promise((r) => setTimeout(r, 300))
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    await new Promise((r) => setTimeout(r, 2500))
    const btn = [...document.querySelectorAll('button')].find((x) => (x.textContent || '').trim() === '详情')
    if (!btn) return 'no-detail-btn'
    btn.click()
    return 'clicked'
  })
  checkTrue('进入测试案件详情', opened === 'clicked', opened)
  await sleep(3500)

  const clicked = await p.evaluate(() => {
    const scope = document.querySelector('.el-drawer__body') || document.body
    const row = [...scope.querySelectorAll('.el-table__row')].find((r) => (r.textContent || '').includes('排版嫌疑人'))
    if (!row) return false
    const btn = [...row.querySelectorAll('button')].find((x) => (x.textContent || '').includes('编辑'))
    if (!btn) return false
    btn.click()
    return true
  })
  checkTrue('打开编辑弹窗', clicked)
  await sleep(1200)

  // ---- 核心：量宽度 ----
  const geo = await p.evaluate(() => {
    const dlg = document.querySelector('.el-dialog:not([style*="display: none"])')
    if (!dlg) return null
    const dlgW = dlg.getBoundingClientRect().width

    const items = [...dlg.querySelectorAll('.el-form-item')]
    const itemOf = (kw) => items.find((x) => {
      const lb = x.querySelector('.el-form-item__label')
      return lb && (lb.textContent || '').trim().includes(kw)
    })

    // label 是否折叠：Element Plus 的 label 高度被钉死 32px，文字超出时不会把元素撑高，
    // 只会折行/溢出（用户截图里「当前强制措施」就是把「施」挤到第二行）。
    // 所以判据只能是「内容区宽 vs canvas 实测文字宽」，不能用高度。
    const labelFits = (label) => {
      if (!label) return null
      const cs = getComputedStyle(label)
      const pad = parseFloat(cs.paddingLeft) + parseFloat(cs.paddingRight)
      const avail = label.clientWidth - pad
      const c = document.createElement('canvas').getContext('2d')
      c.font = `${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`
      const need = c.measureText((label.textContent || '').trim()).width
      return { t: (label.textContent || '').trim(), avail: +avail.toFixed(1), need: +need.toFixed(1), ok: avail >= need }
    }

    const measureItem = (kw) => {
      const it = itemOf(kw)
      if (!it) return null
      const label = it.querySelector('.el-form-item__label')
      const editor = it.querySelector('.el-date-editor')
      const input = it.querySelector('.el-date-editor input')
      return {
        labelText: label ? (label.textContent || '').trim() : '',
        labelAvail: label ? label.getBoundingClientRect().width : 0,
        labelNeed: label ? (() => {
          const cs = getComputedStyle(label)
          const c = document.createElement('canvas').getContext('2d')
          c.font = `${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`
          return c.measureText((label.textContent || '').trim()).width
        })() : 0,
        editorW: editor ? editor.getBoundingClientRect().width : 0,
        inputAvail: input ? input.clientWidth : 0,
        inputOverflow: input ? input.scrollWidth > input.clientWidth + 1 : null,
        // 用最长可能值实测需要宽（含 input 内边距）
        needFor: (() => {
          if (!input) return 0
          const cs = getComputedStyle(input)
          const c = document.createElement('canvas').getContext('2d')
          c.font = `${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`
          const pad = parseFloat(cs.paddingLeft) + parseFloat(cs.paddingRight)
          return c.measureText('2026-10-01').width + pad
        })()
      }
    }

    return {
      dlgW,
      start: measureItem('措施开始'),
      end: measureItem('措施结束'),
      measureSelect: (() => {
        const it = itemOf('强制措施')
        const el = it && it.querySelector('.el-select')
        return el ? el.getBoundingClientRect().width : 0
      })(),
      // label 是否折叠：Element Plus 的 label 高度被钉死 32px，文字超出时不会撑高元素，
      // 只会折行/溢出。判据只能是「内容区宽 vs canvas 实测文字宽」。
      badLabels: items
        .map((x) => labelFits(x.querySelector('.el-form-item__label')))
        .filter((x) => x && x.ok === false)
    }
  })

  console.log('  [实测] ' + JSON.stringify(geo, null, 0))
  checkTrue('弹窗宽度 ≥ 620px', !!geo && geo.dlgW >= 620, geo ? geo.dlgW : 'null')
  checkTrue('① 措施开始 控件宽 ≥ 300px', !!geo && geo.start && geo.start.editorW >= 300,
    geo && geo.start ? String(geo.start.editorW) : 'null')
  checkTrue('② 措施结束 控件宽 ≥ 300px', !!geo && geo.end && geo.end.editorW >= 300,
    geo && geo.end ? String(geo.end.editorW) : 'null')
  checkTrue('③ 日期值不溢出（scrollWidth ≤ clientWidth）',
    !!geo && geo.start && geo.end && geo.start.inputOverflow === false && geo.end.inputOverflow === false,
    geo && geo.start ? JSON.stringify({ s: geo.start.inputOverflow, e: geo.end.inputOverflow }) : 'null')
  checkTrue('④ 控件可用宽 ≥ 日期文字需要宽', !!geo && geo.start && geo.end &&
    geo.start.inputAvail >= geo.start.needFor && geo.end.inputAvail >= geo.end.needFor,
    geo && geo.start ? `需 ${geo.start.needFor.toFixed(1)} / 得 ${geo.start.inputAvail}` : 'null')
  checkTrue('⑤ 所有 label 都装得下（不折行/不溢出）', !!geo && geo.badLabels.length === 0,
    geo ? JSON.stringify(geo.badLabels) : 'null')
  checkTrue('⑦ 强制措施下拉与日期控件同宽', !!geo && geo.measureSelect > 0 &&
    Math.abs(geo.measureSelect - geo.start.editorW) < 2,
    geo ? `${geo.measureSelect} vs ${geo.start && geo.start.editorW}` : 'null')

  // 截图名带时间戳：同名文件会被读图工具判为「内容未变」而看不到新的排版
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(11, 19)
  await p.screenshot({ path: `${OUT}/suspect-dialog-layout-${stamp}.png` })
  const dlgEl = await p.$('.el-dialog:not([style*="display: none"])')
  const dlgShot = `${OUT}/suspect-dialog-only-${stamp}.png`
  if (dlgEl) await dlgEl.screenshot({ path: dlgShot }).catch(() => {})
  console.log('  [截图] ' + dlgShot)

  await api('DELETE', `/cases/${caseId}`, undefined, tok)
  checkTrue('无页面 JS 报错', errs.length === 0, errs.slice(0, 3).join(' | '))
  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  await b.close()
  process.exit(fail === 0 ? 0 : 1)
}

run().catch((e) => { console.error('E2E 异常：', e); process.exit(1) })
