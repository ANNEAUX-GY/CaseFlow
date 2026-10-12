// 端到端：办案流程面板（阶段 → 环节 → 任务）与「阶段完成后要领导确认」
//
// 起因（用户原话）：「探讨一下，如何去推进整个案件的生命周期？
//   比如完成了初查案件的某某阶段，则进入下一阶段；完成了整个初查阶段，则自动并入刑拘在办阶段等」
// 用户随后明确了两条口径：
//   1)「要领导确认」——阶段做完**不自动跳**，得领导拍板转哪条分支；
//   2)「阶段名」——列表里显示阶段名（初查 / 刑拘在办），不是光一个百分比。
//
// 断言：
//   A 盯办抽屉里有「办案流程」面板，阶段名 + 进度条 + 5 个环节 + 13 项标准任务
//   B 勾一项任务 → 进度与环节计数同步 +1；再点一次能撤销
//   C 勾完全部任务 → 面板出现「待领导确认」告警 + 三个分支按钮，**阶段仍是初查**（不自动跳）
//   D 列表出现「待确认流转」标记；案件管理列表有「办案阶段」列且显示阶段名
//   E 领导点「刑拘 → 刑拘在办」→ 二次确认弹窗说清会改什么 → 阶段切换、进度归零、强制措施=拘留
//   F 普通民警看不到流转按钮（后端也是 @FullAccessOnly）
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

  // 造一个干净的刑事案件（建案即自动铺初查的 13 项标准任务）
  const NAME = 'E2E-办案流程-' + Date.now().toString().slice(-6)
  const created = await api('POST', '/cases', {
    name: NAME, caseType: 'CRIMINAL', priority: 'NORMAL', sourceType: 'MANUAL', status: 'PENDING_ASSIGN'
  }, tk)
  check('S1 建案成功', created.code, 0)
  const cid = created.data.id
  const fv0 = (await api('GET', `/watch/cases/${cid}/flow`, null, tk)).data
  check('S2 建案即铺 5 个环节', fv0.steps.length, 5)
  check('S3 标准任务已生成', fv0.progress.total, 13)

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

  // 注意两点：
//  ① 用 cf-flowpanel 定位，不用「.cf-panel 里找含『办案流程』的那个」——
//     WatchDrawer 里办案流程面板被一个 .cf-panel 包裹着，同名匹配会先命中外层包装，
//     拿到一个 steps=0 的空壳，断言全数报"字段是空的"，像功能没做其实值就在那儿。
//  ② 一律用 textContent 而不是 innerText：innerText 是"渲染出来的可见文本"，
//     Element Plus 抽屉带进场动画，动画未落定时同一元素会返回空串（本次也踩过）。
const flowPanel = () => p.evaluate(() => {
    const panel = document.querySelector('.cf-flowpanel')
    if (!panel) return null
    return {
      stage: panel.querySelector('.cf-flow__stage .el-tag')?.textContent.trim() || '',
      count: (panel.querySelector('.cf-flow__count')?.textContent || '').trim(),
      steps: [...panel.querySelectorAll('.cf-flow__stepname')].map((x) => x.textContent.trim()),
      tasks: panel.querySelectorAll('.cf-flow__task').length,
      alert: panel.querySelector('.cf-flow__alert')?.textContent.replace(/\s+/g, ' ').trim() || '',
      branches: [...panel.querySelectorAll('.cf-flow__branches button')].map((x) => x.textContent.trim())
    }
  })

  // ================= A. 面板存在且内容完整 =================
  console.log('\n=== A. 盯办抽屉里的「办案流程」面板 ===')
  checkTrue('A0 抽屉能打开', await openDrawer())
  let v = await flowPanel()
  checkTrue('A1 有「办案流程」面板', !!v)
  check('A2 五个环节按模板顺序',
    v.steps, ['接收材料', '立案', '侦查', '是否符合刑拘条件', '刑拘'])
  check('A3 任务数 = 13', v.tasks, 13)
  check('A4 进度 0 / 13', v.count, '0 / 13')
  checkTrue('A5 阶段显示为「初查」', (v.stage || '').includes('初查'), v.stage)
  await p.screenshot({ path: `${OUT}/fs-1-panel.png` })

  // ================= B. 勾一项 → 进度同步；再点 → 撤销 =================
  console.log('\n=== B. 勾选任务（双向） ===')
  await p.evaluate(() => document.querySelectorAll('.cf-flow__task .el-checkbox__input')[0]?.click())
  await sleep(1500)
  v = await flowPanel()
  check('B1 进度变 1 / 13', v.count, '1 / 13')
  await p.evaluate(() => document.querySelectorAll('.cf-flow__task .el-checkbox__input')[0]?.click())
  await sleep(1500)
  v = await flowPanel()
  check('B2 撤销后回到 0 / 13', v.count, '0 / 13')

  // ================= C. 全部勾完 → 待领导确认，不自动跳 =================
  console.log('\n=== C. 阶段做完：不自动跳，出现待确认告警 ===')
  // 用接口一次性勾完（界面点 13 次太慢），验证的是「状态到达 100% 后界面怎么表现」。
  // 关键是**不调flow/transfer**——这里就是要证明系统不会自己跳。
  const tasks = fv0.steps.flatMap((s) => s.tasks)
  for (const t of tasks) {
    await api('POST', `/watch/plans/${t.id}/done`, {}, tk)
  }
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(2200)
  const ok2 = await p.evaluate((name) => {
    const rows = [...document.querySelectorAll('.el-table__row')]
    const row = rows.find((r) => r.textContent.includes(name))
    if (!row) return false
    const btn = [...row.querySelectorAll('button')].find((x) => x.textContent.includes('侦查详情'))
    btn && btn.click()
    return true
  }, NAME)
  await sleep(1800)
  checkTrue('C0 抽屉能重新打开', ok2)
  v = await flowPanel()
  check('C1 进度 13 / 13', v.count, '13 / 13')
  checkTrue('C2 告警写明「已全部完成」且要领导确认',
    v.alert.includes('已全部完成') && v.alert.includes('领导确认'), v.alert)
  check('C3 三条分支按钮', v.branches.length, 3)
  checkTrue('C4 分支含「刑拘 → 刑拘在办」',
    v.branches.some((x) => x.includes('刑拘') && x.includes('刑拘在办')), JSON.stringify(v.branches))
  checkTrue('C5 阶段仍是初查（没有自动跳）', (v.stage || '').includes('初查'), v.stage)
  await p.screenshot({ path: `${OUT}/fs-2-waitconfirm.png` })

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
  await p.screenshot({ path: `${OUT}/fs-3-list.png` })

  await p.goto(`${BASE}/#/cases`, { waitUntil: 'domcontentloaded' })
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(2200)
  const heads2 = await p.evaluate(() =>
    [...document.querySelectorAll('.el-table__header th')].map((x) => x.textContent.trim()))
  checkTrue('D4 案件管理列表有「办案阶段」列', heads2.includes('办案阶段'), JSON.stringify(heads2))

  // ================= E. 领导确认流转 → 刑拘在办 =================
  console.log('\n=== E. 领导确认流转到刑拘在办 ===')
  await openDrawer()
  await p.evaluate(() => {
    const btn = [...document.querySelectorAll('.cf-flow__branches button')]
      .find((x) => x.textContent.includes('刑拘') && x.textContent.includes('刑拘在办'))
    btn && btn.click()
  })
  await sleep(900)
  const dlg = await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.offsetParent !== null || x.getBoundingClientRect().height > 0)
    return d ? d.textContent.replace(/\s+/g, ' ').trim() : ''
  })
  checkTrue('E1 弹出二次确认', dlg.includes('确认流转'), dlg.slice(0, 120))
  checkTrue('E2 确认框说清会改什么（任务作废）', dlg.includes('作废'), dlg.slice(0, 200))
  checkTrue('E3 确认框说清强制措施会变', dlg.includes('拘留'), dlg.slice(0, 200))
  checkTrue('E4 确认框提示可能要重新指派', dlg.includes('重新指派'), dlg.slice(0, 240))
  await p.screenshot({ path: `${OUT}/fs-4-confirm.png` })

  await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.getBoundingClientRect().height > 0)
    const btn = [...(d?.querySelectorAll('button') || [])].find((x) => x.textContent.includes('确认流转'))
    btn && btn.click()
  })
  await sleep(2200)
  v = await flowPanel()
  checkTrue('E5 阶段已切到「刑拘在办」', (v.stage || '').includes('刑拘在办'), v.stage)
  check('E6 新阶段首环节=指派清案民警', v.steps[0], '指派清案民警')
  check('E7 进度归零', v.count, '0 / 7')
  const d2 = (await api('GET', `/cases/${cid}`, null, tk)).data
  check('E8 强制措施自动登记为拘留', d2.caseMeasure, 'DETENTION')
  await p.screenshot({ path: `${OUT}/fs-5-detain.png` })

  // ================= F. 普通民警看不到流转按钮 =================
  console.log('\n=== F. 普通民警只能看，不能流转 ===')
  let staff = await login('e2e_staff', 'e2e123456')
  if (!staff) {
    console.log('      （无 e2e_staff 账号，跳过；后端 @FullAccessOnly 已由 verify_flow_stage.py 覆盖）')
  } else {
    await p.goto(`${BASE}/#/my-cases`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(1800)
    // 后端：民警调流转接口必须 403
    const r = await api('POST', `/watch/cases/${cid}/flow/transfer`, { action: 'ARREST' }, staff.token)
    check('F1 民警调流转接口被拒', r.code, 403)
    // 前端：这个案件没指派给民警，他打开不了自己的抽屉，
    // 所以只验「民警身份下流程面板不渲染流转按钮」——用列表里他看得见的案子打开抽屉。
    const openedAny = await p.evaluate(() => {
      const btn = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === '详情')
      btn && btn.click()
      return !!btn
    })
    await sleep(1800)
    if (openedAny) {
      const panelHasBranch = await p.evaluate(() =>
        [...document.querySelectorAll('.cf-flow__branches button')].length)
      check('F2 民警界面上没有流转按钮', panelHasBranch, 0)
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