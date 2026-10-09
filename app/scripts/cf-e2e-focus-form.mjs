// 端到端：建案表单改造 + 一键重点关注（2026-10-09）
//
// 需求（用户原话）：
//   1) 调解书不要
//   2) 新增「是否采取强制措施」是/否；选是 → 下拉选拘传 / 取保候审 / 监视居住 / 拘留 / 逮捕
//   3) 截止时间重写：精确到天 + 自己填时间节点名称 + 自己选提前多久提醒，去掉 +1/+3/+7
//   4) 「立案登记表」改成「案件编号」
//   5) 案件管理 / 案件盯办 / 待办总览 / 到期提醒：列表里一键重点关注（不用打开详情）
//
// 覆盖点：
//   A 表单结构：无调解书 / 有案件编号 / 是否强制措施联动下拉 / 无+1天等快捷键 / 期限三件套
//   B 数据：按天存储（23:59:59）、节点名称、提前提醒天数、强制措施入库
//   C 一键重点关注：四个栏目的星标都在；点星即时改；「只看重点」能筛；再点取消
//   D 逮捕类案件归入盯办「刑拘在办」子模块（新增措施不能三张卡片都数不到）
//   E 星标不挡撤回：标完关注后，最近一条可撤回操作仍是被它之前的那条
//
// 测试数据自管：脚本自己建一个案件，跑完删掉（finally 里清理）。
//
// 用法：node scripts/cf-e2e-focus-form.mjs   （前端 5173、后端 8080 需已启动）
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

/**
 * 当前**可见**下拉的选项文本。
 * Element Plus 把所有下拉都留在 DOM 里（隐藏而已），直接查
 * .el-select-dropdown__item 会把页面上十几个下拉的选项全捞回来。
 */
const visibleOptions = (p) => p.evaluate(() =>
  [...document.querySelectorAll('.el-select-dropdown')]
    .filter((d) => d.offsetParent !== null)
    .flatMap((d) => [...d.querySelectorAll('.el-select-dropdown__item')].map((x) => x.innerText.trim())))

/**
 * 在弹窗里选下拉项。
 * 坑：Element Plus 的 toggleMenu 会先吃掉「focus 之后的那一次点击」
 * （内部 menuVisibleOnFocus，先看有没有焦点再决定展不展开），
 * 所以对一个没有焦点的 el-select 只 click 一次，下拉纹丝不动。
 * 这里点一次没开就再点一次，跟真人点两下的效果一致。
 */
const pickSelect = async (p, labelText, optText) => {
  for (let i = 0; i < 3; i++) {
    await p.evaluate((lt) => {
      const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes(lt))
      item?.querySelector('input')?.click()
    }, labelText)
    await sleep(400)
    const opts = await visibleOptions(p)
    if (opts.includes(optText)) break
  }
  const ok = await p.evaluate((ot) => {
    const opt = [...document.querySelectorAll('.el-select-dropdown')]
      .filter((d) => d.offsetParent !== null)
      .flatMap((d) => [...d.querySelectorAll('.el-select-dropdown__item')])
      .find((x) => x.innerText.trim() === ot)
    if (!opt) return false
    opt.click()
    return true
  }, optText)
  await sleep(300)
  return ok
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
  const login = await api('POST', '/auth/login', { username: 'boss', password: 'admin123' })
  const { token, ...ui } = login.body.data
  const cases = []

  const openAs = async (hash, caseType) => {
    await p.evaluate((u, t, ct) => {
      localStorage.setItem('cf_token', t)
      localStorage.setItem('cf_user', JSON.stringify(u))
      if (ct) localStorage.setItem('cf_case_type', ct)
      else localStorage.removeItem('cf_case_type')
    }, ui, token, caseType || '')
    await p.goto(`${BASE}/#${hash}`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(800)
  }

  try {
    // ================= A. 建案表单结构 =================
    console.log('=== A. 建案表单结构 ===')
    await openAs('/cases', 'CRIMINAL')
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('button')].find((x) => x.innerText.includes('新建案件'))
      btn && btn.click()
    })
    await sleep(600)

    /** 弹窗里所有表单项的标签文本 */
    const labels = () => p.evaluate(() => [...document.querySelectorAll('.cf-dialog .el-form-item__label')]
      .map((x) => x.innerText.replace(/\s+/g, ' ').trim()))
    const dialogButtons = () => p.evaluate(() => [...document.querySelectorAll('.cf-dialog button')]
      .map((x) => x.innerText.trim()))

    const ls = await labels()
    console.log('  表单项 =', JSON.stringify(ls))
    checkTrue('① 已无「调解书」', !ls.some((t) => t.includes('调解书')), JSON.stringify(ls))
    checkTrue('② 有「案件编号」', ls.some((t) => t.includes('案件编号')))
    checkTrue('② 无「立案登记表」', !ls.some((t) => t.includes('立案登记表')))
    checkTrue('③ 有「强制措施」开关与「时间节点/期限日期/提前提醒」',
      ['强制措施', '时间节点', '期限日期', '提前提醒'].every((t) => ls.some((x) => x.includes(t))), JSON.stringify(ls))

    const btns = await dialogButtons()
    checkTrue('③ 已去掉 +1天/+3天/+7天/清空 快捷键',
      !btns.some((t) => /^\+\d+天$/.test(t)), JSON.stringify(btns))

    // 「否」默认：措施种类下拉不出现；点「是」后出现，且五个选项齐全
    checkTrue('② 默认「否」时不显示措施种类', !(await labels()).some((t) => t.includes('措施种类')))
    await p.evaluate(() => {
      const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes('强制措施'))
      const yes = [...item.querySelectorAll('.el-radio-button')].find((x) => x.innerText.trim() === '是')
      yes.click()
    })
    await sleep(400)
    checkTrue('② 选「是」后措施种类出现', (await labels()).some((t) => t.includes('措施种类')))
    // 打开措施下拉读选项
    await p.evaluate(() => {
      const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes('措施种类'))
      item.querySelector('input').click()
    })
    await sleep(500)
    const measureOptions = await visibleOptions(p)
    console.log('  措施选项 =', JSON.stringify(measureOptions))
    check('② 措施下拉为法定五种', measureOptions, ['拘传', '拘留', '取保候审', '监视居住', '逮捕'])
    await p.screenshot({ path: `${OUT}/ff1-form.png` })
    // 读完选项必须把下拉收起来：后面填表时再点一次 input 是「切换」，
    // 如果这里留着展开态，那一下会把它关掉，选项点不到 → 「请选择采取的强制措施」
    await p.evaluate(() => document.querySelector('.cf-dialog .el-dialog__title')?.click())
    await sleep(300)

    // ---- 填一份完整表单并保存 ----
    const name = 'e2e-表单改造-' + Date.now().toString().slice(-6)
    await p.evaluate(() => {
      const dialog = document.querySelector('.cf-dialog')
      const setVal = (labelText, value) => {
        const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
          .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes(labelText))
        if (!item) return false
        const input = item.querySelector('input')
        if (!input) return false
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
        setter.call(input, value)
        input.dispatchEvent(new Event('input', { bubbles: true }))
        return true
      }
      setVal('案件名称', '')
      return true
    })
    // 案件名称：用真实输入（避免 Vue 受控值被覆盖）
    await p.evaluate(() => {
      const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes('案件名称'))
      item.querySelector('input').focus()
    })
    await p.keyboard.type(name)
    await sleep(200)

    // 案件分类（大类必选）：点开级联选「刑事」
    await p.evaluate(() => {
      const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes('案件分类'))
      item.querySelector('input').click()
    })
    await sleep(600)
    // 级联是 checkStrictly（可选父节点）：**点标签只会展开下一级**，
    // 要选中必须点节点上的单选按钮（radio）。这里踩过一次：
    // 点了 label，看着面板还在，以为选上了，其实 caseType 仍是空 → 提交被"请选择案件类型"拦下。
    // 注意：页面上残留着其它（已隐藏的）级联面板，直接查 .el-cascader-node
    // 会命中隐藏面板里的同名节点 —— 点它不会有任何效果，还以为选上了。
    // 必须先按 offsetParent 过滤出当前可见的那一个面板。
    const picked = await p.evaluate(() => {
      const panel = [...document.querySelectorAll('.el-cascader-panel')]
        .filter((d) => d.offsetParent !== null)
        .pop()
      if (!panel) return false
      const node = [...panel.querySelectorAll('.el-cascader-node')]
        .find((x) => x.innerText.includes('刑事'))
      if (!node) return false
      const radio = node.querySelector('.el-radio__inner') || node.querySelector('.el-radio')
      if (radio) { radio.click(); return true }
      node.click()
      return true
    })
    checkTrue('（准备）级联选中大类「刑事」', picked)
    await sleep(500)
    const cascadeVal = await p.evaluate(() => {
      const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes('案件分类'))
      return item?.querySelector('input')?.value || ''
    })
    checkTrue('（准备）案件分类已选中（否则提交会被拦）', String(cascadeVal).includes('刑事'), JSON.stringify(cascadeVal))
    await sleep(400)
    // 收起级联面板要**点别处**（点弹窗标题）——按 Esc 会直接把整个弹窗关掉
    await p.evaluate(() => document.querySelector('.cf-dialog .el-dialog__title')?.click())
    await sleep(300)

    // 措施种类：选「逮捕」
    const pickedMeasure = await pickSelect(p, '措施种类', '逮捕')
    checkTrue('（准备）措施种类已选「逮捕」', pickedMeasure)

    // 期限三件套：节点名称 / 日期 / 提前提醒
    await p.evaluate(() => {
      const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes('时间节点'))
      item.querySelector('input').focus()
    })
    await p.keyboard.type('受案时间')
    await sleep(300)
    await p.evaluate(() => {
      const item = [...document.querySelectorAll('.cf-dialog .el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label') || {}).innerText?.includes('期限日期'))
      item.querySelector('input').focus()
    })
    await p.keyboard.type('2026-12-31')
    await p.keyboard.press('Enter')
    await sleep(400)
    // 提前提醒：选 7 天
    const pickedRemind = await pickSelect(p, '提前提醒', '提前 7 天')
    checkTrue('（准备）提前提醒已选 7 天', pickedRemind)
    await p.screenshot({ path: `${OUT}/ff2-form-filled.png` })

    // 保存
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('.cf-dialog button')].find((x) => x.innerText.trim() === '创建')
      btn && btn.click()
    })
    await sleep(1500)
    // 若被前端校验拦下，把提示语打出来——不然只会看到"案件没建成功"，猜不出卡在哪一条
    const warn = await p.evaluate(() => [...document.querySelectorAll('.el-message__content')]
      .map((x) => x.innerText.trim()))
    if (warn.length) console.log('  提示 =', JSON.stringify(warn))

    // ================= B. 落库数据 =================
    console.log('\n=== B. 落库数据（按天 / 节点 / 提醒 / 措施）===')
    const page1 = await api('GET', '/cases?keyword=' + encodeURIComponent(name) + '&size=5', null, token)
    const row = ((page1.body?.data?.list) || [])[0]
    checkTrue('案件已创建并能在列表查到', !!row, JSON.stringify(page1.body).slice(0, 200))
    if (row) {
      cases.push(row.id)
      console.log('  案件 =', JSON.stringify({
        id: row.id, deadlineText: row.deadlineText, deadlineLabel: row.deadlineLabel,
        remindDays: row.remindDays, caseMeasure: row.caseMeasure, focus: row.focus
      }))
      check('③ 期限只到日期（无时分）', row.deadlineText, '2026-12-31')
      check('③ 节点名称入库', row.deadlineLabel, '受案时间')
      check('③ 提前提醒天数入库', row.remindDays, 7)
      check('② 强制措施入库（逮捕）', row.caseMeasure, 'ARREST')
      check('⑤ 重点关注默认否', row.focus, 0)
      const d = await api('GET', '/cases/' + row.id, null, token)
      checkTrue('③ 落库时间归一到当天 23:59:59',
        String(d.body?.data?.deadline || '').endsWith('23:59:59'), d.body?.data?.deadline)
    }

    // ================= D. 逮捕归入盯办「刑拘在办」 =================
    console.log('\n=== D. 新增措施不漏案（逮捕 → 刑拘在办子模块）===')
    if (row) {
      // 盯办列表是 /watch/cases（不是 /watch/list，那个路径不存在会 404）
      const w = await api('GET', '/watch/cases?module=DETENTION&caseType=CRIMINAL&size=50', null, token)
      const list = (w.body?.data?.list) || (w.body?.data?.records) || []
      const hit = list.some((x) => x.id === row.id)
      checkTrue('逮捕案件出现在盯办「刑拘在办」', hit,
        '模块内案件 id = ' + JSON.stringify(list.map((x) => x.id)))
    }

    // ================= C. 一键重点关注 =================
    console.log('\n=== C. 一键重点关注（四个栏目）===')
    // 案件管理：点星
    await openAs('/cases', 'CRIMINAL')
    const firstRowHasStar = await p.evaluate(() => !!document.querySelector('.el-table__row .cf-star'))
    checkTrue('⑤ 案件管理列表有星标按钮', firstRowHasStar)
    // 找到目标案件所在行再点它的星（列表按创建时间倒序，第一列就是重点）
    const clicked = await p.evaluate((caseName) => {
      const tr = [...document.querySelectorAll('.el-table__row')]
        .find((x) => x.innerText.includes(caseName))
      if (!tr) return false
      const btn = tr.querySelector('.cf-star')
      if (!btn) return false
      btn.click()
      return true
    }, name)
    checkTrue('⑤ 列表里一键标注（未打开详情）', clicked)
    await sleep(1200)
    const rowClass = await p.evaluate((caseName) => {
      const tr = [...document.querySelectorAll('.el-table__row')]
        .find((x) => x.innerText.includes(caseName))
      return tr ? tr.className : ''
    }, name)
    checkTrue('⑤ 标注后该行有重点关注样式', rowClass.includes('cf-row--focus'), rowClass)
    const after = await api('GET', '/cases/' + (row?.id || 0), null, token)
    check('⑤ 接口侧 focus=1', after.body?.data?.focus, 1)
    await p.screenshot({ path: `${OUT}/ff3-focus-on.png` })

    // 只看重点
    await p.evaluate(() => {
      const cb = [...document.querySelectorAll('.el-checkbox')].find((x) => x.innerText.includes('只看重点'))
      cb && cb.querySelector('input').click()
    })
    await sleep(1200)
    const names = await p.evaluate(() => [...document.querySelectorAll('.el-table__row')]
      .map((x) => x.innerText.replace(/\s+/g, ' ').slice(0, 40)))
    checkTrue('⑤ 「只看重点」只剩已标注的案件',
      names.length > 0 && names.every((t) => t.includes(name.slice(0, 8))), JSON.stringify(names))
    await p.screenshot({ path: `${OUT}/ff4-focus-only.png` })

    // 其它三个栏目的星标是否都在
    await openAs('/reminders', 'CRIMINAL')
    checkTrue('⑤ 到期提醒列表有星标', await p.evaluate(() => !!document.querySelector('.el-table__row .cf-star')))
    await openAs('/watch', 'CRIMINAL')
    checkTrue('⑤ 案件盯办列表有星标', await p.evaluate(() => !!document.querySelector('.el-table__row .cf-star')))
    await openAs('/todos', 'CRIMINAL')
    const todoStar = await p.evaluate(() => !!document.querySelector('.el-table__row .cf-star'))
    console.log('  待办总览星标 =', todoStar, '（有待办行才会渲染）')

    // 取消标注
    await openAs('/cases', 'CRIMINAL')
    await p.evaluate((caseName) => {
      const tr = [...document.querySelectorAll('.el-table__row')]
        .find((x) => x.innerText.includes(caseName))
      tr && tr.querySelector('.cf-star')?.click()
    }, name)
    await sleep(1200)
    const off = await api('GET', '/cases/' + (row?.id || 0), null, token)
    check('⑤ 再点一次取消标注', off.body?.data?.focus, 0)

    // ================= E. 星标不挡撤回 =================
    console.log('\n=== E. 星标不挡撤回 ===')
    if (row) {
      await api('PUT', '/cases/' + row.id, { name: name + '-改', caseType: 'CRIMINAL' }, token)
      await api('POST', '/cases/' + row.id + '/focus', { focus: 1 }, token)
      const logs = await api('GET', '/logs/case/' + row.id, null, token)
      const recent = logs.body?.data || []
      const latest = recent[recent.length - 1]
      console.log('  该案件时间线 =', JSON.stringify(recent.map((l) => l.action)))
      checkTrue('星标写进了操作日志（CASE.FOCUS）', recent.some((l) => l.action === 'FOCUS'),
        JSON.stringify(recent.map((l) => l.action)))
      checkTrue('星标本身不可撤回（不占「最新一步」）', latest?.action === 'FOCUS' && latest?.undoable === false,
        JSON.stringify({ action: latest?.action, undoable: latest?.undoable }))
      const undo = await api('POST', '/logs/undo-latest', null, token)
      checkTrue('标完关注后，上一步「修改案件」仍可撤回',
        // 后端把 CASE.UPDATE 的日志名写作「更新案件」
        undo.body?.code === 0 && String(undo.body?.data?.content || '').includes('更新案件'),
        JSON.stringify(undo.body).slice(0, 300))
    }

    console.log('\n控制台错误：' + (errs.length ? errs.join('\n  ') : 'none'))
  } finally {
    console.log('\n=== 清理 ===')
    for (const id of cases) {
      const r = await api('DELETE', '/cases/' + id, null, token)
      console.log(`  删案件 ${id} → ${r.body && r.body.code === 0 ? 'OK' : JSON.stringify(r.body)}`)
    }
    await b.close()
  }

  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail > 0 || errs.length > 0 ? 1 : 0)
}

run().catch((e) => { console.error(e); process.exit(1) })
