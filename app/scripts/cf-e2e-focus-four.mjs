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
  const tickFocus = async () => p.evaluate(() => {
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

  const realErrs = errs.filter((e) => !e.includes('Failed to load resource'))
  console.log('\n非资源类控制台错误：' + (realErrs.length ? realErrs.join('\n  ') : 'none'))
  check('E0 全程无 JS 异常', realErrs, [])

  await b.close()
  console.log(`\n通过 ${pass} / 失败 ${fail}`)
  process.exit(fail ? 1 : 0)
}
run().catch((e) => { console.error(e); process.exit(1) })
