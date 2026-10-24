// 为《用户使用手册》批量截取真实界面（面向中老年用户的图解版）
// 用法：node cf-manual-shots.mjs
// 前置：后端 8080 + 前端 5173 均已启动
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import fs from 'fs'

const OUT = 'D:/盯办/CaseFlow开发环境包/app/docs/shots'
fs.mkdirSync(OUT, { recursive: true })

const BASE = 'http://127.0.0.1:5173'
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
const waitFor = async (p, s, t = 15000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < t) {
    if (await p.evaluate((x) => !!document.querySelector(x), s)) return true
    await wait(400)
  }
  return false
}

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
  args: ['--no-sandbox', '--font-render-hinting=none']
})

const page = await browser.newPage()
const errs = []
page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
page.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 120)) })

// 登录：注入 localStorage（同时预置案件类型，绕过 5 个栏目的门控页）
const login = async (u, pw) => page.evaluate(async (u, pw) => {
  const r = await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: pw })
  })
  const j = await r.json()
  if (j.code !== 0) throw new Error('登录失败 ' + u + ': ' + j.msg)
  const { token, ...ui } = j.data
  localStorage.setItem('cf_token', token)
  localStorage.setItem('cf_user', JSON.stringify(ui))
  localStorage.setItem('cf_case_type', 'CRIMINAL')
}, u, pw)

const goto = async (hash) => {
  await page.goto(BASE + '/#' + hash, { waitUntil: 'networkidle2' })
  await page.reload({ waitUntil: 'networkidle2' })
}

const shot = async (name) => {
  await wait(700)
  await page.screenshot({ path: `${OUT}/${name}.png` })
  console.log('  ✓', name)
}

// 按 innerText 文字点按钮（el-select / el-cascader 是 append-to-body 的，page.click 点不到）
const clickByText = async (sel, re, idx = 0) => page.evaluate((sel, re, idx) => {
  const list = [...document.querySelectorAll(sel)].filter((x) => new RegExp(re).test(x.innerText || ''))
  if (!list[idx]) return false
  list[idx].click()
  return true
}, sel, re, idx)

await page.setViewport({ width: 1440, height: 900 })
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle2' })
await login('boss', 'admin123')

console.log('开始截图（管理员视角）')

// ---------- 1. 登录页（要未登录状态，单独开页） ----------
{
  const anon = await browser.newPage()
  await anon.setViewport({ width: 1440, height: 900 })
  await anon.goto(BASE + '/#/login', { waitUntil: 'networkidle2' })
  await wait(1500)
  await anon.screenshot({ path: `${OUT}/01-login.png` })
  console.log('  ✓ 01-login')
  await anon.close()
}

// ---------- 2. 工作台 ----------
await goto('/dashboard')
await waitFor(page, '.cf-echart')
await wait(3000)
await shot('02-dashboard')

// ---------- 3. 案件管理（列表） ----------
await goto('/cases')
await wait(2600)
await shot('03-cases')

// ---------- 4. 新建案件弹窗 ----------
await clickByText('button', '新建案件')
await waitFor(page, '.el-dialog')
await wait(1400)
await shot('04-case-create')
await page.keyboard.press('Escape')
await wait(800)

// ---------- 5. 指派弹窗 ----------
const openedAssign = await page.evaluate(() => {
  const row = document.querySelector('.el-table__row')
  if (!row) return false
  const btn = [...row.querySelectorAll('button')].find((x) => /指派/.test(x.innerText))
  if (btn) { btn.click(); return true }
  return false
})
if (openedAssign) {
  await waitFor(page, '.el-dialog')
  await wait(1500)
  await shot('05-case-assign')
  await page.keyboard.press('Escape')
  await wait(800)
} else {
  console.log('  ! 未找到指派按钮')
}

// ---------- 6. 案件详情抽屉 ----------
const openedDetail = await page.evaluate(() => {
  const row = document.querySelector('.el-table__row')
  if (!row) return false
  const btn = [...row.querySelectorAll('button')].find((x) => /详情/.test(x.innerText))
  if (btn) { btn.click(); return true }
  return false
})
if (openedDetail) {
  await wait(2000)
  await shot('06-case-detail')
  await page.keyboard.press('Escape')
  await wait(900)
}

// ---------- 7. 添加待办弹窗 ----------
const openedTodo = await page.evaluate(() => {
  const row = document.querySelector('.el-table__row')
  if (!row) return false
  const btn = [...row.querySelectorAll('button')].find((x) => /详情/.test(x.innerText))
  if (btn) btn.click()
  return !!btn
})
if (openedTodo) {
  await wait(2000)
  const addBtn = await page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => /添加第一条|＋\s*添加|\+\s*添加/.test(x.innerText))
    if (b) { b.click(); return true }
    return false
  })
  if (addBtn) {
    await wait(1500)
    await shot('07-todo-add')
    await page.keyboard.press('Escape')
    await wait(700)
    await page.keyboard.press('Escape')
    await wait(800)
  } else {
    console.log('  ! 未找到添加待办按钮')
    await page.keyboard.press('Escape')
    await wait(800)
  }
}

// ---------- 8. 案件盯办 ----------
await goto('/watch')
await waitFor(page, '.cf-echart')
await wait(3000)
await shot('08-watch')

// ---------- 9. 侦查详情抽屉（含办案阶段与阶段流转） ----------
// 选待办最多的案件，让阶段进度条有内容（优先匹配有真实待办的那条）
const openedWatch = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.el-table__row')]
  // 优先挑「待办已完成/总数」看起来非 0 的行：办案阶段列里有进度条
  let row = rows.find((r) => /待确认流转|%/.test(r.innerText || ''))
  if (!row) row = rows.find((r) => /未成年人保护跟进/.test(r.innerText || ''))
  if (!row) row = rows[0]
  if (!row) return false
  const btn = [...row.querySelectorAll('button')].find((x) => /侦查详情/.test(x.innerText))
  if (btn) { btn.click(); return true }
  return false
})
if (openedWatch) {
  await wait(2200)
  await shot('09-watch-drawer')
  // 找到「办案阶段」区块并滚到可见
  await page.evaluate(() => {
    const els = [...document.querySelectorAll('*')].filter((e) => e.children.length === 0 && /办案阶段/.test(e.textContent || ''))
    if (els[0]) els[0].scrollIntoView({ block: 'center' })
  })
  await wait(900)
  await shot('09b-watch-stage')
  await page.keyboard.press('Escape')
  await wait(900)
} else {
  console.log('  ! 未找到侦查详情按钮')
}

// ---------- 10. 待办总览 ----------
await goto('/todos')
await wait(2600)
await shot('10-todos')

// ---------- 11. 到期提醒 ----------
await goto('/reminders')
await waitFor(page, '.cf-echart')
await wait(3000)
await shot('11-reminders')

// ---------- 12. 员工图谱 ----------
await goto('/org')
await waitFor(page, '.cf-org__tree')
await wait(2000)
await shot('12-org')

// ---------- 13. 账号管理（点全部账号，避免待审核空页） ----------
await goto('/users')
await wait(2000)
await clickByText('.el-radio-button__inner', '全部账号')
await wait(1600)
await shot('13-users')

// ---------- 14. 类别管理 ----------
await goto('/categories')
await wait(2000)
await shot('14-categories')

// ---------- 15. 案件类型选择页 ----------
await goto('/case-type')
await wait(1800)
await shot('15-case-type')

// ---------- 民警视角 ----------
console.log('开始截图（民警视角）')
{
  const staff = await browser.newPage()
  staff.on('pageerror', (e) => errs.push('STAFF PAGEERROR: ' + e.message))
  await staff.setViewport({ width: 1440, height: 900 })
  await staff.goto(BASE + '/#/login', { waitUntil: 'networkidle2' })
  // 先找库里有哪个民警账号
  const names = await staff.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'boss', password: 'admin123' })
    })
    const j = await r.json()
    const token = j.data.token
    const rr = await fetch('/api/users?page=1&size=50', { headers: { 'X-Token': token } })
    const uu = await rr.json()
    const list = (uu.data && (uu.data.list || uu.data)) || []
    return list.filter((x) => x.role === 'STAFF' && x.auditStatus !== 'REJECTED')
      .map((x) => ({ u: x.username, n: x.name, s: x.status }))
  })
  console.log('  民警账号候选:', JSON.stringify(names))

  let ok = false
  // 优先用 test1（库里有真实案件与待办），密码逐一试探
  const ordered = [
    { u: 'test1', pw: '123456' },
    { u: 'test1', pw: 'e2e123456' },
    { u: '13900000003', pw: 'e2e123456' },
    { u: 'e2e_staff', pw: 'e2e123456' }
  ]
  for (const cand of ordered) {
    const r = await staff.evaluate(async (u, p) => {
      try {
        const r = await fetch('/api/auth/login', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: u, password: p })
        })
        const j = await r.json()
        return j.code === 0 ? j.data : null
      } catch (e) { return null }
    }, cand.u, cand.pw)
    if (r) {
      await staff.evaluate((d) => {
        const { token, ...ui } = d
        localStorage.setItem('cf_token', token)
        localStorage.setItem('cf_user', JSON.stringify(ui))
        localStorage.setItem('cf_case_type', 'CRIMINAL')
      }, r)
      ok = true
      console.log('  用民警账号登录成功:', cand.u)
      break
    }
  }

  if (ok) {
    for (const [hash, name, extra] of [
      ['/my-cases', '16-my-cases'],
      ['/my-todos', '17-my-todos']
    ]) {
      await staff.goto(BASE + '/#' + hash, { waitUntil: 'networkidle2' })
      await staff.reload({ waitUntil: 'networkidle2' })
      await wait(2600)
      await staff.screenshot({ path: `${OUT}/${name}.png` })
      console.log('  ✓', name)
    }
  } else {
    console.log('  ! 没有可用民警账号（密码 e2e123456），跳过民警端截图')
  }
  await staff.close()
}

// ---------- 16/17. 民警端：待办详情与提交反馈 ----------
console.log('补拍民警端关键操作')
{
  const st2 = await browser.newPage()
  st2.on('pageerror', (e) => errs.push('STAFF2 PAGEERROR: ' + e.message))
  await st2.setViewport({ width: 1440, height: 900 })
  await st2.goto(BASE + '/#/login', { waitUntil: 'networkidle2' })
  const r = await st2.evaluate(async () => {
    const rr = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'test1', password: '123456' })
    })
    const j = await rr.json()
    if (j.code !== 0) return null
    const { token, ...ui } = j.data
    localStorage.setItem('cf_token', token)
    localStorage.setItem('cf_user', JSON.stringify(ui))
    localStorage.setItem('cf_case_type', 'CRIMINAL')
    return ui.name || ui.username
  })
  if (!r) {
    console.log('  ! test1 登录失败')
  } else {
    // 待办列表页（不要点进详情，那会跳到案件抽屉）
    await st2.goto(BASE + '/#/my-todos', { waitUntil: 'networkidle2' })
    await st2.reload({ waitUntil: 'networkidle2' })
    await wait(2800)
    await st2.screenshot({ path: `${OUT}/17-my-todos.png` })
    console.log('  ✓ 17-my-todos')

    // 待办详情浮窗：点标题链接打开
    const opened = await st2.evaluate(() => {
      const cands = [...document.querySelectorAll('.el-table__row')]
      for (const row of cands) {
        const link = row.querySelector('.el-link, a, .cf-link')
        if (link) { link.click(); return true }
      }
      return false
    })
    if (opened) {
      await wait(2200)
      await st2.screenshot({ path: `${OUT}/18-todo-detail.png` })
      console.log('  ✓ 18-todo-detail')
      await st2.keyboard.press('Escape')
      await wait(900)
    } else {
      console.log('  ! 待办行内未找到可点标题')
    }

    // 提交反馈弹窗
    const fb = await st2.evaluate(() => {
      const b = [...document.querySelectorAll('button')].find((x) => /提交反馈/.test(x.innerText))
      if (b) { b.click(); return true }
      return false
    })
    if (fb) {
      await wait(1600)
      await st2.screenshot({ path: `${OUT}/19-feedback.png` })
      console.log('  ✓ 19-feedback')
    }
  }
  await st2.close()
}

console.log('\n完成。截图目录:', OUT)
if (errs.length) {
  console.log('\n页面错误', errs.length, '条（前 10）:')
  errs.slice(0, 10).forEach((e) => console.log('  ', e))
} else {
  console.log('无页面 JS 错误')
}

await browser.close()
