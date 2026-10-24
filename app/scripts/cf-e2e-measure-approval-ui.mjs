// 强制措施到期提醒 + 待审批入口 · UI 自检（2026-10-24）
// 用户口径：到期前要在工作台总览提示；转入下一个措施要经领导批准。
//
// 断言：
//   1) 工作台 KPI 有「措施到期」格，点击后滚到面板（不跳页）
//   2) 面板列出现实数据行，状态标签是「已到期 N 天」或「N 天后到期」
//   3) 造一条待审批申请后，领导能在工作台看到「待审批 N 条」并打开审批对话框
//   4) 对话框里显示「原措施 → 申请措施」，退回会弹意见框
//   5) 普通民警（test1）工作台不出现审批入口（角色隔离）
//
// 用法：node scripts/cf-e2e-measure-approval-ui.mjs [baseUrl]   默认 http://127.0.0.1:8080/api
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = process.argv[2] || 'http://127.0.0.1:8080/api'
const OUT = 'D:/盯办/_e2e_shots'
const STAMP = new Date().toISOString().replace(/[:.]/g, '-').slice(11, 19)
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
  const boss = await api('POST', '/auth/login', { username: 'boss', password: 'admin123' })
  const bossTok = (boss.data || {}).token
  const bossInfo = boss.data || {}
  checkTrue('管理员登录', !!bossTok)

  const staff = await api('POST', '/auth/login', { username: 'test1', password: '123456' })
  const staffTok = (staff.data || {}).token
  const staffInfo = staff.data || {}

  // ---- 造一条待审批：建案 → 指派给 test1 → test1 提交 ----
  const mk = await api('POST', '/cases', {
    name: 'E2E措施审批', caseType: 'CRIMINAL', status: 'PENDING_ASSIGN', filingNo: 'E2E-MEA-UI'
  }, bossTok)
  const caseId = (mk.data || {}).id
  await api('POST', `/cases/${caseId}/assign`, { ownerId: staffInfo.employeeId, memberIds: [] }, bossTok)
  await api('POST', `/watch/cases/${caseId}/measure`, {
    measure: 'BAIL',
    measureDate: new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10),
    // 故意设成昨天到期：这样才会出现「登记下一措施」入口（未过期只有「查看」）
    detainDeadline: new Date(Date.now() - 86400000).toISOString().slice(0, 10),
    comment: 'E2E：申请取保'
  }, staffTok)
  checkTrue('准备待审批申请', !!caseId)

  // ---- 领导视角 ----
  await p.evaluate((tk, ui) => {
    localStorage.setItem('cf_token', tk)
    localStorage.setItem('cf_user', JSON.stringify(ui))
  }, bossTok, bossInfo)
  await p.goto(BASE + '/#/dashboard', { waitUntil: 'networkidle2' })
  await p.reload({ waitUntil: 'networkidle2' })   // 让 Pinia 重新读 localStorage（详见 cf-e2e-case-rename 注释）
  await sleep(3500)

  const kpi = await p.evaluate(() => {
    const cells = [...document.querySelectorAll('.cf-kpi')]
    const hit = cells.find((c) => (c.textContent || '').includes('措施到期'))
    return hit ? { val: (hit.querySelector('.cf-kpi__value') || {}).textContent || '', text: hit.textContent.replace(/\s+/g, ' ').trim() } : null
  })
  checkTrue('工作台有「措施到期」KPI', !!kpi, kpi ? kpi.text : 'null')
  checkTrue('KPI 数值为数字且 > 0', !!kpi && Number(kpi.val) > 0, kpi ? kpi.val : 'null')

  const panel = await p.evaluate(() => {
    const el = document.getElementById('cf-measure-panel')
    if (!el) return null
    const head = (el.querySelector('.cf-panel__head') || {}).textContent || ''
    const rows = [...el.querySelectorAll('.el-table__row')].map((r) => (r.textContent || '').replace(/\s+/g, ' ').trim())
    const hasApproveBtn = [...el.querySelectorAll('button')].some((x) => (x.textContent || '').includes('待审批'))
    return { head: head.replace(/\s+/g, ' ').trim(), rows, hasApproveBtn }
  })
  checkTrue('措施到期面板存在', !!panel)
  checkTrue('面板标题含计数', !!panel && /强制措施到期（\d+）/.test(panel.head), panel ? panel.head : 'null')
  checkTrue('面板有数据行', !!panel && panel.rows.length > 0, panel ? String(panel.rows.length) : 'null')
  checkTrue('行内含「到期」状态文案', !!panel && panel.rows.some((r) => r.includes('到期')), panel ? JSON.stringify(panel.rows.slice(0, 2)) : 'null')
  checkTrue('领导可见「待审批」按钮', !!panel && panel.hasApproveBtn)
  await p.screenshot({ path: `${OUT}/measure-dashboard-${STAMP}.png`, fullPage: false })

  // KPI 点击应滚到面板而不是跳页
  const hashBefore = await p.evaluate(() => location.hash)
  await p.evaluate(() => {
    const cell = [...document.querySelectorAll('.cf-kpi')].find((c) => (c.textContent || '').includes('措施到期'))
    if (cell) cell.click()
  })
  await sleep(800)
  const afterClick = await p.evaluate(() => ({ hash: location.hash, inView: (() => {
    const el = document.getElementById('cf-measure-panel')
    if (!el) return false
    const r = el.getBoundingClientRect()
    return r.top < window.innerHeight && r.bottom > 0
  })() }))
  checkTrue('点 KPI 不跳页', afterClick.hash === hashBefore, `${hashBefore} -> ${afterClick.hash}`)
  checkTrue('点 KPI 后面板在视口内', afterClick.inView)

  // ---- 打开审批对话框 ----
  const opened = await p.evaluate(() => {
    const el = document.getElementById('cf-measure-panel')
    const btn = el && [...el.querySelectorAll('button')].find((x) => (x.textContent || '').includes('待审批'))
    if (!btn) return false
    btn.click()
    return true
  })
  checkTrue('点击「待审批」按钮', opened)
  await sleep(1500)

  const dlg = await p.evaluate(() => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"])')
    if (!d) return null
    const title = (d.querySelector('.el-dialog__title') || {}).textContent || ''
    const rows = [...d.querySelectorAll('.el-table__row')].map((r) => (r.textContent || '').replace(/\s+/g, ' ').trim())
    // 按钮是否真的落在对话框可视区内（列宽不够时「退回」会被挤出右边，看着像少了按钮）
    const dRect = d.getBoundingClientRect()
    const btns = [...d.querySelectorAll('.el-table button, .el-dialog__footer button')].map((x) => {
      const r = x.getBoundingClientRect()
      return {
        t: (x.textContent || '').trim(),
        inView: r.width > 0 && r.left >= dRect.left - 1 && r.right <= dRect.right + 1
      }
    })
    return { title, rows, btns, names: btns.map((x) => x.t), allInView: btns.every((x) => x.inView) }
  })
  checkTrue('审批对话框标题正确', !!dlg && dlg.title.includes('待审批'), dlg ? dlg.title : 'null')
  checkTrue('对话框列出申请行', !!dlg && dlg.rows.length > 0, dlg ? JSON.stringify(dlg.rows) : 'null')
  checkTrue('行内显示「原措施 → 新措施」', !!dlg && dlg.rows.some((r) => r.includes('→') && r.includes('取保')), dlg ? JSON.stringify(dlg.rows.slice(0, 2)) : 'null')
  checkTrue('行内有同意/退回按钮', !!dlg && dlg.names.includes('同意') && dlg.names.includes('退回'), dlg ? JSON.stringify(dlg.names) : 'null')
  checkTrue('同意/退回都在可视区内（不被列宽挤出）', !!dlg && dlg.allInView, dlg ? JSON.stringify(dlg.btns) : 'null')
  await p.screenshot({ path: `${OUT}/measure-approval-dialog-${STAMP}.png` })

  // 点退回 → 应弹意见框（退回必须填意见）
  await p.evaluate(() => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"])')
    const btn = d && [...d.querySelectorAll('button')].find((x) => (x.textContent || '').trim() === '退回')
    if (btn) btn.click()
  })
  await sleep(900)
  const box = await p.evaluate(() => {
    const m = document.querySelector('.el-message-box')
    return m ? { visible: true, text: (m.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 60), hasInput: !!m.querySelector('textarea, input') } : { visible: false }
  })
  checkTrue('退回弹意见框（必填）', box.visible && box.hasInput, JSON.stringify(box))
  // 关掉意见框
  await p.evaluate(() => {
    const m = document.querySelector('.el-message-box')
    const cancel = m && [...m.querySelectorAll('button')].find((x) => (x.textContent || '').trim() === '取消')
    if (cancel) cancel.click()
  })
  await sleep(600)

  // ---- 普通民警视角 ----
  // 注意：民警进 /dashboard 会被路由守卫重定向到 /my-cases（STAFF_PAGES），
  // 所以"工作台总览"对他不存在；他该在「我的案件」页看到自己名下措施到期。
  await api('POST', `/watch/measure-approvals/${(await api('GET', '/watch/measure-approvals/pending', undefined, bossTok)).data.find((x) => x.caseId === caseId).id}/review`,
    { approve: true, comment: 'E2E 同意，便于验证民警视角' }, bossTok)
  await p.evaluate((tk, ui) => {
    localStorage.setItem('cf_token', tk)
    localStorage.setItem('cf_user', JSON.stringify(ui))
  }, staffTok, staffInfo)
  await p.goto(BASE + '/#/dashboard', { waitUntil: 'networkidle2' })
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(3000)
  const staffView = await p.evaluate(() => {
    const kpi = [...document.querySelectorAll('.cf-kpi')].find((c) => (c.textContent || '').includes('措施到期'))
    const list = document.getElementById('cf-mine-measure')
    const rows = list ? [...list.querySelectorAll('.cf-measure-list__row')].map((r) => (r.textContent || '').replace(/\s+/g, ' ').trim()) : []
    return {
      hash: location.hash,
      kpiVal: kpi ? (kpi.querySelector('.cf-kpi__value') || {}).textContent || '' : null,
      hasList: !!list,
      rows
    }
  })
  checkTrue('民警访问工作台被重定向到我的案件', staffView.hash.startsWith('#/my-cases'), staffView.hash)
  checkTrue('我的案件页有「措施到期」KPI', staffView.kpiVal !== null && Number(staffView.kpiVal) > 0, JSON.stringify(staffView))
  // 只在 **测试案那一行** 里找按钮：这页同时列着真实案件的到期措施，
  // 按"第一个含某文案的按钮"取会点到真实案件上（曾误提交一条待审批）。
  const CASE_NAME = 'E2E措施审批'
  const rowInfo = await p.evaluate((name) => {
    const list = document.getElementById('cf-mine-measure')
    if (!list) return null
    const row = [...list.querySelectorAll('.cf-measure-list__row')].find((r) => (r.textContent || '').includes(name))
    if (!row) return null
    return {
      text: (row.textContent || '').replace(/\s+/g, ' ').trim(),
      btns: [...row.querySelectorAll('button')].map((x) => (x.textContent || '').trim())
    }
  }, CASE_NAME)
  checkTrue('我的案件页列出到期措施', !!rowInfo && rowInfo.text.includes(CASE_NAME), JSON.stringify(rowInfo))
  checkTrue('到期行有「登记下一措施」入口', !!rowInfo && rowInfo.btns.includes('登记下一措施'), rowInfo ? JSON.stringify(rowInfo.btns) : 'null')
  await p.screenshot({ path: `${OUT}/measure-mycases-${STAMP}.png` })

  // ---- 承办人点「登记下一措施」 → 弹窗应说明"保存后要领导批" ----
  const btnClicked = await p.evaluate((name) => {
    const list = document.getElementById('cf-mine-measure')
    if (!list) return false
    const row = [...list.querySelectorAll('.cf-measure-list__row')].find((r) => (r.textContent || '').includes(name))
    if (!row) return false
    const btn = [...row.querySelectorAll('button')].find((x) => (x.textContent || '').includes('登记下一措施'))
    if (!btn) return false
    btn.click()
    return true
  }, CASE_NAME)
  checkTrue('点击「登记下一措施」', btnClicked)
  await sleep(1200)
  const md = await p.evaluate(() => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"])')
    if (!d) return null
    const text = (d.textContent || '').replace(/\s+/g, ' ')
    return {
      title: (d.querySelector('.el-dialog__title') || {}).textContent || '',
      hasHint: text.includes('待审批申请') && text.includes('领导'),
      text: text.slice(0, 160)
    }
  })
  checkTrue('打开措施登记弹窗', !!md && md.title.includes('强制措施'), md ? md.title : 'null')
  checkTrue('弹窗提示「保存后需领导批准」', !!md && md.hasHint, md ? md.text : 'null')
  await p.screenshot({ path: `${OUT}/measure-staff-dialog-${STAMP}.png` })
  // 直接确定（默认沿用当前措施，属"续期"，不会触发拘捕校验），应生成待审批
  const submitted = await p.evaluate(() => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"])')
    const ok = d && [...d.querySelectorAll('button')].find((x) => (x.textContent || '').trim() === '确定')
    if (!ok) return false
    ok.click()
    return true
  })
  checkTrue('承办人提交确定', submitted)
  await sleep(2200)
  const afterSubmit = await p.evaluate(async (cid) => {
    const r = await fetch('/api/watch/cases/' + cid + '/measure-pending', {
      headers: { 'X-Token': localStorage.getItem('cf_token') }
    })
    return r.json()
  }, caseId)
  checkTrue('提交后进入「审批中」状态', !!(afterSubmit.data || {}).pending, JSON.stringify(afterSubmit))

  // ---- 清理 ----
  await api('DELETE', `/cases/${caseId}`, undefined, bossTok)
  checkTrue('无页面 JS 报错', errs.length === 0, errs.slice(0, 3).join(' | '))
  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  await b.close()
  process.exit(fail === 0 ? 0 : 1)
}

run().catch((e) => { console.error('E2E 异常：', e); process.exit(1) })
