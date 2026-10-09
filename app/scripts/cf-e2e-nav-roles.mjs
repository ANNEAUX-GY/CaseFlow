// 端到端：侧栏角色分档（2026-10-09）
//
// 需求原话：「普通用户、管理员保持不变，而领导侧边栏不需要有
//           员工图谱 / 类别管理 / 账号管理 这三个栏目」
//
// 三档菜单（判据在 frontend/src/store/user.js 的 navTierOf）：
//   ① STAFF  普通民警   → 我的案件 / 我的待办 / 到期提醒
//   ② LEADER 业务领导   → 工作台 / 案件盯办 / 待办总览 / 案件管理 / 到期提醒
//                         （所长 CHIEF、副所长 DEPUTY_CHIEF、法制员 LAW_OFFICER）
//   ③ ADMIN  系统管理员 → ② 的全部 + 员工图谱 / 类别管理 / 账号管理
//
// 覆盖点：
//   1) 三种角色的侧栏条目**逐项比对**（不多不少，顺序也要对）
//   2) 领导手敲 /org、/categories、/users 被守卫送回落地页（藏菜单不是障眼法）
//   3) 普通民警手敲同样三个地址 → 回「我的案件」
//   4) 全程零控制台错误
//
// 测试数据自管：脚本用 boss 令牌新建 3 个临时领导账号（各带一份员工档案），
// 跑完无论是成功还是抛异常都删掉（finally 里清理），不污染真实账号表。
//
// 用法：node scripts/cf-e2e-nav-roles.mjs   （前端 5173、后端 8080 需已启动）
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

/** 三档菜单的期望值（顺序 = 侧栏从上到下） */
const MENU = {
  STAFF: ['我的案件', '我的待办', '到期提醒'],
  LEADER: ['工作台', '案件盯办', '待办总览', '案件管理', '到期提醒'],
  ADMIN: ['工作台', '案件盯办', '待办总览', '案件管理', '到期提醒', '员工图谱', '类别管理', '账号管理']
}
/** 三个系统管理类栏目：领导与民警都不该进 */
const SYSTEM_PAGES = ['/org', '/categories', '/users']

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

  // 页面内 fetch（走 Vite 代理，避开沙箱注入的 HTTP_PROXY）
  const api = (method, path, body, token) => p.evaluate(async (m, pt, bd, tk) => {
    const r = await fetch('/api' + pt, {
      method: m,
      headers: Object.assign({ 'Content-Type': 'application/json' }, tk ? { 'X-Token': tk } : {}),
      body: bd ? JSON.stringify(bd) : undefined
    })
    let j = null
    try { j = await r.json() } catch (e) { /* 空响应 */ }
    return { status: r.status, body: j }
  }, method, path, body, token)

  await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })

  /** 以某角色打开某个 hash：先写 localStorage 再 reload —— 只改 hash 不会重建 store */
  const openAs = async (ui, token, hash, caseType) => {
    await p.goto(`${BASE}/#/login`, { waitUntil: 'domcontentloaded' })
    await p.evaluate((u, t, ct) => {
      localStorage.setItem('cf_token', t)
      localStorage.setItem('cf_user', JSON.stringify(u))
      if (ct) localStorage.setItem('cf_case_type', ct)
      else localStorage.removeItem('cf_case_type')
    }, ui, token, caseType || '')
    await p.goto(`${BASE}/#${hash}`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(700)
  }

  // 只比栏目名：先把角标（待办未完成数 / 待审核数）摘掉——那是数据，不是菜单结构
  const navItems = () => p.evaluate(() => [...document.querySelectorAll('.cf-nav__menu .el-menu-item')]
    .map((el) => {
      const clone = el.cloneNode(true)
      clone.querySelectorAll('.cf-nav-badge').forEach((x) => x.remove())
      return clone.innerText.replace(/\s+/g, ' ').trim()
    }))
  const hash = () => p.evaluate(() => location.hash)

  /** 真实登录，返回 { token, ui }（ui 不含 token，与 store 落 localStorage 的结构一致） */
  const login = async (username, password) => {
    const r = await api('POST', '/auth/login', { username, password })
    if (r.status !== 200 || !r.body || r.body.code !== 0) {
      throw new Error(`登录 ${username} 失败：${JSON.stringify(r.body)}`)
    }
    const { token, ...ui } = r.body.data
    return { token, ui }
  }

  const boss = await login('boss', 'admin123')

  // ---- 临时领导账号（跑完必删）----
  const TEMP = [
    { username: 'navcheck_chief', role: 'CHIEF' },
    { username: 'navcheck_deputy', role: 'DEPUTY_CHIEF' },
    { username: 'navcheck_law', role: 'LAW_OFFICER' }
  ]
  const created = []   // { userId, employeeId }
  const purge = async (username) => {
    const r = await api('GET', '/users?size=200', null, boss.token)
    const rows = ((r.body || {}).data || {}).records || []
    const hit = rows.find((u) => u.username === username)
    if (!hit) return null
    await api('DELETE', `/users/${hit.id}`, null, boss.token)
    return hit
  }

  try {
    console.log('=== 0. 准备：临时领导账号 ===')
    for (const t of TEMP) {
      await purge(t.username)   // 上一次失败留下的脏账号先清掉
      const r = await api('POST', '/users', {
        username: t.username, password: 'nav123456', displayName: '自检-' + t.username,
        role: t.role, dept: '自检部门',
        newEmployee: { name: '自检-侧栏-' + t.role, dept: '自检部门', title: '组员' }
      }, boss.token)
      if (r.status !== 200 || !r.body || r.body.code !== 0) {
        throw new Error(`新建 ${t.username} 失败：${JSON.stringify(r.body)}`)
      }
      created.push({ userId: r.body.data.id, employeeId: r.body.data.employeeId })
      console.log(`  已建 ${t.username}（${t.role}）账号 id=${r.body.data.id}`)
    }

    console.log('\n=== 1. 系统管理员（BOSS）：菜单保持原样 ===')
    await openAs(boss.ui, boss.token, '/dashboard')
    check('系统管理员侧栏', await navItems(), MENU.ADMIN)
    await p.screenshot({ path: `${OUT}/nav-roles-admin.png` })

    console.log('\n=== 2. 业务领导（所长 / 副所长 / 法制员）：无员工图谱/类别管理/账号管理 ===')
    for (const t of TEMP) {
      const acc = await login(t.username, 'nav123456')
      check(`${t.username} 登录返回的角色`, acc.ui.role, t.role)
      await openAs(acc.ui, acc.token, '/dashboard')
      check(`${t.role} 侧栏`, await navItems(), MENU.LEADER)
      if (t.role === 'CHIEF') await p.screenshot({ path: `${OUT}/nav-roles-leader.png` })
    }

    console.log('\n=== 3. 普通民警（STAFF）：保持原样 ===')
    const staff = await login('test1', '123456')
    await openAs(staff.ui, staff.token, '/my-cases')
    check('普通民警侧栏', await navItems(), MENU.STAFF)

    console.log('\n=== 4. 手敲地址也进不去（藏菜单不是障眼法）===')
    const chief = await login('navcheck_chief', 'nav123456')
    for (const path of SYSTEM_PAGES) {
      await openAs(chief.ui, chief.token, path)
      check(`领导手敲 ${path} → 送回工作台`, await hash(), '#/dashboard')
    }
    for (const path of SYSTEM_PAGES) {
      await openAs(staff.ui, staff.token, path)
      check(`民警手敲 ${path} → 送回我的案件`, await hash(), '#/my-cases')
    }
    // 反证：领导仍能进业务栏目（本次只动系统管理类三项，别误伤）
    await openAs(chief.ui, chief.token, '/dashboard')
    check('领导仍能进工作台', await hash(), '#/dashboard')
    await openAs(chief.ui, chief.token, '/cases', 'CRIMINAL')
    const casesHash = await hash()
    check('领导选了类型后能进案件管理（三级浏览第 2 级也算进）',
      casesHash === '#/cases' || casesHash === '#/case-boards', true)
    console.log('  实际落点 = ' + casesHash)
    await openAs(chief.ui, chief.token, '/watch', 'CRIMINAL')
    const watchHash = await hash()
    check('领导能进案件盯办', watchHash.startsWith('#/watch') || watchHash === '#/case-boards', true)
    console.log('  实际落点 = ' + watchHash)
    await p.screenshot({ path: `${OUT}/nav-roles-redirect.png` })

    console.log('\n控制台错误：' + (errs.length ? errs.join('\n  ') : 'none'))
  } finally {
    console.log('\n=== 5. 清理临时账号与档案 ===')
    for (const c of created) {
      const r = await api('DELETE', `/users/${c.userId}`, null, boss.token)
      console.log(`  删账号 ${c.userId} → ${r.body && r.body.code === 0 ? 'OK' : JSON.stringify(r.body)}`)
      if (c.employeeId) {
        const e = await api('DELETE', `/employees/${c.employeeId}`, null, boss.token)
        console.log(`  删档案 ${c.employeeId} → ${e.body && e.body.code === 0 ? 'OK' : JSON.stringify(e.body)}`)
      }
    }
    // 兜底：万一上面某步没删掉，再按登录名扫一遍
    for (const t of TEMP) {
      const left = await purge(t.username)
      if (left) console.log(`  兜底清掉残留账号 ${t.username}`)
    }
    await b.close()
  }

  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail > 0 || errs.length > 0 ? 1 : 0)
}

run().catch((e) => { console.error(e); process.exit(1) })
