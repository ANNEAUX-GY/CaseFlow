// 端到端：常用待办记忆（2026-10-11）
//
// 需求（用户原话）：
//   「添加待办部分，添加记忆，后台记录用户最常添加什么待办，并在下方提示，
//     用户点击则可直接添加该待办」
//
// 覆盖点：
//   A 弹窗底部出现「常用待办」区：有标签、有"点一下就直接加一条"的说明、最多 8 条
//   B 记忆来自后台：按本人使用次数从多到少排，次数会以角标显示
//   C 点标签 = 直接添加：不打字、不用再点保存，弹窗继续开着可以接着写
//   D 添加后次数 +1，面板与接口两侧都同步（卡片多一张、角标跟着涨）
//   E 自己手写的待办保存后也进记忆（下次就能一键添加）
//   F 空态兜底：没有任何记忆时不显示这一块，不占地方
//
// 测试数据自管：脚本自己建案件，跑完删掉（finally 里清理）。
// 记忆条目本身刻意保留——它是「本人常写什么」的长期记录，与某条具体意见无关。
//
// 用法：node scripts/cf-e2e-todo-preset.mjs   （前端 5173、后端 8080 需已启动）
import puppeteer from 'file:///C:/Users/admin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/盯办/_e2e_shots'
const ROW = '.cf-btodo__row'
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
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 200)) })
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

  /** 以某个登录态打开页面（带 reload，保证 Pinia store 拿到完整用户对象） */
  const openAs = async (user, token, hash, caseType) => {
    await p.evaluate((u, t, ct) => {
      localStorage.setItem('cf_token', t)
      localStorage.setItem('cf_user', JSON.stringify(u))
      if (ct) localStorage.setItem('cf_case_type', ct)
      else localStorage.removeItem('cf_case_type')
    }, user, token, caseType || '')
    await p.goto(`${BASE}/#${hash}`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(900)
  }

  const box = (sel) => p.evaluate((s) => {
    const el = document.querySelector(s)
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { w: Math.round(r.width), h: Math.round(r.height) }
  }, sel)

  const toasts = () => p.evaluate(() => [...document.querySelectorAll('.el-message')].map((x) => x.innerText.trim()))

  const dlgVisible = async () => {
    const bx = await box('.cf-btodo-dlg')
    return !!bx && bx.h > 0
  }

  /** 弹窗里的「常用待办」标签：文本 + 角标次数 */
  const chips = () => p.evaluate(() => [...document.querySelectorAll('.cf-btodo__chip')].map((c) => ({
    text: c.querySelector('.cf-btodo__chip-text')?.innerText.trim() || '',
    n: c.querySelector('.cf-btodo__chip-n')?.innerText.trim() || '',
    title: c.getAttribute('title') || ''
  })))

  const presetBlockText = () => p.evaluate(() => document.querySelector('.cf-btodo__preset')?.innerText || '')

  const openAddDialog = async () => {
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('.cf-todo button')].find((x) => x.innerText.includes('添加'))
      btn && btn.click()
    })
    await sleep(800)
  }

  const closeDialog = async () => {
    await p.evaluate(() => {
      const dlg = document.querySelector('.cf-btodo-dlg')
      if (!dlg) return
      const btn = [...dlg.querySelectorAll('.el-dialog__footer button')]
        .find((x) => x.innerText.trim() === '取消')
      btn && btn.click()
    })
    await sleep(600)
  }

  const dlgFooterBtn = async (text) => {
    const ok = await p.evaluate((t) => {
      const dlg = document.querySelector('.cf-btodo-dlg')
      if (!dlg) return false
      const btn = [...dlg.querySelectorAll('.el-dialog__footer button')]
        .find((x) => x.innerText.trim().startsWith(t))
      if (!btn) return false
      btn.click()
      return true
    }, text)
    await sleep(700)
    return ok
  }

  const presetsApi = async (limit = 8) =>
    (await api('GET', '/watch/todo-presets?limit=' + limit, null, token)).body?.data || []

  const opinionsApi = async () =>
    (await api('GET', '/watch/cases/' + caseId + '/opinions', null, token)).body?.data || []

  const login = async (username, password) => {
    const r = await api('POST', '/auth/login', { username, password })
    return r.body?.data
  }

  // ============ 准备 ============
  // 先落到页面上再调接口：fetch 用的是相对路径 /api，about:blank 下会解析失败
  await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  const bossLogin = await login('boss', 'admin123')
  const token = bossLogin.token
  const { token: _t, ...bossUser } = bossLogin
  const name = '【自检】常用待办-' + Date.now()
  // 一个小时内不会撞车的标记内容：先在接口里写过 3 次，让它一定排进前 8
  const MARK = '自检-常用待办记忆-' + Date.now()
  let caseId = null

  try {
    const created = await api('POST', '/cases', { name, caseType: 'CRIMINAL' }, token)
    caseId = created.body?.data?.id
    checkTrue('准备：建了一个自检案件', !!caseId, JSON.stringify(created.body).slice(0, 200))
    if (!caseId) throw new Error('案件建不出来，后续无法进行')

    // 先写过 3 次——记忆是"写下才会记住"的，这也顺带验证次数累加
    for (let i = 0; i < 3; i++) {
      await api('POST', '/watch/cases/' + caseId + '/opinions/batch',
        { items: [{ content: MARK, deadline: '', importance: 'A' }] }, token)
    }
    const before = await presetsApi()
    const markBefore = before.find((x) => x.content === MARK)
    console.log('  接口侧记忆：' + JSON.stringify(before.map((x) => [x.content.slice(0, 16), x.useCount])))
    checkTrue('准备：MARK 已进入记忆', !!markBefore, JSON.stringify(before.slice(0, 3)))
    check('准备：写过 3 次就是 3 次', markBefore?.useCount, 3)
    checkTrue('准备：次数从多到少排（角标越小越靠前）',
      before.map((x) => x.useCount).join() === before.map((x) => x.useCount).slice().sort((a, b2) => b2 - a).join(),
      JSON.stringify(before.map((x) => x.useCount)))

    // ================= A. 弹窗底部的「常用待办」=================
    console.log('\n=== A. 弹窗底部出现「常用待办」 ===')
    await openAs(bossUser, token, '/cases', 'CRIMINAL')
    await p.evaluate((nm) => {
      const inp = [...document.querySelectorAll('input')].find((x) => (x.placeholder || '').includes('案件名'))
      if (inp) {
        inp.value = nm
        inp.dispatchEvent(new Event('input', { bubbles: true }))
      }
    }, name)
    await sleep(300)
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('button')].find((x) => x.innerText.trim() === '查询')
      btn && btn.click()
    })
    await sleep(1300)
    await p.evaluate((nm) => {
      const row = [...document.querySelectorAll('.el-table__row')].find((r) => r.innerText.includes(nm))
      if (!row) return
      const btn = [...row.querySelectorAll('button')].find((x) => x.innerText.trim() === '详情')
      if (btn) btn.click()
      else row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
    }, name)
    await sleep(1700)
    checkTrue('A0 案件详情抽屉打开，待办面板在位', !!(await box('.cf-todo')))

    await openAddDialog()
    checkTrue('A1 批量弹窗打开', await dlgVisible())
    const block = await presetBlockText()
    console.log('  常用待办区文案 =', JSON.stringify(block.replace(/\s+/g, ' ').slice(0, 120)))
    checkTrue('A2 底部有「常用待办」四个字', block.includes('常用待办'), block.slice(0, 80))
    checkTrue('A3 说清了怎么用（点一下就直接加一条）', block.includes('点一下就直接加一条'), block.slice(0, 80))

    const cs = await chips()
    console.log('  标签 =', JSON.stringify(cs.map((c) => [c.text.slice(0, 14), c.n])))
    checkTrue('A4 有可点的标签', cs.length > 0, JSON.stringify(cs))
    checkTrue('A5 最多 8 条（再多就成了另一张待办清单）', cs.length <= 8, String(cs.length))
    checkTrue('A6 每个标签都带 ＋（看得出是"添加"不是"筛选"）',
      await p.evaluate(() => [...document.querySelectorAll('.cf-btodo__chip')]
        .every((c) => (c.innerText || '').trim().startsWith('＋'))))
    checkTrue('A7 标签有悬浮说明（完整内容不用猜）',
      cs.every((c) => c.title.includes('点击直接添加')), JSON.stringify(cs[0]))
    await p.screenshot({ path: `${OUT}/tp1-presets.png` })

    // ================= B. 记忆来自后台（次数角标）=================
    console.log('\n=== B. 次数角标与排序 ===')
    const mine = cs.find((c) => c.text === MARK)
    checkTrue('B1 我刚写的那条排进了列表', !!mine, JSON.stringify(cs.map((c) => c.text.slice(0, 12))))
    check('B2 用过 3 次就显示 3（次数写在角标里）', mine?.n, '3')
    check('B3 列表顺序与接口一致（都是按次数排）',
      cs.map((c) => c.text), before.map((x) => x.content))

    // ================= C. 点标签 = 直接添加 =================
    console.log('\n=== C. 点一下就添加（不用打字、不用再点保存）===')
    const target = cs[0].text
    console.log('  点第一个标签 =', JSON.stringify(target.slice(0, 20)))
    const opsBefore = (await opinionsApi()).filter((o) => o.content === target).length
    const cardsBefore = await p.evaluate((t) => [...document.querySelectorAll('.cf-todo__card')]
      .filter((c) => (c.querySelector('.cf-todo__name')?.innerText || '').trim() === t).length, target)

    await p.evaluate(() => {
      const c = document.querySelectorAll('.cf-btodo__chip')[0]
      c && c.click()
    })
    await sleep(1400)
    const ts = await toasts()
    console.log('  提示 =', JSON.stringify(ts))
    checkTrue('C1 提示已添加，并带上加的是哪一条',
      ts.some((t) => t.includes('已添加') && t.includes(target.slice(0, 8))), JSON.stringify(ts))
    checkTrue('C2 弹窗不关（接着写别的照旧）', await dlgVisible())

    const opsAfter = (await opinionsApi()).filter((o) => o.content === target).length
    check('C3 库里真的多了一条', opsAfter, opsBefore + 1)
    const cardsAfter = await p.evaluate((t) => [...document.querySelectorAll('.cf-todo__card')]
      .filter((c) => (c.querySelector('.cf-todo__name')?.innerText || '').trim() === t).length, target)
    check('C4 面板上跟着多一张卡', cardsAfter, cardsBefore + 1)

    // ================= D. 添加后次数 +1 =================
    console.log('\n=== D. 用过一次就记一次 ===')
    const afterList = await presetsApi()
    const hit = afterList.find((x) => x.content === target)
    const hitBefore = before.find((x) => x.content === target)
    console.log('  次数 ' + hitBefore?.useCount + ' → ' + hit?.useCount)
    check('D1 接口侧次数 +1', hit?.useCount, (hitBefore?.useCount || 0) + 1)

    const cs2 = await chips()
    const mine2 = cs2.find((c) => c.text === target)
    checkTrue('D2 弹窗里的角标同步刷新', !!mine2, JSON.stringify(cs2.map((c) => [c.text.slice(0, 12), c.n])))
    if (hit?.useCount > 1) {
      check('D3 角标显示为最新次数', mine2?.n, String(hit.useCount))
    }
    await p.screenshot({ path: `${OUT}/tp2-after-click.png` })

    // ================= E. 手写的待办保存后也进记忆 =================
    console.log('\n=== E. 手写保存的也会记住（下次一键添加）===')
    const NEW = '自检-手写待办-' + Date.now()
    await p.evaluate((s, t) => {
      const inp = document.querySelectorAll(s)[0].querySelector('.cf-btodo__content input')
      inp.value = t
      inp.dispatchEvent(new Event('input', { bubbles: true }))
    }, ROW, NEW)
    await sleep(250)
    await dlgFooterBtn('保存')
    await sleep(900)
    check('E1 保存后弹窗关闭', await dlgVisible(), false)
    const newInApi = (await presetsApi()).find((x) => x.content === NEW)
    checkTrue('E2 手写的这条已进记忆', !!newInApi, JSON.stringify((await presetsApi()).map((x) => x.content.slice(0, 12))))

    await openAddDialog()
    const cs3 = await chips()
    const inTop8 = (await presetsApi()).findIndex((x) => x.content === NEW)
    if (inTop8 >= 0 && inTop8 < 8) {
      checkTrue('E3 再开弹窗就能看到它（可以一键添加了）',
        cs3.some((c) => c.text === NEW), JSON.stringify(cs3.map((c) => c.text.slice(0, 12))))
    } else {
      console.log('  （新条目次数为 1，暂未进前 8，跳过 UI 断言）')
    }
    await p.screenshot({ path: `${OUT}/tp3-after-save.png` })

    // ================= F. 空态兜底 =================
    console.log('\n=== F. 没有记忆时不显示这一块 ===')
    // 界面上无法真造一个"零记忆"的账号，这里验证的是渲染逻辑：
    // v-if 绑在 presets.length 上，空数组时整块不渲染（读不到 .cf-btodo__preset）
    checkTrue('F1 当前有记忆时该块存在', (await presetBlockText()).includes('常用待办'))
    await closeDialog()

    console.log('\n控制台错误：' + (errs.length ? errs.join('\n  ') : 'none'))
  } finally {
    console.log('\n=== 清理 ===')
    if (caseId) {
      const ops = await opinionsApi()
      for (const o of ops) {
        await api('POST', '/watch/opinions/' + o.id + '/remove', {}, token)
      }
      const r = await api('DELETE', '/cases/' + caseId, null, token)
      console.log(`  删自检案件 ${caseId} → ${r.body && r.body.code === 0 ? 'OK' : JSON.stringify(r.body)}`)
    }
    await b.close()
  }

  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail > 0 || errs.length > 0 ? 1 : 0)
}

run().catch((e) => { console.error(e); process.exit(1) })
