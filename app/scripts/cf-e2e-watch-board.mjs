// 端到端：案件盯办看板的「小类口径」与图表（2026-10-09）
//
// 背景：从「按类别浏览」选完小类进盯办页时，四张卡片仍按**大类**统计，
// 出现过「卡片写 7 件、下面列表只有 2 条」的口径错位。本次统一为：
// 卡片 / 图表 / 列表 全部走当前小类，三者数字必须一致。
//
// 断言口径全部来自 **接口实时返回值**，不是写死的数字——真实业务数据变了也不会假失败。
//
// 覆盖：
//   A. 大类口径：四张卡片 = /watch/board（不带小类）
//   B. 小类口径：地址栏带 category 进来，卡片 = /watch/board（带小类），且提示条显示「类别：X」
//   C. 图表现身：三张图（在办构成 / 措施期限分布 / 各小类案件数）都渲染出非空画布
//   D. 图能不能点：点「各小类案件数」柱体 → 真的筛到该类；再点一次取消
//
// 用法：node scripts/cf-e2e-watch-board.mjs   （前端 5173 与后端 8080 需已启动）
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
    args: ['--no-sandbox', '--window-size=1600,950']
  })
  const p = await b.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 200)) })
  await p.setViewport({ width: 1600, height: 950 })

  // 登录；盯办是门控栏目，必须先选定类型，否则会被弹回类型选择页
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
    localStorage.setItem('cf_case_type', 'CRIMINAL')
  })

  /** 页面内 fetch（同源走 Vite 代理，避开沙箱对本地回环的代理干扰） */
  const api = (path) => p.evaluate(async (p2) => {
    const r = await fetch(p2, { headers: { 'X-Token': localStorage.getItem('cf_token') } })
    return r.json()
  }, '/api' + path)

  /** 四张卡片的数字（顺序：初查 / 刑拘在办 / 取保及监居 / 待审批） */
  const cardValues = () => p.evaluate(() =>
    [...document.querySelectorAll('.cf-watch__card-value')].map((e) => Number(e.innerText.trim())))
  const gateText = () => p.evaluate(() => document.querySelector('.cf-gatebar')?.innerText || '')
  const openWatch = async (query) => {
    await p.goto(`${BASE}/#/watch${query || ''}`, { waitUntil: 'networkidle2' })
    await p.waitForSelector('.cf-watch__card-value')
    await sleep(1800)   // 等 list + board 两次请求都落地
  }

  /** 每张图画布的非空像素数：ECharts 画布背景透明，画了东西才有像素 */
  const canvasInk = () => p.evaluate(() => {
    const out = []
    document.querySelectorAll('.cf-watch__charts .cf-chart').forEach((panel) => {
      const cv = panel.querySelector('canvas')
      const title = panel.querySelector('.cf-panel__head span')?.innerText || '?'
      if (!cv) { out.push({ title, ink: -1, w: 0, h: 0 }); return }
      const ctx = cv.getContext('2d')
      const d = ctx.getImageData(0, 0, cv.width, cv.height).data
      let ink = 0
      for (let i = 3; i < d.length; i += 4) { if (d[i] > 0) ink++ }
      out.push({ title, ink, w: cv.width, h: cv.height })
    })
    return out
  })

  // ================= A. 大类口径 =================
  console.log('\n[A] 不带小类进入盯办：卡片 = 接口（大类口径）')
  await openWatch('')
  const bA = (await api('/watch/board?caseType=CRIMINAL')).data
  const wantA = [bA.initialTotal, bA.detentionTotal, bA.bailTotal, bA.approvalTotal]
  const gotA = await cardValues()
  console.log('  卡片 =', gotA, ' 接口 =', wantA)
  check('四张卡片数字与接口一致', gotA, wantA)
  const gA = await gateText()
  check('提示条未显示「类别：」', gA.includes('类别：'), false)

  // ================= B. 小类口径 =================
  console.log('\n[B] 带小类「电诈」进入盯办：卡片 = 接口（小类口径）')
  await openWatch('?category=' + encodeURIComponent('电诈'))
  const bB = (await api('/watch/board?caseType=CRIMINAL&category=' + encodeURIComponent('电诈'))).data
  const wantB = [bB.initialTotal, bB.detentionTotal, bB.bailTotal, bB.approvalTotal]
  const gotB = await cardValues()
  console.log('  卡片 =', gotB, ' 接口 =', wantB, ' （大类时是', wantA, '）')
  check('四张卡片数字与接口一致', gotB, wantB)
  checkIn('提示条显示「类别：电诈」', await gateText(), '类别：电诈')
  // 卡片数字必须真的被小类收窄过，否则等于没生效（这里电诈是大类的真子集）
  const narrowed = wantB.reduce((s, v) => s + v, 0) < wantA.reduce((s, v) => s + v, 0)
  check('小类口径确实收窄了统计范围', narrowed, true)

  // 列表条数也要跟卡片对上（这正是本次要修的那个错位）
  const listB = (await api('/watch/cases?page=1&size=1&module=INITIAL&caseType=CRIMINAL&category='
    + encodeURIComponent('电诈'))).data
  check('初查卡片数 = 初查列表条数', gotB[0], listB.total)
  await p.screenshot({ path: `${OUT}/W2-scoped-category.png` })

  // ================= C. 图表渲染 =================
  console.log('\n[C] 三张图表都渲染出内容')
  const inks = await canvasInk()
  inks.forEach((c) => console.log(`  「${c.title}」画布 ${c.w}x${c.h}，非空像素 ${c.ink}`))
  check('图表数量 = 3', inks.length, 3)
  check('每张图都有画布', inks.every((c) => c.w > 0 && c.h > 0), true)
  check('每张图都画出了内容', inks.every((c) => c.ink > 500), true)
  checkIn('有「在办案件构成」图', inks.map((c) => c.title).join('|'), '在办案件构成')
  checkIn('有「措施期限分布」图', inks.map((c) => c.title).join('|'), '措施期限分布')
  checkIn('有「各小类案件数」图', inks.map((c) => c.title).join('|'), '各小类案件数')
  // 各小类分布按大类统计：选中小类后这张图不该只剩一根柱子
  const bBig = (await api('/watch/board?caseType=CRIMINAL')).data
  check('各小类分布不受小类筛选影响',
    bB.categoryDist.map((d) => d.code + ':' + d.value), bBig.categoryDist.map((d) => d.code + ':' + d.value))

  // ================= D. 点柱体切换小类 =================
  console.log('\n[D] 点「各小类案件数」柱体 → 真的筛到该类')
  await openWatch('')
  const catChartBox = () => p.evaluate(() => {
    const r = document.querySelectorAll('.cf-watch__charts .cf-chart')[2]
      .querySelector('canvas').getBoundingClientRect()
    return { x: r.x, y: r.y, w: r.width, h: r.height }
  })
  /**
   * 用像素量出第 idx 根柱体的垂直中心，而不是按比例猜。
   * 柱体只占条带的约 3/4（上下各 3px 是视觉间隙），猜 y 很容易点进间隙里点空——
   * 实测踩过：同一个 fy=0.5 有时命中、有时落空。
   * 顺带断言「柱体高度 ≥ 条带的 2/3」，避免以后调样式把可点区域压到点不着。
   */
  const barGeom = (idx) => p.evaluate((i) => {
    const cv = document.querySelectorAll('.cf-watch__charts .cf-chart')[2].querySelector('canvas')
    const ctx = cv.getContext('2d')
    const dpr = cv.width / cv.clientWidth
    const col = ctx.getImageData(Math.round(cv.clientWidth * 0.35 * dpr), 0, 1, cv.height).data
    const runs = []
    let s = null
    for (let y = 0; y < cv.height; y++) {
      const a = col[y * 4 + 3]
      if (a > 0 && s === null) s = y
      if (a === 0 && s !== null) { if (y - s > 4) runs.push([s / dpr, (y - 1) / dpr]); s = null }
    }
    if (s !== null && cv.height - s > 4) runs.push([s / dpr, (cv.height - 1) / dpr])
    const r = runs[i]
    // 带高 = 相邻柱体顶端的间距（不能用「画布高 / 柱数」：那里面还含图例与边距）
    const bandH = runs.length > 1 ? runs[1][0] - runs[0][0] : 0
    return {
      count: runs.length,
      mid: r ? (r[0] + r[1]) / 2 : null,
      barH: r ? r[1] - r[0] + 1 : 0,
      bandH
    }
  }, idx)
  /** 提示条里的当前小类（点图筛选只改组件状态、不改地址栏，所以从这里读） */
  const gateCategory = async () => {
    const t = await gateText()
    const m = t.match(/类别：([^\n]+)/)
    return m ? m[1].trim() : ''
  }
  const clickCatBar = async (idx) => {
    const bx = await catChartBox()
    const g = await barGeom(idx)
    if (g.mid == null) return { ...g, clicked: false }
    const x = bx.x + bx.w * 0.35
    const y = bx.y + g.mid
    await p.mouse.move(x - 20, y)
    await p.mouse.move(x, y)
    await p.mouse.click(x, y)
    await sleep(2000)
    return { ...g, clicked: true }
  }

  const bars = (await api('/watch/board?caseType=CRIMINAL')).data.categoryDist
  const target = bars[1]                       // 第 2 根（条数降序，值≥1，一定够长）
  const boxD = await catChartBox()
  const geom = await barGeom(1)
  console.log('  命中前柱体几何：', JSON.stringify(geom), ' 目标小类 =', target.code)
  check('图上柱体数 = 接口小类数', geom.count, bars.length)
  check('柱体高度 ≥ 条带 2/3（可点区域够大）',
    geom.barH >= geom.bandH * 2 / 3, true)
  console.log('  画布宽', boxD.w, '高', boxD.h)

  const r1 = await clickCatBar(1)
  console.log('  点击后当前小类 =', JSON.stringify(await gateCategory()), '（柱体几何', r1.barH, '/', r1.bandH, '）')
  check('点击柱体筛到了对应小类', await gateCategory(), target.name)
  const bD = (await api('/watch/board?caseType=CRIMINAL&category=' + encodeURIComponent(target.code))).data
  check('点击后卡片 = 该小类接口值',
    await cardValues(), [bD.initialTotal, bD.detentionTotal, bD.bailTotal, bD.approvalTotal])
  await p.screenshot({ path: `${OUT}/W3-click-category.png` })

  console.log('\n[E] 再点同一根柱体 → 取消筛选')
  await clickCatBar(1)
  check('再点一次取消筛选', await gateCategory(), '')
  check('卡片回到大类口径', await cardValues(), wantA)

  // ================= F. 点环形图切页签 =================
  console.log('\n[F] 点「在办案件构成」环形图 → 切到对应页签')
  const moduleName = () => p.evaluate(() =>
    document.querySelector('.el-tabs__item.is-active')?.innerText.trim() || '')
  // 挑一个"非当前页签"的扇区点，才验得出来真的切了（默认就在初查）
  const slices = [
    { key: 'DETENTION', name: '刑拘在办', value: bA.detentionTotal },
    { key: 'BAIL_RESIDENCE', name: '取保及监居', value: bA.bailTotal }
  ].filter((s) => s.value > 0)
  const before = await moduleName()
  console.log('  点击前页签 =', JSON.stringify(before))
  if (!slices.length) {
    check('无强制措施案件，跳过环形图点击', true, true)
  } else {
    const need = slices[0]
    // 扇区中点角度：ECharts 饼图默认 startAngle=90（正上方）顺时针，
    // 先按环形图的数据顺序累加前面的占比，才能算到目标扇区的中线
    const ring = [
      { key: 'INITIAL', value: bA.initialTotal },
      { key: 'DETENTION', value: bA.detentionTotal },
      { key: 'BAIL_RESIDENCE', value: bA.bailTotal }
    ].filter((s) => s.value > 0)
    const tot = ring.reduce((s, d) => s + d.value, 0)
    let acc = 0
    for (const s of ring) { if (s.key !== need.key) acc += s.value; else break }
    const midFrac = (acc + need.value / 2) / tot
    const pt = await p.evaluate((frac) => {
      const cv = document.querySelectorAll('.cf-watch__charts .cf-chart')[0].querySelector('canvas')
      const r = cv.getBoundingClientRect()
      const cx = r.x + r.width * 0.5
      const cy = r.y + r.height * 0.42
      const rad = (Math.min(r.width, r.height) / 2) * 0.62   // 取环带中线
      // ECharts 饼图自 12 点起顺时针扫过 360°，所以「数据位置 p」对应的
      // 顺时针角就是 360×p——这里曾经多加了 90°，结果整圈偏了四分之一、点中隔壁扇区（实测踩过）
      const th = (360 * frac) * Math.PI / 180
      return { x: cx + rad * Math.sin(th), y: cy - rad * Math.cos(th) }
    }, midFrac)
    await p.mouse.move(pt.x - 15, pt.y)
    await p.mouse.move(pt.x, pt.y)
    await p.mouse.click(pt.x, pt.y)
    await sleep(2200)
    const after = await moduleName()
    console.log('  点击后页签 =', JSON.stringify(after), '（目标', need.name, '）')
    check('环形图点击切到了对应页签', after, need.name)
    await p.screenshot({ path: `${OUT}/W4-pie-tab.png` })
  }

  console.log('\n控制台错误：' + (errs.length ? errs.join('\n  ') : 'none'))
  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  await b.close()
  process.exit(fail > 0 || errs.length > 0 ? 1 : 0)
}

run().catch((e) => { console.error(e); process.exit(1) })
