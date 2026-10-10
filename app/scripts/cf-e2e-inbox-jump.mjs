// 端到端：信箱「查看案件」跳转（2026-10-11 修的真 bug）
//
// 用户反馈：「点击信箱处查看案件，跳转不到相应的案件」
//
// 两个真原因（都在这里钉死）：
//   1. 案件管理页（CaseList）把「按 ?caseId= 打开详情抽屉」写进了
//      watch(route.query, { immediate: true })——immediate 的 watcher 在 setup 期间同步执行，
//      而它紧接着要 router.replace 抹掉定位参数，等于在「/watch → /cases」这次导航还没落地时
//      发起第二次导航，把这一次 push 顶掉。
//      结果：**只有人在案件管理页自己点才正常；从案件盯办/待办总览/到期提醒点，地址栏变成 /cases 但抽屉不开**。
//   2. 库里躺着一批指向已删案件的信件（本次实测 18 条里 6 条），点进去必然"案件不存在"。
//      对策：删案件连信件一起删 + 启动时清一遍存量（后端 NotificationPurge）。
//
// 覆盖点：
//   A 从案件管理 / 案件盯办 / 到期提醒 三个栏目点信件，都能落到对应案件的详情抽屉
//   B 抽屉里显示的案号 = 那封信所指的案件（不是随便开一个）
//   C 定位参数用完即抹：地址栏不留 caseId，刷新不会重复弹抽屉
//   D 信箱里没有悬空信件（接口侧全量核对：每条 caseId 都取得到案件）
//   E 再点第二封信也能正确切换（不是只能跳一次）
//
// 用法：node scripts/cf-e2e-inbox-jump.mjs   （前端 5173、后端 8080 需已启动）
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
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message.slice(0, 160)))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 160)) })
  await p.setViewport({ width: 1440, height: 950 })

  /** 页面内 fetch（走 Vite 代理，避开沙箱注入的 HTTP_PROXY） */
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

  /** 以 boss 身份打开某个栏目（带 reload，保证 Pinia store 拿到完整用户对象） */
  const openAs = async (hash) => {
    await p.goto(`${BASE}/#${hash}`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(1200)
  }

  /**
   * 打开信箱、切到指定页签、点第 index 条。
   *
   * <p>为什么优先用「已读」页签：点一封未读会把它挪进已读，
   * 跑第二、第三个栏目时未读页签就空了（脚本自己点的），测不下去。
   * 已读页签点了不挪窝，可以反复用同一批信测三个栏目。
   */
  const clickLetter = async (index, tab = 'read') => {
    // 接口侧叫 read/unread，界面上叫 已读/未读——别把两者混着传给查询
    const label = tab === 'read' ? '已读' : '未读'
    await p.evaluate(() => document.querySelector('.cf-notif__bell')?.click())
    await sleep(700)
    await p.evaluate((t) => {
      const btn = [...document.querySelectorAll('.cf-notif__tab')].find((x) => x.innerText.trim().startsWith(t))
      btn && btn.click()
    }, label)
    await sleep(600)
    const picked = await p.evaluate((i) => {
      const items = document.querySelectorAll('.cf-notif__item')
      if (!items[i]) return null
      const t = items[i].querySelector('.cf-notif__item-title')?.innerText.trim() || ''
      items[i].click()
      return t
    }, index)
    await sleep(2300)
    return picked
  }

  /** 取某个页签当前第一条信（API 侧），用于知道"这封信本该落到哪个案件" */
  const firstLetterOf = async (box) =>
    ((await api('GET', `/notifications/list?box=${box}`, null, token)).body?.data || [])[0] || null

  /** 当前打开的详情抽屉信息（抽屉在 fixed 遮罩里，只能按尺寸判可见） */
  const drawer = () => p.evaluate(() => {
    const dr = [...document.querySelectorAll('.el-drawer')].filter((d) => d.getBoundingClientRect().width > 0)
    if (!dr.length) return { open: false, head: null, caseNo: null }
    const head = dr[0].querySelector('.cf-drawer__head')?.innerText.replace(/\s+/g, ' ') || ''
    const m = head.match(/(CA-[\w-]+)/)
    return { open: true, head: head.slice(0, 80), caseNo: m ? m[1] : null }
  })

  // ============ 准备 ============
  await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  const lg = (await api('POST', '/auth/login', { username: 'boss', password: 'admin123' })).body.data
  const token = lg.token
  const { token: _t, ...user } = lg
  await p.evaluate((u, t) => {
    localStorage.setItem('cf_token', t)
    localStorage.setItem('cf_user', JSON.stringify(u))
    localStorage.setItem('cf_case_type', 'CRIMINAL')   // 模拟真实用户：已经选过案件类型
  }, user, token)

  // ================= D. 信箱里没有悬空信件 =================
  console.log('\n=== D. 信箱里没有指向已删案件的悬空信件 ===')
  await openAs('/cases')
  const boxes = []
  for (const box of ['unread', 'read']) {
    boxes.push(...((await api('GET', `/notifications/list?box=${box}`, null, token)).body?.data || []))
  }
  console.log('  信件总数 =', boxes.length)
  checkTrue('D0 信箱里有信可测（否则后面测不了）', boxes.length > 0, String(boxes.length))
  const orphans = []
  for (const n of boxes) {
    if (!n.caseId) continue
    const r = await api('GET', '/cases/' + n.caseId, null, token)
    if (r.body?.code !== 0 || !r.body?.data) orphans.push({ id: n.id, caseId: n.caseId })
  }
  check('D1 没有一条信指向已删案件（点了打不开的那种）', orphans.length, 0)

  // ================= A/B/C. 三个栏目点信件都能落到对应案件 =================
  for (const [label, hash] of [['案件管理', '/cases'], ['案件盯办', '/watch'], ['到期提醒', '/reminders']]) {
    console.log(`\n=== A/B/C. 从「${label}」点信件 ===`)
    await openAs(hash)

    // 优先用「已读」页签（点已读不会把信挪走，三个栏目可以复用同一批信）
    let box = 'read'
    let first = await firstLetterOf(box)
    if (!first) {
      box = 'unread'
      first = await firstLetterOf(box)
    }
    checkTrue(`A0 「${label}」信箱里有信`, !!first, JSON.stringify(first || {}))
    if (!first) continue
    const wantCase = (await api('GET', '/cases/' + first.caseId, null, token)).body?.data
    const wantNo = wantCase?.caseNo || null
    console.log(`  第 1 封信（${box}）→ 案件 ${first.caseId}（${wantNo}）`)

    await clickLetter(0, box)

    const d = await drawer()
    console.log('  抽屉 =', JSON.stringify(d))
    checkTrue(`A1 从「${label}」点过去，详情抽屉真的开了`, d.open, JSON.stringify(d))
    check(`B1 开的就是那封信所指的案件（案号一致）`, d.caseNo, wantNo)
    checkTrue(`C1 地址栏不再留 caseId（刷新不会重复弹）`,
      !(await p.evaluate(() => location.href)).includes('caseId'), await p.evaluate(() => location.href))
    await p.screenshot({ path: `${OUT}/ib-${hash.replace(/\//g, '')}.png` })
  }

  // ================= C2. 刷新不重复弹 =================
  console.log('\n=== C2. 刷新后不重复打开 ===')
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(1500)
  check('C2 刷新后抽屉是关着的（定位参数已抹干净）', (await drawer()).open, false)

  // ================= E. 再点一封也能切换 =================
  console.log('\n=== E. 连着点第二封（不是只能跳一次）===')
  // 换一条不同的信：已读页签里取第 2 条
  await p.evaluate(() => document.querySelector('.cf-notif__bell')?.click())
  await sleep(700)
  await p.evaluate(() => {
    const btn = [...document.querySelectorAll('.cf-notif__tab')].find((x) => x.innerText.trim().startsWith('已读'))
    btn && btn.click()
  })
  await sleep(600)
  const letters = (await api('GET', '/notifications/list?box=read', null, token)).body?.data || []
  const idx = Math.min(1, letters.length - 1)
  const second = letters[idx]
  console.log('  第 2 封信 =', JSON.stringify(second ? [second.id, second.caseId, (second.title || '').slice(0, 16)] : null))
  const want2 = second ? (await api('GET', '/cases/' + second.caseId, null, token)).body?.data : null
  await p.evaluate((i) => document.querySelectorAll('.cf-notif__item')[i]?.click(), idx)
  await sleep(2300)
  const d2 = await drawer()
  console.log('  抽屉 =', JSON.stringify(d2))
  checkTrue('E1 第二封也能打开', d2.open, JSON.stringify(d2))
  check('E2 开的是第二封那封信的案件（不是上一次的）', d2.caseNo, want2?.caseNo || null)

  console.log('\n控制台错误：' + (errs.length ? errs.join('\n  ') : 'none'))
  await b.close()
  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail > 0 || errs.length > 0 ? 1 : 0)
}

run().catch((e) => { console.error(e); process.exit(1) })
