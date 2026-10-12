// 端到端：办案阶段推进（阶段任务 = 案件待办）与「完成后要领导确认」
//
// 用户口径（三条，2026-10-12 二次修订）：
//   1) 阶段完成的判据是**案件待办全部办完**，不是另造一套标准任务清单；
//   2) 办完也**不自动跳**，得领导点确认才转入下一阶段；
//   3) 刑事转入路径只有 **初查 → 刑拘在办 → 取保及监居**；
//      另外「办案流程」面板（阶段→环节→13 项标准任务）整个删掉。
//
// 断言：
//   A 抽屉里有「办案阶段」条：阶段名 + 待办进度 + 领导流转按钮；没有「办案流程」面板
//   B 领导提意见派生的待办计入本阶段进度；办完一条 → 进度 +1
//   C 本阶段待办全办完 → 出现「待办已办完，确认转入」，**阶段仍是初查**
//   D 列表「办案阶段」列显示阶段名 + 「待确认流转」标记
//   E 领导点「待办已办完，确认转入」→ 二次确认弹窗 → 阶段切换、进度归零、强制措施=拘留
//   F 普通民警看不到流转按钮（后端 403）
//
// 用法：node scripts/cf-e2e-flow-stage.mjs   （前端 5173、后端 8080 需已启动）
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/盯办/_e2e_shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let pass = 0
let fail = 0
const check = (name, got, want) => {
  const g = JSON.stringify(got)
  const w = JSON.stringify(want)
  if (g === w) { pass++; console.log('  OK   ' + name) }
  else { fail++; console.log('  FAIL ' + name + '\n       got  ' + g + '\n       want ' + w) }
}
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
  await p.setViewport({ width: 1440, height: 950 })

  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))

  const api = async (method, path, body, token) => {
    const r = await p.evaluate(async (base, m, p2, bd, tk) => {
      const res = await fetch(base + '/api' + p2, {
        method: m,
        headers: Object.assign({ 'Content-Type': 'application/json' }, tk ? { 'X-Token': tk } : {}),
        body: bd === null || bd === undefined ? undefined : JSON.stringify(bd)
      })
      return await res.json()
    }, BASE, method, path, body ?? null, token || null)
    return r
  }

  const login = async (user, pw) => {
    await p.goto(`${BASE}/#/login`, { waitUntil: 'domcontentloaded' })
    await sleep(900)
    const r = await p.evaluate(async (base, u, password) => {
      const res = await fetch(base + '/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: u, password: password })
      })
      return await res.json()
    }, BASE, user, pw)
    const d = r.data
    if (!d || !d.token) return null
    await p.evaluate((token, u2) => {
      localStorage.setItem('cf_token', token)
      localStorage.setItem('cf_user', JSON.stringify(u2))
      localStorage.setItem('cf_case_type', 'CRIMINAL')
    }, d.token, { username: d.username, role: d.role, employeeId: d.employeeId ?? null, name: d.name, isFullAccess: d.isFullAccess })
    return d
  }

  const boss = await login('boss', 'admin123')
  checkTrue('S0 管理员登录', !!boss?.token)
  const tk = boss.token

  // 造一个干净的刑事案件（建案**不再**铺任何标准任务）
  const NAME = 'E2E-办案阶段-' + Date.now().toString().slice(-6)
  const created = await api('POST', '/cases', {
    name: NAME, caseType: 'CRIMINAL', priority: 'NORMAL', sourceType: 'MANUAL', status: 'PENDING_ASSIGN'
  }, tk)
  check('S1 建案成功', created.code, 0)
  const cid = created.data.id
  const fv0 = (await api('GET', `/watch/cases/${cid}/flow`, null, tk)).data
  check('S2 建案不铺任务（0/0）', [fv0.progress.total, fv0.progress.done], [0, 0])
  check('S3 初查唯一出口=刑拘', fv0.transitions.map((t) => t.action), ['DETAIN'])

  /** 打开盯办抽屉并定位到该案件那一行的「侦查详情」 */
  const openDrawer = async () => {
    await p.goto(`${BASE}/#/watch`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(2200)
    const ok = await p.evaluate((name) => {
      const rows = [...document.querySelectorAll('.el-table__row')]
      const row = rows.find((r) => r.textContent.includes(name))
      if (!row) return false
      const btn = [...row.querySelectorAll('button')].find((x) => x.textContent.includes('侦查详情'))
      if (!btn) return false
      btn.click()
      return true
    }, NAME)
    await sleep(1800)
    return ok
  }

  // 一律用 textContent 而不是 innerText：innerText 是"渲染出来的可见文本"，
  // Element Plus 抽屉带进场动画，动画未落定时同一元素会返回空串（本次也踩过）。
  const stageBar = () => p.evaluate(() => {
    const bar = document.querySelector('.cf-stagebar')
    if (!bar) return null
    return {
      stage: bar.querySelector('.el-tag')?.textContent.trim() || '',
      count: (bar.querySelector('.cf-stagebar__count')?.textContent || '').trim(),
      buttons: [...bar.querySelectorAll('button')].map((x) => x.textContent.trim()),
      text: bar.textContent.replace(/\s+/g, ' ').trim()
    }
  })

  /** 「办案流程」面板应已彻底消失（只在当前 DOM 内判断，不扫历史构建产物） */
  const hasFlowPanel = () => p.evaluate(() =>
    !!document.querySelector('.cf-flowpanel')
    || [...document.querySelectorAll('.cf-panel__head span')].some((x) => x.textContent.includes('办案流程')))

  // ================= A. 阶段推进条就位；办案流程面板已删 =================
  console.log('\n=== A. 抽屉里的「办案阶段」条 ===')
  checkTrue('A0 抽屉能打开', await openDrawer())
  let v = await stageBar()
  checkTrue('A1 有「办案阶段」条', !!v)
  checkTrue('A2 阶段显示为「初查」', (v.stage || '').includes('初查'), v.stage)
  checkTrue('A3 显示「本阶段暂无待办」', (v.text || '').includes('暂无待办'), v.text)
  checkTrue('A4 「办案流程」面板已整体删除', !(await hasFlowPanel()))
  await p.screenshot({ path: `${OUT}/fs-1-stagebar.png` })

  // ================= B. 待办计入阶段进度；办完一条 +1 =================
  console.log('\n=== B. 案件待办 = 阶段任务 ===')
  // 领导提两条意见 → 自动派生两条带阶段标记的待办
  for (const content of ['E2E-接收材料登记', 'E2E-完成立案审批']) {
    await api('POST', `/watch/cases/${cid}/opinions`, { content, importance: 'C' }, tk)
  }
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(2200)
  await p.evaluate((name) => {
    const rows = [...document.querySelectorAll('.el-table__row')]
    const row = rows.find((r) => r.textContent.includes(name))
    const btn = [...(row?.querySelectorAll('button') || [])].find((x) => x.textContent.includes('侦查详情'))
    btn && btn.click()
  }, NAME)
  await sleep(2000)
  v = await stageBar()
  check('B1 本阶段待办数 = 2', v.count, '0 / 2')
  checkTrue('B2 按钮文案是「转入下一阶段」（未办完）',
    v.buttons.some((x) => x.includes('转入下一阶段')), JSON.stringify(v.buttons))

  // 办完其中一条（先反馈说明，再标记完成——待办完成必须有凭据）
  const todos = (await api('GET', `/todos/case/${cid}`, null, tk)).data
  const t1 = todos.find((t) => t.content === 'E2E-接收材料登记')
  await api('POST', `/todos/${t1.id}/feedbacks`, { status: 'DONE', content: 'E2E 已办结' }, tk)
  await api('POST', `/todos/${t1.id}/done`, { remark: '已办结' }, tk)
  await sleep(200)
  // 阶段条是打开抽屉时拉的进度，不轮询；改完待办必须重新打开抽屉才能看到新数字
  check('B3 接口口径：办完一条 done=1',
    (await api('GET', `/watch/cases/${cid}/flow`, null, tk)).data.progress.done, 1)
  await openDrawer()
  v = await stageBar()
  check('B4 界面进度变 1 / 2', v.count, '1 / 2')
  await p.screenshot({ path: `${OUT}/fs-2-progress.png` })

  // ================= C. 本阶段办完 → 待领导确认，不自动跳 =================
  console.log('\n=== C. 阶段做完：不自动跳，等领导确认 ===')
  const t2 = todos.find((t) => t.content === 'E2E-完成立案审批')
  await api('POST', `/todos/${t2.id}/feedbacks`, { status: 'DONE', content: 'E2E 已办结' }, tk)
  await api('POST', `/todos/${t2.id}/done`, { remark: '已办结' }, tk)
  // 关键：**不调 flow/transfer** —— 这里就是要证明系统不会自己跳
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(2200)
  await p.evaluate((name) => {
    const rows = [...document.querySelectorAll('.el-table__row')]
    const row = rows.find((r) => r.textContent.includes(name))
    const btn = [...(row?.querySelectorAll('button') || [])].find((x) => x.textContent.includes('侦查详情'))
    btn && btn.click()
  }, NAME)
  await sleep(2000)
  v = await stageBar()
  check('C1 本阶段待办全办完 2 / 2', v.count, '2 / 2')
  checkTrue('C2 阶段仍是初查（没有自动跳）', (v.stage || '').includes('初查'), v.stage)
  checkTrue('C3 按钮变成「待办已办完，确认转入」',
    v.buttons.some((x) => x.includes('待办已办完')), JSON.stringify(v.buttons))
  await p.screenshot({ path: `${OUT}/fs-3-waitconfirm.png` })

  // ================= D. 列表标记与阶段名列 =================
  console.log('\n=== D. 列表上的阶段名与「待确认流转」 ===')
  await p.goto(`${BASE}/#/watch`, { waitUntil: 'domcontentloaded' })
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(2200)
  const heads = await p.evaluate(() =>
    [...document.querySelectorAll('.el-table__header th')].map((x) => x.textContent.trim()))
  checkTrue('D1 盯办列表有「办案阶段」列', heads.includes('办案阶段'), JSON.stringify(heads))
  const rowTxt = await p.evaluate((name) => {
    const row = [...document.querySelectorAll('.el-table__row')].find((r) => r.textContent.includes(name))
    return row ? row.textContent.replace(/\s+/g, ' ').trim() : ''
  }, NAME)
  checkTrue('D2 该行显示阶段名「初查」', rowTxt.includes('初查'), rowTxt)
  checkTrue('D3 该行标了「待确认流转」', rowTxt.includes('待确认流转'), rowTxt)
  // 列表里的阶段进度是 el-progress 图形，不带 "2 / 2" 文字；
  // 数字口径由 verify_flow_stage.py 的 flowDone/flowTotal 断言覆盖，这里只验图形存在
  checkTrue('D4 该行渲染了阶段进度条',
    await p.evaluate((name) => {
      const row = [...document.querySelectorAll('.el-table__row')].find((r) => r.textContent.includes(name))
      return !!row && row.querySelectorAll('.el-progress').length > 0
    }, NAME))
  await p.screenshot({ path: `${OUT}/fs-4-list.png` })

  // ================= E. 领导确认流转 → 刑拘在办 =================
  console.log('\n=== E. 领导确认流转到刑拘在办 ===')
  await openDrawer()
  await p.evaluate(() => {
    const bar = document.querySelector('.cf-stagebar')
    const btn = [...(bar?.querySelectorAll('button') || [])]
      .find((x) => x.textContent.includes('待办已办完'))
    btn && btn.click()
  })
  await sleep(900)
  const dlg = await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')]
      .find((x) => x.getBoundingClientRect().height > 0)
    return d ? d.textContent.replace(/\s+/g, ' ').trim() : ''
  })
  checkTrue('E1 弹出二次确认', dlg.includes('确认转入'), dlg.slice(0, 140))
  checkTrue('E2 确认框说清阶段变化', dlg.includes('初查') && dlg.includes('刑拘在办'), dlg.slice(0, 220))
  checkTrue('E3 确认框说清强制措施会变', dlg.includes('拘留'), dlg.slice(0, 260))
  checkTrue('E4 确认框提示可能要重新指派', dlg.includes('重新指派'), dlg.slice(0, 300))
  await p.screenshot({ path: `${OUT}/fs-5-confirm.png` })

  await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.getBoundingClientRect().height > 0)
    const btn = [...(d?.querySelectorAll('button') || [])].find((x) => x.textContent.includes('确认转入'))
    btn && btn.click()
  })
  await sleep(2400)
  v = await stageBar()
  checkTrue('E5 阶段已切到「刑拘在办」', (v.stage || '').includes('刑拘在办'), v.stage)
  checkTrue('E6 新阶段进度归零', (v.text || '').includes('暂无待办'), v.text)
  const d2 = (await api('GET', `/cases/${cid}`, null, tk)).data
  check('E7 强制措施自动登记为拘留', d2.caseMeasure, 'DETENTION')
  await p.screenshot({ path: `${OUT}/fs-6-detain.png` })

  // ================= F. 普通民警看不到流转按钮 =================
  console.log('\n=== F. 普通民警只能看，不能流转 ===')
  const staff = await login('e2e_staff', 'e2e123456')
  if (!staff) {
    console.log('      （无 e2e_staff 账号，跳过；后端 403 已由 verify_flow_stage.py 覆盖）')
  } else {
    await p.goto(`${BASE}/#/my-cases`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(1800)
    const r = await api('POST', `/watch/cases/${cid}/flow/transfer`, { action: 'BAIL' }, staff.token)
    check('F1 民警调流转接口被拒', r.code, 403)
    const openedAny = await p.evaluate(() => {
      const btn = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === '详情')
      btn && btn.click()
      return !!btn
    })
    await sleep(1800)
    if (openedAny) {
      const barBtns = await p.evaluate(() =>
        [...(document.querySelector('.cf-stagebar')?.querySelectorAll('button') || [])]
          .map((x) => x.textContent.trim()))
      check('F2 民警界面上没有流转按钮', barBtns.length, 0)
    } else {
      console.log('      （民警名下无可打开的案件，跳过 F2 的界面断言）')
    }
  }

  // ================= 清理 =================
  await login('boss', 'admin123')
  await api('DELETE', `/cases/${cid}`, null, tk)
  console.log(`\n清理：已删除自检案件 ${cid}（${NAME}）`)

  const realErrs = errs.filter((e) => !e.includes('Failed to load resource'))
  check('Z1 控制台无 JS 报错', realErrs, [])

  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  await b.close()
  if (fail > 0) process.exit(1)
}

run().catch((e) => { console.error(e); process.exit(1) })