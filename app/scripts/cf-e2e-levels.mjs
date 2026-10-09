// 端到端：三级浏览（大类卡片 → 小类卡片 → 栏目页）与「逐级退回」
//
// 覆盖点（2026-10-09 改造）：
//   1) 未选类型进受门控栏目 → 落到大类卡片页（第 1 级）
//   2) 选大类 → 一律进小类卡片页（第 2 级），不再直达栏目
//   3) 选小类 → 落到原栏目并带上 category 筛选
//   4) 顶栏按钮逐级变：返回类别 → 返回大类 → 退出类型
//   5) 「待办总览」这类没有小类维度的栏目：第 2 级只给一张「全部」卡，且能落到 /todos
//
// 用法：node scripts/cf-e2e-levels.mjs   （前端 5173 与后端 8080 需已启动）
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
const checkIn = (name, got, sub) => {
  if (String(got).includes(sub)) { pass++; console.log('  OK   ' + name) }
  else { fail++; console.log('  FAIL ' + name + '\n       got  ' + JSON.stringify(got) + '\n       应包含 ' + sub) }
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

  // ---- 登录，并清掉类型选择（模拟"这次会话还没选过类型"）----
  await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  await p.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'boss', password: 'admin123' })
    })
    const j = await r.json()
    const { token, ...ui } = j.data
    localStorage.setItem('cf_token', token)
    localStorage.setItem('cf_user', JSON.stringify(ui))
    localStorage.removeItem('cf_case_type')
  })

  /** 当前页面状态快照 */
  const snap = () => p.evaluate(() => ({
    hash: location.hash,
    levelLabel: document.querySelector('.cf-ctypebar__label')?.innerText.trim() || '',
    tags: [...document.querySelectorAll('.cf-ctypebar__tag')].map((x) => x.innerText.trim()),
    btn: document.querySelector('.cf-ctypebar__exit')?.innerText.trim() || '',
    pickerCards: [...document.querySelectorAll('.cf-picker__card .cf-picker__badge')].map((x) => x.innerText.trim()),
    boardCards: [...document.querySelectorAll('.cf-boards__card')].map((x) => x.innerText.replace(/\s+/g, ' ').trim()),
    pickerTitle: document.querySelector('.cf-picker__title')?.innerText.trim() || '',
    gatebar: document.querySelector('.cf-gatebar')?.innerText.replace(/\s+/g, ' ').trim() || '',
    rows: document.querySelectorAll('.el-table__row').length
  }))

  const clickCard = (text) => p.evaluate((t) => {
    const el = [...document.querySelectorAll('.cf-picker__card, .cf-boards__card')]
      .find((c) => c.innerText.includes(t))
    if (!el) return false
    el.click()
    return true
  }, text)

  const clickBack = () => p.evaluate(() => {
    const btn = document.querySelector('.cf-ctypebar__exit')
    if (!btn) return false
    btn.click()
    return true
  })

  const waitHash = async (sub, ms = 8000) => {
    for (let i = 0; i < ms / 200; i++) {
      if (await p.evaluate((s) => location.hash.includes(s), sub)) return true
      await sleep(200)
    }
    return false
  }

  // ================= 场景 A：案件管理（支持小类） =================
  console.log('\n[A] 未选类型进「案件管理」→ 应落到大类卡片页')
  await p.goto(`${BASE}/#/cases`, { waitUntil: 'networkidle2' })
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(1200)
  await waitHash('/case-type')
  let s = await snap()
  check('落在第 1 级 /case-type', s.hash.split('?')[0], '#/case-type')
  check('三个大类卡片', s.pickerCards, ['刑事案件', '行政案件', '其他案件'])
  console.log('  标题 =', s.pickerTitle)
  await p.screenshot({ path: `${OUT}/L1-types.png` })

  console.log('\n[B] 点「刑事」→ 应进第 2 级小类卡片页')
  await clickCard('刑事案件')
  await waitHash('/case-boards')
  await sleep(1600)
  s = await snap()
  checkIn('落在第 2 级 /case-boards', s.hash, '/case-boards')
  checkIn('落点参数 to=/cases', s.hash, 'to=/cases')
  check('层级提示=选择小类', s.levelLabel, '选择小类')
  check('返回按钮=返回大类', s.btn, '返回大类')
  checkIn('出现「电诈」小类卡', JSON.stringify(s.boardCards), '电诈')
  checkIn('出现「接触性诈骗」小类卡', JSON.stringify(s.boardCards), '接触性诈骗')
  checkIn('出现「未分类」小类卡', JSON.stringify(s.boardCards), '未分类')
  console.log('  小类卡 =', s.boardCards.map((x) => x.replace(/共 \d+ 件/, '').trim()))
  await p.screenshot({ path: `${OUT}/L2-small-cats.png` })

  console.log('\n[C] 点「电诈」→ 应落到案件管理并带上小类筛选')
  await clickCard('电诈')
  await waitHash('/cases')
  await sleep(1800)
  s = await snap()
  checkIn('落到案件管理', s.hash, '/cases')
  checkIn('URL 带 category=电诈', decodeURIComponent(s.hash), 'category=电诈')
  check('类型条两个标签=刑事/电诈', s.tags, ['刑事案件', '电诈'])
  check('返回按钮=返回类别', s.btn, '返回类别')
  checkIn('页内类型条回显类别', s.gatebar, '电诈')
  console.log('  列表行数 =', s.rows, ' 页内条 =', s.gatebar)
  await p.screenshot({ path: `${OUT}/L3-case-list.png` })

  console.log('\n[D] 逐级退回：返回类别 → 返回大类 → 退出类型')
  await clickBack()
  await waitHash('/case-boards')
  await sleep(1500)
  s = await snap()
  check('退到第 2 级', s.hash.split('?')[0], '#/case-boards')
  check('按钮变返回大类', s.btn, '返回大类')

  await clickBack()
  await waitHash('/case-type')
  await sleep(1200)
  s = await snap()
  check('退到第 1 级', s.hash.split('?')[0], '#/case-type')
  check('按钮变退出类型', s.btn, '退出类型')
  check('大类卡仍高亮原选择', s.tags.includes('刑事案件'), true)
  await p.screenshot({ path: `${OUT}/L1-back.png` })

  await clickBack()
  await sleep(1200)
  s = await snap()
  check('退出类型后类型条消失', s.tags, [])
  check('退出类型后仍在选择页', s.hash.split('?')[0], '#/case-type')
  check('三个大类卡片仍在', s.pickerCards, ['刑事案件', '行政案件', '其他案件'])

  // ================= 场景 B：待办总览（无小类维度） =================
  console.log('\n[E] 未选类型进「待办总览」→ 第 2 级只给一张「全部」卡')
  await p.evaluate(() => localStorage.removeItem('cf_case_type'))
  await p.goto(`${BASE}/#/todos`, { waitUntil: 'networkidle2' })
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(1200)
  await waitHash('/case-type')
  await clickCard('刑事案件')
  await waitHash('/case-boards')
  await sleep(1600)
  s = await snap()
  checkIn('落点参数 to=/todos', s.hash, 'to=/todos')
  check('只有一张卡', s.boardCards.length, 1)
  checkIn('那张卡是「全部」', s.boardCards[0] || '', '全部')
  checkIn('说明该栏目不按小类划分', s.boardCards[0] || '', '不按小类划分')
  // 这张卡通往待办总览而不是案件列表，挂"共 N 件案件"会让人误读，必须不显示数字
  check('该卡不显示案件数', (s.boardCards[0] || '').includes('共 '), false)
  console.log('  卡片 =', s.boardCards[0])
  await p.screenshot({ path: `${OUT}/L2-todos-all.png` })

  console.log('\n[F] 点「全部」→ 应落到待办总览（不是案件管理）')
  await clickCard('全部')
  await waitHash('/todos')
  await sleep(1800)
  s = await snap()
  checkIn('落到待办总览', s.hash, '/todos')
  check('返回按钮=返回类别', s.btn, '返回类别')
  await p.screenshot({ path: `${OUT}/L3-todos.png` })

  console.log('\n[G] 从待办总览退回第 2 级，落点仍是待办总览')
  await clickBack()
  await waitHash('/case-boards')
  await sleep(1500)
  s = await snap()
  checkIn('回小类页且落点仍是 /todos', s.hash, 'to=/todos')
  check('只有一张「全部」卡', s.boardCards.length, 1)

  console.log('\n控制台错误:', errs.length ? errs.slice(0, 6) : '无')
  if (errs.length) fail++
  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  await b.close()
  process.exit(fail ? 1 : 0)
}

run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
