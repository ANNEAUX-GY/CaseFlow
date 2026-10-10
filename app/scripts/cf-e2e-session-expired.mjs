// 端到端：令牌失效（后端重启 / 过期）时不该再出现「一屏 401 + 满屏 0」
//
// 起因（用户反馈原话）：
//   「为什么我每次启动都会这样，然后每次都得先退出再输账号进入才能正常显示」
//   截图里工作台顶部叠了 7 条 "Request failed with status code 401"，所有数字都是 0。
//
// 根因：后端令牌是内存态（TokenStore），后端每次启动都是全新的空会话表；
//       浏览器 localStorage 里的旧 cf_token 还在，路由守卫只看"有没有 token"就放行，
//       工作台一进去并发打十几个请求全部 401，而响应拦截器当时只特判了 403，
//       401 落到兜底分支 → 只弹一句英文报错，不清登录态、不跳登录页。
//
// 断言：
//   A 令牌有效 → 正常进工作台（确认没把好路径弄坏）
//   B 令牌失效 → 直接被送到登录页并说明原因，本地令牌被清掉
//   C 全程不再出现 "Request failed with status code 401"（也不该有整屏红条）
//   D 重新登录后能正常回到工作台且数据非空
//
// 「令牌失效」的造法：把 cf_token 改成一个后端不认识的假值。
// 与"后端重启导致旧令牌作废"在服务端眼里完全等效，且不用真的重启后端。
//
// 用法：node scripts/cf-e2e-session-expired.mjs   （前端 5173、后端 8080 需已启动）
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

  // 收集控制台错误 —— 用户看到的"红条"就是这些
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 200)) })

  /**
   * 浏览器对"请求返回 4xx"自己会记一条 `Failed to load resource: ... 401`。
   * 这个场景下 401 是我们**故意**造出来的（拿废令牌去探服务端），
   * 它不代表页面出错——要断言的是"页面上不再叠一屏红条"，所以把它单独归类。
   */
  const resourceErrs = () => errs.filter((e) => e.includes('Failed to load resource'))
  const realErrs = () => errs.filter((e) => !e.includes('Failed to load resource'))

  // 页面上当前可见的 Element Plus 提示条文本
  const toasts = () => p.evaluate(() =>
    [...document.querySelectorAll('.el-message')].map((x) => x.innerText.trim()))
  const token = () => p.evaluate(() => localStorage.getItem('cf_token'))
  const hash = () => p.evaluate(() => location.hash)

  /**
   * 提示条只有 3 秒寿命，靠"事后拍一张快照"很容易刚好错过
   * （何况登录页拿到 expired 后会立刻 router.replace 把 query 抹掉）。
   * 所以在文档创建时就挂一个记录器：MutationObserver 一有 .el-message 就记下来，
   * 再短的提示也跑不掉。reload 后依然生效。
   */
  await p.evaluateOnNewDocument(() => {
    window.__msgLog = []
    window.__navLog = []
    const boot = () => {
      if (!document.body) return setTimeout(boot, 10)
      const scan = () => {
        document.querySelectorAll('.el-message').forEach((el) => {
          const t = el.innerText.trim()
          if (t && !window.__msgLog.includes(t)) window.__msgLog.push(t)
        })
        if (!window.__navLog.includes(location.hash)) window.__navLog.push(location.hash)
      }
      new MutationObserver(scan).observe(document.body, { childList: true, subtree: true })
      window.addEventListener('hashchange', scan)
      scan()
    }
    boot()
  })
  const msgLog = () => p.evaluate(() => window.__msgLog || [])
  const navLog = () => p.evaluate(() => window.__navLog || [])

  const loginByApi = () => p.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'boss', password: 'admin123' })
    })
    const j = await r.json()
    const { token, ...ui } = j.data
    localStorage.setItem('cf_token', token)
    localStorage.setItem('cf_user', JSON.stringify(ui))
    localStorage.setItem('cf_case_type', 'CRIMINAL')
    return token
  })

  try {
    // ============ A. 令牌有效：正常进工作台 ============
    console.log('=== A. 令牌有效（回归：好路径不能被弄坏）===')
    await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
    const good = await loginByApi()
    await p.goto(`${BASE}/#/dashboard`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(2500)
    check('A1 落在工作台', await hash(), '#/dashboard')
    const cards = await p.evaluate(() =>
      [...document.querySelectorAll('.cf-stat__value, .cf-kpi__value, .cf-stat-value')]
        .map((x) => x.innerText.trim()).filter(Boolean))
    checkTrue('A2 工作台拿到数据（不是满屏 0）',
      cards.length > 0 && cards.some((v) => v !== '0'), JSON.stringify(cards))
    check('A3 无 401 报错', errs.filter((e) => e.includes('401')), [])

    // ============ B. 令牌失效：应被送去登录页 ============
    console.log('\n=== B. 令牌失效（等价于后端重启）===')
    // 造一个后端不认识的令牌，模拟"后端重启后旧令牌作废"。
    // 这里**只 reload 一次**：若先 goto 再 reload 就成了两遍加载，
    // 第一遍已经把废令牌清掉，第二遍走的是"压根没令牌"那条分支（不带 expired），
    // 断言就会假失败——本脚本第一次写就踩了这个坑。
    await p.evaluate(() => localStorage.setItem('cf_token', 'stale-token-from-previous-run'))
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(3000)
    const msgs = await msgLog()
    console.log('  途径的地址 =', JSON.stringify(await navLog()))
    console.log('  出现过的提示 =', JSON.stringify(msgs))

    checkTrue('B1 被送到登录页，而不是停在工作台',
      (await hash()).startsWith('#/login'), await hash())
    checkTrue('B2 登录页说明了原因（收到了 expired 标记）',
      msgs.some((t) => t.includes('登录已过期')), JSON.stringify(msgs))
    check('B3 失效令牌已从本地清掉（避免下次再撞）', await token(), null)

    checkTrue('B4 全程只提示一次，不是一屏红条',
      msgs.filter((t) => t.includes('登录已过期')).length === 1, JSON.stringify(msgs))
    checkTrue('B5 没有 "Request failed with status code 401" 这种英文报错',
      !msgs.some((t) => t.includes('status code')), JSON.stringify(msgs))
    checkTrue('B6 页面正文没有 401 字样残留',
      !(await p.evaluate(() => document.body.innerText.includes('Request failed with status code'))))
    check('B7 没有任何非资源类的控制台报错（JS 异常为 0）', realErrs(), [])
    // 精心造的 401 只应发生在探测那一次；把它显式记下来，便于回看是否又退化成"一屏 401"
    console.log('  （预期内的 401 探测次数 = ' + resourceErrs().length + '）')
    await p.screenshot({ path: `${OUT}/SX1-expired-to-login.png` })

    // ============ C. 重新登录后恢复 ============
    console.log('\n=== C. 重新登录后回到正常 ===')
    await loginByApi()
    await p.goto(`${BASE}/#/dashboard`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(2500)
    check('C1 重新登录后能进工作台', await hash(), '#/dashboard')
    const cards2 = await p.evaluate(() =>
      [...document.querySelectorAll('.cf-stat__value, .cf-kpi__value, .cf-stat-value')]
        .map((x) => x.innerText.trim()).filter(Boolean))
    checkTrue('C2 数据正常展示', cards2.some((v) => v !== '0'), JSON.stringify(cards2))
    checkTrue('C3 新令牌与首次的不是同一个（确实重新登录过）', (await token()) !== good)
    await p.screenshot({ path: `${OUT}/SX2-relogin-ok.png` })

    // 再来一次：确认这不是"一次性"处理，第二次失效同样能被挡住（同样只 reload 一遍）
    console.log('\n=== D. 重复失效也要挡得住 ===')
    await p.evaluate(() => localStorage.setItem('cf_token', 'stale-again'))
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(2500)
    checkTrue('D1 第二次失效仍然落到登录页', (await hash()).startsWith('#/login'), await hash())
    const msgs2 = await msgLog()
    checkTrue('D2 第二次也说明了原因', msgs2.some((t) => t.includes('登录已过期')), JSON.stringify(msgs2))

    // ============ E. 后端不可达：同样只提示一次 ============
    // 同类问题：后端没就绪 / 断网时，页面并发请求会各弹一条"网络异常"，
    // 一样是"一屏红条"。用请求拦截把 /api 全部掐掉来复现。
    console.log('\n=== E. 后端不可达（不铺满"网络异常"）===')
    await loginByApi()
    // 先在正常状态下站到工作台，再掐网重载 —— 这样一进页面就是十几个并发请求
    await p.goto(`${BASE}/#/dashboard`, { waitUntil: 'networkidle2' })
    await sleep(800)
    // 注意匹配的是 URL 路径以 /api/ 开头（后端代理），
    // 不能写 includes('/api/')——前端源码里的 /src/api/index.js 也会被误伤，
    // 结果把应用自己的模块掐了，页面直接白屏（踩过）。
    const isBackendApi = (u) => {
      try {
        return new URL(u).pathname.startsWith('/api/')
      } catch (e) {
        return false
      }
    }
    const abortApi = (req) => {
      if (isBackendApi(req.url())) req.abort()
      else req.continue()
    }
    // 先挂处理器、再开拦截：反过来中间到达的请求会一直悬着，页面根本渲染不出来
    p.on('request', abortApi)
    await p.setRequestInterception(true)
    await p.reload({ waitUntil: 'domcontentloaded' })
    await sleep(4000)
    const msgs3 = await msgLog()
    console.log('  出现过的提示 =', JSON.stringify(msgs3))
    console.log('  当前地址 =', await hash())
    console.log('  页面文字 =', JSON.stringify((await p.evaluate(() => document.body.innerText)).slice(0, 140)))
    checkTrue('E1 只提示一次，不是每个请求各弹一条',
      msgs3.length === 1, JSON.stringify(msgs3))
    checkTrue('E2 提示语说明是连不上服务',
      msgs3.some((t) => t.includes('无法连接服务')), JSON.stringify(msgs3))
    // 网络不通不能误判成掉线：令牌要留着，否则后端一起来就被踢去登录了
    checkTrue('E3 网络问题不清登录态（后端恢复后还能用）', !!(await token()))
    p.off('request', abortApi)
    await p.setRequestInterception(false)

    const real = realErrs()
    console.log('\n非资源类控制台错误：' + (real.length ? real.join('\n  ') : 'none'))
    console.log('（预期内的 401 探测记录：' + resourceErrs().length + ' 条，属故意制造，不算失败）')
  } finally {
    await b.close().catch(() => {})
  }

  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail > 0 ? 1 : 0)
}

run().catch((e) => { console.error(e); process.exit(1) })
