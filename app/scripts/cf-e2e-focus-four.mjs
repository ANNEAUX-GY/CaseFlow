// 端到端：四个栏目（案件管理 / 案件盯办 / 待办总览 / 到期提醒）的重点案件能力必须一致
//
// 起因（用户要求原话）：
//   「在这四个栏目都加上重点案件显示」
//
// 上一版只在案件管理里做了「只看重点」筛选，另外三个栏目没有——
// 用户在这四个页面之间来回切，会得到"这页能筛重点、那页不能"的割裂感。
// 本脚本把「星标列 + 只看重点 + 卡片与列表同口径」三件事在四个栏目上逐一钉死。
//
// 断言：
//   A 四个栏目首列都是「重点」
//   B 四个栏目勾「只看重点」后，列表条数与接口 focusOnly=true 的结果一致，且每行都已点亮
//   C 卡片/汇总必须与列表同口径（盯办看板初查卡、待办总览卡片 total）
//   D 普通民警看不到「只看重点」勾选框（后端 focus 接口是 @FullAccessOnly）
//
// 用法：node scripts/cf-e2e-focus-four.mjs   （前端 5173、后端 8080 需已启动）
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

  // ---------- 登录（接口拿 token，注入 localStorage 后 reload，store 才有完整用户态） ----------
  await p.goto(`${BASE}/#/login`, { waitUntil: 'domcontentloaded' })
  await sleep(1200)
  const login = async (user, pass) => {
    await p.goto(`${BASE}/#/login`, { waitUntil: 'domcontentloaded' })
    await sleep(600)
    const r = await p.evaluate(async (base, u, pw) => {
      const res = await fetch(base + '/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: u, password: pw })
      })
      return await res.json()
    }, BASE, user, pass)
    const d = r.data
    await p.evaluate((token, u) => {
      localStorage.setItem('cf_token', token)
      localStorage.setItem('cf_user', JSON.stringify(u))
      localStorage.setItem('cf_case_type', 'CRIMINAL')
    }, d.token, { username: d.username, role: d.role, employeeId: d.employeeId ?? null, name: d.name, isFullAccess: d.isFullAccess })
    return d
  }
  const boss = await login('boss', 'admin123')
  checkTrue('S0 管理员登录成功（token 非空）', !!boss?.token)

  // 页面内直调接口，用页面自己的 token（期望值以此为准，避免脚本里写死数字）
  const apiGet = async (path) => {
    const r = await p.evaluate(async (base, p2) => {
      const res = await fetch(base + '/api' + p2, {
        headers: { 'X-Token': localStorage.getItem('cf_token') || '' }
      })
      return await res.json()
    }, BASE, path)
    return r
  }

  const openPage = async (hash) => {
    await p.goto(`${BASE}/#${hash}`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(2200)
  }
  const heads = () => p.evaluate(() =>
    [...document.querySelectorAll('.el-table__header th')].map((x) => x.innerText.trim()))
  const rowCount = () => p.evaluate(() => document.querySelectorAll('.el-table__row').length)
  const litStars = () => p.evaluate(() => document.querySelectorAll('.el-table__row .cf-star.is-on').length)
  const focusRows = () => p.evaluate(() => document.querySelectorAll('.el-table__row.cf-row--focus').length)

  /** 勾「只看重点」：Element Plus 的 label 包着原生 input，点 label 即可 */
  // 勾选入口有两种形态：默认/inline 是 el-checkbox，lead 是 .cf-focus-lead 按钮
  const tickFocus = async () => p.evaluate(() => {
    const btn = [...document.querySelectorAll('.cf-focus-lead')]
      .filter((x) => x.offsetParent !== null)[0]
    if (btn) { btn.click(); return 'ok' }
    const box = [...document.querySelectorAll('.el-checkbox')]
      .filter((x) => x.offsetParent !== null)
      .find((x) => x.innerText.includes('只看重点'))
    if (!box) return 'not-found'
    ;(box.querySelector('.el-checkbox__input') || box).click()
    return 'ok'
  })

  // ================= A. 四个栏目首列都是「重点」 =================
  console.log('\n=== A. 四个栏目都有「重点」列 ===')
  const PAGES = [
    ['案件管理', '/cases'],
    ['案件盯办', '/watch'],
    ['待办总览', '/todos'],
    ['到期提醒', '/reminders']
  ]
  for (const [label, hash] of PAGES) {
    await openPage(hash)
    const hs = await heads()
    check(`A · ${label} 首列表头是「重点」`, hs[0], '重点')
    checkTrue(`A · ${label} 实际落在本栏目（没被门控弹走）`,
      (await p.evaluate(() => location.hash)).startsWith('#' + hash),
      await p.evaluate(() => location.hash))
  }

  // ================= B. 四个栏目都能「只看重点」 =================
  console.log('\n=== B. 四个栏目都能筛出重点案件 ===')

  // ---- 案件管理 ----
  await openPage('/cases')
  {
    const all = await rowCount()
    const exp = (await apiGet('/cases?size=20&caseType=CRIMINAL&focusOnly=true')).data.total
    check('B1 案件管理：勾选前有数据', await tickFocus(), 'ok')
    await sleep(1800)
    const n = await rowCount()
    check('B2 案件管理：勾选后条数 = 接口 focusOnly 结果', n, Math.min(exp, 20))
    check('B3 案件管理：勾选后每一行都是重点（点亮数 = 行数）', await litStars(), n)
    check('B4 案件管理：勾选后每一行都有重点高亮', await focusRows(), n)
    console.log(`      （勾选前 ${all} 行 → 勾选后 ${n} 行）`)
  }

  // ---- 案件盯办 ----
  await openPage('/watch')
  {
    const exp = (await apiGet('/watch/cases?size=20&caseType=CRIMINAL&module=INITIAL&focusOnly=true')).data.total
    check('B5 案件盯办：找到并勾上「只看重点」', await tickFocus(), 'ok')
    await sleep(1800)
    const n = await rowCount()
    check('B6 案件盯办：勾选后条数 = 接口 focusOnly 结果', n, Math.min(exp, 20))
    check('B7 案件盯办：勾选后每一行都是重点', await litStars(), n)
    await p.screenshot({ path: `${OUT}/ff4-watch-focus.png` })
  }

  // ---- 待办总览 ----
  await openPage('/todos')
  {
    const exp = (await apiGet('/todos/overview?caseType=CRIMINAL&focusOnly=true')).data.length
    check('B8 待办总览：找到并勾上「只看重点」', await tickFocus(), 'ok')
    await sleep(1800)
    const n = await rowCount()
    check('B9 待办总览：勾选后条数 = 接口 focusOnly 结果', n, exp)
    check('B10 待办总览：勾选后每一行背后的案件都是重点', await litStars(), n)
    await p.screenshot({ path: `${OUT}/ff4-todos-focus.png` })
  }

  // ---- 到期提醒 ----
  await openPage('/reminders')
  {
    const exp = (await apiGet('/cases/reminders?bucket=OVERDUE&limit=100&caseType=CRIMINAL&focusOnly=true')).data.length
    check('B11 到期提醒：找到并勾上「只看重点」', await tickFocus(), 'ok')
    await sleep(1800)
    const n = await rowCount()
    check('B12 到期提醒：勾选后条数 = 接口 focusOnly 结果', n, exp)
    check('B13 到期提醒：勾选后每一行都是重点', await litStars(), n)
    await p.screenshot({ path: `${OUT}/ff4-reminders-focus.png` })
  }

  // ================= C. 卡片/汇总与列表同口径 =================
  console.log('\n=== C. 卡片数字必须与列表同口径（不能卡片 10 件、列表 1 条） ===')
  await openPage('/watch')
  {
    await tickFocus()
    await sleep(2000)
    const board = (await apiGet('/watch/board?caseType=CRIMINAL&focusOnly=true')).data
    const listTotal = (await apiGet('/watch/cases?size=20&caseType=CRIMINAL&module=INITIAL&focusOnly=true')).data.total
    // 看板「初查」卡与列表（默认 module=INITIAL）必须是同一个数
    check('C1 盯办看板「初查」卡 = 盯办列表条数', board.initialTotal, listTotal)
    const sum = board.initialTotal + board.detentionTotal + board.bailTotal + board.approvalTotal
    check('C2 看板四卡合计 = 全部重点案件数',
      sum, (await apiGet('/cases?size=20&caseType=CRIMINAL&focusOnly=true')).data.total)
  }

  await openPage('/todos')
  {
    await tickFocus()
    await sleep(2000)
    const n = await rowCount()
    const cardTotal = await p.evaluate(() => {
      const el = document.querySelector('.cf-todo-stat__num')
      return el ? Number(el.innerText.trim()) : null
    })
    check('C3 待办总览卡片「待办总数」= 列表条数', cardTotal, n)
  }

  // ================= D. 权限：普通民警看不到「只看重点」 =================
  console.log('\n=== D. 普通民警不该看到重点筛选（后端 focus 是 @FullAccessOnly） ===')
  {
    // 不动 token，只把本地用户态降级成 STAFF —— /reminders 在普通民警白名单里，能被守卫放行
    await p.evaluate(() => {
      const u = JSON.parse(localStorage.getItem('cf_user') || '{}')
      u.role = 'STAFF'
      u.isFullAccess = false
      localStorage.setItem('cf_user', JSON.stringify(u))
    })
    await openPage('/reminders')
    check('D1 普通民警在到期提醒看不到「重点」列', (await heads())[0] === '重点' ? '有' : '无', '无')
    const has = await p.evaluate(() =>
      [...document.querySelectorAll('.el-checkbox')].filter((x) => x.offsetParent !== null)
        .some((x) => x.innerText.includes('只看重点')))
    check('D2 普通民警看不到「只看重点」勾选框', has, false)
    // 复位，别把降级后的用户态留给后续脚本（localStorage 是同域名共享的）
    await p.evaluate((u) => localStorage.setItem('cf_user', JSON.stringify(u)), {
      username: boss.username, role: boss.role, employeeId: boss.employeeId ?? null,
      name: boss.name, isFullAccess: boss.isFullAccess
    })
  }

  // ================= F. 「只看重点」不能被埋在人堆里（2026-10-12） =================
  // 位置反馈共四轮：①塞在筛选行中间没人看见；②独占一行吊在标题下像孤儿；
  // ③待办总览里贴着「刷新」像附属按钮；④其余三页也要跟待办总览统一成条首筛选片。
  // 现在的约定：**有检索条的（案件管理/案件盯办/待办总览）一律用 `lead` 排条首**，
  // 只有没有检索条的到期提醒才独占一行放页面顶部。
  // 本节把落位钉死，防止又被打回中间那排控件里或右端贴按钮。
  console.log('\n=== F. 「只看重点」的落位（有检索条=条首筛选片；到期提醒=页面顶部） ===')
  for (const [label, hash, mode] of [
    ['案件管理', '/cases', 'lead'],
    ['案件盯办', '/watch', 'lead'],
    ['待办总览', '/todos', 'lead'],
    ['到期提醒', '/reminders', 'top']
  ]) {
    await openPage(hash)
    const r = await p.evaluate((mode) => {
      const t = document.querySelector('.cf-focus-toggle')
      const inline = !!(t && t.classList.contains('is-inline'))
      const lead = !!(t && t.classList.contains('is-lead'))
      const visible = [...document.querySelectorAll('.el-checkbox, .cf-focus-lead')]
        .filter((x) => x.offsetParent !== null
          && (x.innerText || '').includes('只看重点'))
      const out = { exists: !!t, inline, lead, count: visible.length }
      if (!t) return out
      if (mode === 'lead') {
        // 条首筛选片：必须在检索条里，且排在**所有**其他检索控件左侧
        const bar = t.closest('.cf-toolbar, .cf-filter-bar')
        // 注意：候选里要排除筛选片自身的 button，否则 first 永远是它自己
        const first = bar && [...bar.querySelectorAll('.el-input, .el-select, .el-cascader, .el-radio-group, button')]
          .find((x) => !t.contains(x))
        out.inBar = !!bar
        out.leftOfRest = !!(bar && first
          && t.getBoundingClientRect().right <= first.getBoundingClientRect().left + 1)
        // 且必须在表格上方：它管的是下面这张表
        const table = document.querySelector('.el-table')
        out.aboveTable = !!(table && t.getBoundingClientRect().bottom <= table.getBoundingClientRect().top + 1)
      } else {
        const anchor = document.querySelector('.cf-toolbar, .cf-watch__board, .el-row')
        out.aboveAnchor = !!(anchor && t.getBoundingClientRect().bottom <= anchor.getBoundingClientRect().top + 1)
      }
      return out
    }, mode)
    check(`F · ${label} 有且只有一个「只看重点」`, [r.exists, r.count], [true, 1])
    check(`F · ${label} 落位方式符合约定（${mode}）`, mode === 'lead' ? r.lead : r.inline, mode !== 'top')
    if (mode === 'lead') {
      checkTrue(`F · ${label} 在检索条里、且排在所有检索控件最左`, r.inBar && r.leftOfRest, JSON.stringify(r))
      checkTrue(`F · ${label} 在表格上方`, r.aboveTable, JSON.stringify(r))
    } else {
      checkTrue(`F · ${label} 在主要内容（工具条/卡片/图表）上方`, r.aboveAnchor, JSON.stringify(r))
    }
  }

  // ================= G. 勾选后给出提示文案 =================
  console.log('\n=== G. 勾选后有「仅显示已标为重点的案件」提示 ===')
  for (const [label, hash] of [['案件管理', '/cases'], ['案件盯办', '/watch'], ['待办总览', '/todos']]) {
    await openPage(hash)
    await tickFocus()
    await sleep(1500)
    // 用 textContent 而不是 innerText：lead 形态的说明文案放在 .cf-sr-only 裁剪区
// （避免行内文字把检索条挤换行），innerText 对裁剪区元素的计入行为不可靠
    const hint = await p.evaluate(() => {
      const t = document.querySelector('.cf-focus-toggle')
      return t ? t.textContent.replace(/\s+/g, ' ').trim() : ''
    })
    checkTrue(`G · ${label} 提示文案出现`, hint.includes('仅显示已标为重点的案件'), hint)
  }

  // ================= H. 工作台也能一眼看到重点案件 =================
  // 用户要求：「要求在工作台能看到重点案件情况」。
  // 工作台是"今天要盯什么"的入口，重点案件只在案件管理里找，等于这个功能在工作台不存在。
  // 本节钉四件事：KPI 计数、清单、两者同口径、点进去能落到筛选好的列表。
  console.log('\n=== H. 工作台的重点案件（KPI + 清单 + 跳转） ===')
  await openPage('/')
  let dashTotal = 0
  {
    const expTotal = (await apiGet('/cases?size=1&focusOnly=true&status=OPEN')).data.total
    const d = (await apiGet('/cases/dashboard')).data || {}
    dashTotal = d.focusCase || 0

    checkTrue('H1 工作台有「重点案件」KPI',
      await p.evaluate(() => [...document.querySelectorAll('.cf-kpi')]
        .some((x) => x.innerText.includes('重点案件'))))
    check('H2 KPI 计数 = 案件管理 focusOnly+OPEN 的条数', dashTotal, expTotal)
    check('H3 KPI 上显示的数字就是那个计数', await p.evaluate((want) => {
      const k = [...document.querySelectorAll('.cf-kpi')]
        .find((x) => x.innerText.includes('重点案件'))
      return (k?.querySelector('.cf-kpi__value')?.innerText || '').trim()
    }, String(dashTotal)), String(dashTotal))

    checkTrue('H4 有「重点案件」清单面板', await p.evaluate(() => !!document.querySelector('.cf-focus-head')))
    const listInfo = await p.evaluate(() => {
      // 清单面板是页面里第 4 张表（KPI 之后依次是重点/状态图/负载图/已逾期/待指派…），
      // 用标题定位更稳：从 .cf-focus-head 所在的 .cf-panel 内找表格
      const panel = document.querySelector('.cf-focus-head')?.closest('.cf-panel')
      const rows = panel ? [...panel.querySelectorAll('.el-table__row')] : []
      return {
        rows: rows.length,
        allLit: rows.length > 0 && rows.every((r) => !!r.querySelector('.cf-star.is-on')),
        allHighlit: rows.length > 0 && rows.every((r) => r.className.includes('cf-row--focus'))
      }
    })
    check('H5 清单条数 = 接口 focusList 条数', listInfo.rows, (d.focusList || []).length)
    checkTrue('H6 清单每行都已点亮星标', listInfo.allLit, JSON.stringify(listInfo))
    checkTrue('H7 清单每行都有重点高亮（金色边条）', listInfo.allHighlit, JSON.stringify(listInfo))

    // 排序：已逾期的排最前（领导第一眼要看到最急的），未设期限的排最后
    const order = (d.focusList || []).map((c) => c.deadline === null)
    checkTrue('H8 未设期限的重点案件排在最后', order.indexOf(true) === -1 || order.lastIndexOf(false) < order.indexOf(true), JSON.stringify(order))
    const dl = (d.focusList || []).map((c) => c.deadline).filter(Boolean)
    checkTrue('H9 清单按期限升序（越急越靠前）',
      dl.every((v, i) => i === 0 || dl[i - 1] <= v), JSON.stringify(dl))

    // 「其中 N 件已逾期」不能超过清单条数（它是"这 N 条里几条逾期"）
    checkTrue('H10 逾期条数不超过清单条数',
      (d.focusOverdue || 0) <= (d.focusList || []).length,
      `${d.focusOverdue} / ${(d.focusList || []).length}`)
  }

  // 点 KPI 跳案件管理：筛选片必须自动点亮，且列表 = 重点案件
  await openPage('/')
  await p.evaluate(() => {
    const k = [...document.querySelectorAll('.cf-kpi')].find((x) => x.innerText.includes('重点案件'))
    k && k.click()
  })
  await sleep(2600)
  {
    checkTrue('H11 点 KPI 跳到案件管理并带上 focusOnly',
      (await p.evaluate(() => location.hash)).includes('focusOnly=true'),
      await p.evaluate(() => location.hash))
    checkTrue('H12 「只看重点」筛选片自动点亮',
      await p.evaluate(() => !!document.querySelector('.cf-focus-lead.is-on')))
    const n = await rowCount()
    check('H13 跳转后列表条数 = 重点案件总数', n, Math.min(20, dashTotal))
  }

  // 普通民警：工作台不该出现重点 KPI 与清单（他既不能标星，也不该看到一块空面板）
  // 与 D 组同一手法：降级本地用户态，不真换账号（工作台对所有人可进，不需要白名单）
  await p.evaluate(() => {
    const u = JSON.parse(localStorage.getItem('cf_user') || '{}')
    u.role = 'STAFF'
    u.isFullAccess = false
    localStorage.setItem('cf_user', JSON.stringify(u))
  })
  await openPage('/')
  {
    checkTrue('H14 普通民警工作台无「重点案件」KPI',
      await p.evaluate(() => ![...document.querySelectorAll('.cf-kpi')].some((x) => x.innerText.includes('重点案件'))))
    checkTrue('H15 普通民警工作台无「重点案件」清单面板',
      await p.evaluate(() => !document.querySelector('.cf-focus-head')))
  }

  // ================= I. 图表必须跟随重点筛选 =================
  // 用户要求：「点击『只看重点』时，echarts 图也要跟着筛选数据」。
  // 图表走的是独立的 /cases/stats 接口，历史上只传了 days 与案卷类型，
  // 于是出现"列表只剩 3 条、柱状图还是 12 条"这种用户看不懂的错位。
  //
  // 断言方式：**抓 /cases/stats 的实际请求与响应**。
  // 不用 window.__cfCharts 之类钩子——那要求往生产组件里塞调试字段，
  // 测试不该为它污染运行时；而请求层断言恰好就是用户看得见的那条链路。
  console.log('\n=== I. 图表跟随重点筛选（列表与图表同一口径） ===')

  // H 组末尾为了验「普通民警看不到」把本地用户态降级成了 STAFF，这里必须先恢复回去：
  // 焦点开关对普通民警是隐藏的（FocusOnlyToggle 内部按 isFullAccess 收敛），
  // 不恢复就会 point-not-found，然后所有「点了有没有重新取数」的断言集体假失败。
  await p.evaluate(() => {
    const u = JSON.parse(localStorage.getItem('cf_user') || '{}')
    u.role = 'BOSS'
    u.isFullAccess = true
    localStorage.setItem('cf_user', JSON.stringify(u))
  })

  // 记录最近一次 /cases/stats 的查询串与响应体
  const statCalls = []
  p.on('response', async (res) => {
    const u = res.url()
    if (!u.includes('/api/cases/stats')) return
    const entry = { url: u, status: res.status(), body: null }
    statCalls.push(entry)
    try { entry.body = await res.json() } catch (e) { /* 非 JSON 忽略 */ }
  })
  const lastStats = () => statCalls[statCalls.length - 1]
  const qHas = (entry, key) => new URL(entry.url).searchParams.get(key)

  // --- 接口层：focusOnly / 到期桶 / 优先级 三个条件都要被 stats 接受 ---
  {
    const sum = (d) => (d.statusDist || []).reduce((a, x) => a + x.value, 0)
    const all = (await apiGet('/cases/stats?days=14')).data
    const foc = (await apiGet('/cases/stats?days=14&focusOnly=true')).data
    const expAll = sum(all)
    const expFoc = sum(foc)
    const listFoc = (await apiGet('/cases?size=1&focusOnly=true')).data.total

    checkTrue('I1 接口层：focusOnly 让 stats 合计收窄', expFoc < expAll, `${expFoc} vs ${expAll}`)
    check('I2 接口层：只看重点的 stats 合计 = 列表 focusOnly 条数', expFoc, listFoc)
    // 优先级 / 到期桶：这两个维度历史上**根本没接**，接口早支持但前端从不传，
    // 于是图表与列表早就对不上。本次一并接上，所以这里钉住"接了之后确实收窄"。
    const sumUrg = sum((await apiGet('/cases/stats?days=14&priorities=URGENT')).data)
    checkTrue('I3 接口层：优先级筛选生效（特急数 ≤ 特急合计）',
      sumUrg > 0 && sumUrg <= expAll, `${sumUrg} / ${expAll}`)
    const sumOver = sum((await apiGet('/cases/stats?days=14&dueBuckets=OVERDUE')).data)
    checkTrue('I4 接口层：到期桶筛选生效（逾期数 ≤ 全量）',
      sumOver > 0 && sumOver <= expAll, `${sumOver} / ${expAll}`)
  }

  // --- 案件管理：勾「只看重点」后，前端必须重新拉带 focusOnly 的 stats ---
  await openPage('/cases')
  await sleep(1500)
  statCalls.length = 0
  await tickFocus()
  await sleep(2200)
  {
    const e = lastStats()
    checkTrue('I5 案件管理：勾选后重新请求了 stats', !!e, JSON.stringify(statCalls.map((x) => x.url)))
    checkTrue('I6 案件管理：该请求带上了 focusOnly=true', !!e && qHas(e, 'focusOnly') === 'true',
      e ? e.url : 'no call')
    checkTrue('I7 案件管理：响应为 200 且带图表数据', !!e && e.status === 200 && !!e.body?.data?.statusDist,
      e ? JSON.stringify({ status: e.status, has: !!e.body?.data?.statusDist }) : '')
    const bodySum = !!e && e.body?.data
      ? e.body.data.statusDist.reduce((a, x) => a + x.value, 0) : -1
    const listTotal = (await apiGet('/cases?size=1&focusOnly=true')).data.total
    check('I8 案件管理：图表合计 = 列表条数（同口径）', bodySum, listTotal)
  }

  // 再点一次取消勾选：请求必须回到不带 focusOnly，否则取消不掉就永远只剩重点
  statCalls.length = 0
  await tickFocus()
  await sleep(2200)
  {
    const e = lastStats()
    checkTrue('I9 案件管理：取消勾选后请求不再带 focusOnly', !!e && !qHas(e, 'focusOnly'), e ? e.url : 'no call')
    const bodySum = !!e && e.body?.data
      ? e.body.data.statusDist.reduce((a, x) => a + x.value, 0) : -1
    const listAll = (await apiGet('/cases?size=1')).data.total
    check('I10 案件管理：取消后图表回到全量', bodySum, listAll)
  }

  // 优先级与到期桶下拉同样要走 reload（只刷列表会把图表落在旧口径）
  // 注意：Element Plus 的 option 元素上**没有 data-value**（实测为 null），
  // 只能按可见文本匹配。原来写成 `data-value === vv || textContent.trim()`，
  // 兜底的 truthy 判断让 find 永远返回第一个可见项，于是点到了「特急」以外的东西、静默失败。
  for (const [label, optText, q, val] of [
    ['优先级', '特急', 'priorities', 'URGENT'],
    ['到期', '已逾期', 'dueBuckets', 'OVERDUE']
  ]) {
    await openPage('/cases')
    await sleep(1500)
    statCalls.length = 0
    await p.evaluate((lab) => {
      const sel = [...document.querySelectorAll('.cf-toolbar .el-select')]
        .find((s) => s.textContent.includes(lab))
      if (sel) sel.querySelector('input').click()
    }, label)
    await sleep(900)
    await p.evaluate((txt) => {
      const li = [...document.querySelectorAll('.el-select-dropdown__item')]
        .filter((x) => x.offsetParent !== null)
        .find((x) => x.textContent.trim() === txt)
      li && li.click()
    }, optText)
    await sleep(2200)
    const e = lastStats()
    checkTrue(`I11 案件管理：改「${label}」后重新请求了 stats`, !!e, JSON.stringify(statCalls.map((x) => x.url)))
    checkTrue(`I12 案件管理：该请求带上了 ${q}=${val}`, !!e && qHas(e, q) === val, e ? e.url : 'no call')
  }

  // --- 到期提醒：那页的账龄图与未来到期图同样要跟随 ---
  await openPage('/reminders')
  await sleep(1500)
  statCalls.length = 0
  await p.evaluate(() => {
    const cb = [...document.querySelectorAll('.el-checkbox')]
      .filter((x) => x.offsetParent !== null)
      .find((x) => x.textContent.includes('只看重点'))
    cb && cb.click()
  })
  await sleep(2200)
  {
    const e = lastStats()
    checkTrue('I13 到期提醒：勾选后重新请求了 stats', !!e, '')
    checkTrue('I14 到期提醒：该请求带上了 focusOnly=true', !!e && qHas(e, 'focusOnly') === 'true',
      e ? e.url : 'no call')
    const bodySum = !!e && e.body?.data?.overdueAgeDist
      ? e.body.data.overdueAgeDist.reduce((a, x) => a + x.value, 0) : -1
    const expAge = ((await apiGet('/cases/stats?days=14&focusOnly=true')).data.overdueAgeDist || [])
      .reduce((a, x) => a + x.value, 0)
    check('I15 到期提醒：账龄图合计 = 接口 focusOnly 口径', bodySum, expAge)
  }

  // --- 案件盯办：看板三张图的数据来自 /watch/board，本就带 focusOnly，钉住别退化 ---
  await openPage('/watch')
  await sleep(1500)
  await tickFocus()
  await sleep(2200)
  {
    const board = await p.evaluate(() => document.querySelectorAll('.cf-watch__card').length)
    checkTrue('I16 案件盯办：勾选后看板卡片仍在（/watch/board 本就带 focusOnly）', board > 0, `cards=${board}`)
  }

  const realErrs = errs.filter((e) => !e.includes('Failed to load resource'))
  console.log('\n非资源类控制台错误：' + (realErrs.length ? realErrs.join('\n  ') : 'none'))
  check('E0 全程无 JS 异常', realErrs, [])

  await b.close()
  console.log(`\n通过 ${pass} / 失败 ${fail}`)
  process.exit(fail ? 1 : 0)
}
run().catch((e) => { console.error(e); process.exit(1) })
