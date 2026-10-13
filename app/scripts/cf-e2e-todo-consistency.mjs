// 待办录入「三处一致」端到端自检（2026-10-13）
// 用法：node scripts/cf-e2e-todo-consistency.mjs   （前端 5173、后端 8080 需已启动）
//
// 覆盖用户本轮需求：指派弹窗与建案弹窗里的待办录入，必须与案件详情「添加待办」完全一致。
// 判据分两层：
//   交互层 —— 同一套表格（列头、Enter 续行、重要程度沿用、上限 10、图例、条数统计）；
//   落库层 —— 提交后产出的 opinion + 派生 todo 字段与详情入口一字不差（看 API，不看截图）。
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
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 200)) })
  await p.setViewport({ width: 1440, height: 950 })

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

  const login = async (username, password) => {
    const r = await api('POST', '/auth/login', { username, password })
    return r.body?.data
  }
  const openAs = async (user, token, hash, caseType) => {
    await p.evaluate((u, t, ct) => {
      localStorage.setItem('cf_token', t)
      localStorage.setItem('cf_user', JSON.stringify(u))
      if (ct) localStorage.setItem('cf_case_type', ct)
      else localStorage.removeItem('cf_case_type')
    }, user, token, caseType || '')
    await p.goto(`${BASE}/#${hash}`, { waitUntil: 'domcontentloaded' })
    await p.reload({ waitUntil: 'networkidle2' })
    await sleep(1000)
  }
  const toasts = () => p.evaluate(() => [...document.querySelectorAll('.el-message')].map((x) => x.innerText.trim()))

  // ---- 通用：读某个 scope 下表格的每行状态 ----
  const rowState = (scope) => p.evaluate((sc) => {
    const box = document.querySelector('.' + sc)
    if (!box) return null
    return [...box.querySelectorAll('[class$="__row"]')].map((r) => ({
      content: r.querySelector('[class$="__content"] input')?.value ?? null,
      imp: ([...r.querySelectorAll('[class$="__abc"] .el-radio-button')]
        .find((x) => x.classList.contains('is-active')) || {}).innerText?.trim() ?? null
    }))
  }, scope)

  const fillContent = (scope, i, text) => p.evaluate((sc, idx, t) => {
    const box = document.querySelector('.' + sc)
    const row = box.querySelectorAll('[class$="__row"]')[idx]
    const inp = row.querySelector('[class$="__content"] input')
    inp.value = t
    inp.dispatchEvent(new Event('input', { bubbles: true }))
  }, scope, i, text)

  const pickImp = async (scope, i, v) => {
    const ok = await p.evaluate((sc, idx, val) => {
      const box = document.querySelector('.' + sc)
      const row = box.querySelectorAll('[class$="__row"]')[idx]
      const btn = [...row.querySelectorAll('[class$="__abc"] .el-radio-button')]
        .find((x) => x.innerText.trim() === val)
      if (!btn) return false
      const input = btn.querySelector('input')
      ;(input || btn).click()
      return true
    }, scope, i, v)
    await sleep(250)
    return ok
  }

  const addOne = async (scope) => {
    const clicked = await p.evaluate((sc) => {
      const box = document.querySelector('.' + sc)
      const foot = box.closest('.el-dialog, .cf-dialog, div').parentElement
      const btn = [...(foot ? foot.querySelectorAll('button') : [])]
        .find((x) => x.innerText.includes('再添一条'))
      if (!btn || btn.disabled || btn.classList.contains('is-disabled')) return false
      btn.click()
      return true
    }, scope)
    await sleep(350)
    return clicked
  }

  /** 在建案弹窗里：打开 → 填案件名 → 选案件类型（大类，必填）
   *  返回 { nameFilled, typePicked }
   *
   *  顺序有讲究：先填案件名再选类型。反过来的话，下拉面板会盖在表单上，
   *  后面按 placeholder 找输入框可能命中被遮挡/还没渲染的那个，
   *  表现为「提示请填写案件名称」——是脚本时序问题，不是产品问题。 */
  const fillCreateForm = async (name) => {
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('button')]
        .find((x) => x.innerText.trim() === '新建案件' || x.innerText.trim() === '＋ 新建')
      btn && btn.click()
    })
    await sleep(1300)
    const nameFilled = await p.evaluate((nm) => {
      const inp = [...document.querySelectorAll('.el-dialog input')]
        .find((x) => (x.placeholder || '').includes('案件名'))
      if (!inp) return false
      inp.value = nm
      inp.dispatchEvent(new Event('input', { bubbles: true }))
      inp.dispatchEvent(new Event('change', { bubbles: true }))
      return true
    }, name)
    await sleep(300)
    // 案件分类是 el-cascader（两级：大类 → 小类），且面板 teleport 到 body。
    // 三种驱动方式都试过（element.click / 真实鼠标 / offsetParent 过滤），最后用**键盘**：
    //   真实鼠标要按 getBoundingClientRect 点，但 cascader 有 hover→展开的时序，
    //   连点两下会跳过第二级；offsetParent 在 fixed 弹层里恒为 null。
    // 键盘路线最稳：聚焦输入框 → ArrowDown 逐项移动 → Enter 确认，
    // 一直按到输入框里出现「刑事 / 子类」这样带斜杠的完整路径（叶子节点）为止。
    const cascInput = '.el-dialog .el-cascader .el-input__inner'
    await p.click('.el-dialog .el-cascader .el-input__wrapper')
    await sleep(500)
    let cascVal = ''
    // 一级一级的选项在**同一面板**里上下移动，每按一次 ArrowDown 换一项、
    // Enter 落定。有子类的项落定后输入框会显示完整路径「刑事 / 盗窃」（带斜杠）；
    // 「未立案」这类叶子大类没有斜杠，选中它会建出 caseType=PRELIMINARY 的案件，
    // 与后续断言（刑事列表）不符，所以要继续往下试。
    for (let i = 0; i < 10; i++) {
      await p.keyboard.press('ArrowDown')
      await sleep(200)
      await p.keyboard.press('Enter')
      await sleep(400)
      cascVal = await p.evaluate((s) => document.querySelector(s)?.value || '', cascInput)
      if (cascVal.includes('/')) break
    }
    await p.keyboard.press('Escape')
    await sleep(300)
    const typePicked = cascVal.includes('/')
    // 选完再确认一次案件名还在（面板可能盖住输入框导致误填/丢值）
    const nameStill = await p.evaluate((nm) => {
      const inp = [...document.querySelectorAll('.el-dialog input')]
        .find((x) => (x.placeholder || '').includes('案件名'))
      if (!inp) return false
      if (!inp.value) {
        inp.value = nm
        inp.dispatchEvent(new Event('input', { bubbles: true }))
      }
      return inp.value === nm
    }, name)
    return { nameFilled: nameStill, typePicked, cascVal }
  }

  const submitCreate = async () => {
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('.el-dialog__footer button')]
        .find((x) => x.innerText.trim() === '创建')
      btn && btn.click()
    })
    await sleep(2200)
  }

  // 必须先落到真实 origin 再调 fetch：页面内 fetch 用的是相对路径 /api，
  // about:blank 下会解析失败（曾因此拿到 401/空响应）。
  await p.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  await sleep(600)
  const bossLogin = await login('boss', 'admin123')
  if (!bossLogin || !bossLogin.token) {
    console.log('  ! 拿不到 boss 登录态，终止')
    await b.close()
    process.exit(1)
  }
  const token = bossLogin.token
  const { token: _t, ...bossUser } = bossLogin
  const MARK = 'TC' + Date.now()
  let createdIds = []

  try {
    // 找一个在职员工用于指派（search 返回的是**树**，顶层也是员工，要往下走）
    const emps = await api('GET', '/employees/search?limit=1000', null, token)
    const flat = []
    const walk = (ns) => { for (const n of ns || []) { if (n && n.id != null) flat.push(n); walk(n && n.children) } }
    walk(emps.body?.data || [])
    const emp = flat.find((e) => e.status === 1) || flat[0]
    checkTrue('准备：找到可指派员工', !!emp, JSON.stringify(emp && emp.name))
    if (!emp) throw new Error('无可指派员工，后续无法进行')

    // ================= A. 建案弹窗 =================
    console.log('\n=== A. 建案弹窗里的待办区块 ===')
    const cname1 = MARK + '-建案带待办'
    await openAs(bossUser, token, '/cases', 'CRIMINAL')
    const filled1 = await fillCreateForm(cname1)
    // 判据只要求「案件名填进去了 + 案件分类选上了任意一项」。
    // 不强求必须是刑事：cascader 的键盘落点会停在哪个大类取决于面板里
    // 小类的数量（有的分类没有二级），钉死某一类会让这条断言变成对
    // 分类库内容的断言——分类库一改就假失败，与本脚本要验的待办功能无关。
    checkTrue('A0 建案弹窗打开并填了案件名与类型',
      filled1.nameFilled && !!filled1.cascVal, JSON.stringify(filled1))

    checkTrue('A1 建案弹窗里有待办区块，且默认不占地方',
      await p.evaluate(() => {
        const blk = document.querySelector('.cf-form-todo')
        return !!blk && !!blk.innerText.includes('待办事项')
      }))

    const openedTodo = await p.evaluate(() => {
      const btn = [...document.querySelectorAll('.cf-form-todo button')]
        .find((x) => x.innerText.includes('添加待办'))
      if (!btn) return false
      btn.click()
      return true
    })
    await sleep(500)
    checkTrue('A2 点「+ 添加待办」展开表格', openedTodo)
    checkTrue('A3 表格列头与详情入口一致',
      await p.evaluate(() => {
        const heads = [...document.querySelectorAll('.cf-form-todo [class$="__grid--head"] span')]
          .map((x) => x.innerText.trim()).filter(Boolean)
        return JSON.stringify(heads) === JSON.stringify(['序号', '待办内容（必填）', '截止时间（选填）', '重要程度'])
      }),
      await p.evaluate(() => [...document.querySelectorAll('.cf-form-todo [class$="__grid--head"] span')].map((x) => x.innerText.trim()).filter(Boolean).join('/')))
    checkTrue('A4 有 Enter 续行提示', await p.evaluate(() =>
      (document.querySelector('.cf-form-todo [class$="__hint"]')?.innerText || '').includes('Enter')))
    checkTrue('A5 有 A/B/C 图例', await p.evaluate(() =>
      (document.querySelector('.cf-form-todo [class$="__legend"]')?.innerText || '').includes('A 最重要')))

    check('A6 默认给一行', (await rowState('cf-form-todo'))?.length, 1)
    await fillContent('cf-form-todo', 0, MARK + ' 走访三名证人')
    await pickImp('cf-form-todo', 0, 'B')
    // 末行按 Enter 应续出新行，并沿用上一行的重要程度
    await p.evaluate(() => {
      const inp = document.querySelector('.cf-form-todo [class$="__content"] input')
      inp.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    })
    await sleep(400)
    let rows = await rowState('cf-form-todo')
    check('A7 末行按 Enter 续出新行', rows.length, 2)
    check('A8 新行沿用上一行重要程度', rows[1]?.imp, 'B')
    check('A9 新行是空行，不复制上一行内容', rows[1]?.content, '')

    await fillContent('cf-form-todo', 1, MARK + ' 调取银行流水')
    await pickImp('cf-form-todo', 1, 'A')
    await addOne('cf-form-todo')
    await fillContent('cf-form-todo', 2, MARK + ' 出具情况说明')
    check('A10 共 3 行', (await rowState('cf-form-todo'))?.length, 3)
    checkTrue('A11 底部统计跟着走',
      await p.evaluate(() => (document.querySelector('.cf-form-todo__count')?.innerText || '').replace(/\s+/g, '').includes('已填3条')),
      await p.evaluate(() => document.querySelector('.cf-form-todo__count')?.innerText || ''))
    await p.screenshot({ path: `${OUT}/tc1-create-todo.png` })

    await submitCreate()
    console.log('  提示 =', JSON.stringify(await toasts()))
    // 分页返回的是 { list, total, page, size }——不是 records。
    const findByName = async (nm) => {
      const r = await api('GET', `/cases?page=1&size=200&keyword=${encodeURIComponent(nm)}`, null, token)
      return (r.body?.data?.list || []).find((x) => x.name === nm) || null
    }
    const hit1 = await findByName(cname1)
    checkTrue('A12 案件建成', !!hit1, cname1)
    const cid1 = hit1?.id
    createdIds.push(cid1)

    const ops1 = await api('GET', `/watch/cases/${cid1}/opinions`, null, token)
    const tds1 = await api('GET', `/todos/case/${cid1}`, null, token)
    check('A13 建案待办 = 3 条意见', (ops1.body?.data || []).length, 3)
    check('A14 建案待办 = 3 条派生待办', (tds1.body?.data || []).length, 3)
    checkTrue('A15 每条待办都带 opinionId（与详情入口同源）',
      (tds1.body?.data || []).every((t) => !!t.opinionId),
      (tds1.body?.data || []).map((t) => t.opinionId))
    const byc = Object.fromEntries((tds1.body?.data || []).map((t) => [t.content, t]))
    check('A16 A 级 → 紧急 + 重点',
      [byc[MARK + ' 出具情况说明']?.urgency, byc[MARK + ' 出具情况说明']?.importance],
      ['URGENT', 'KEY'])
    check('A17 B 级 → 较急 + 次重点',
      [byc[MARK + ' 走访三名证人']?.urgency, byc[MARK + ' 走访三名证人']?.importance],
      ['HIGH', 'MEDIUM'])
    checkTrue('A18 待办被计入当前阶段进度',
      ((await api('GET', `/watch/cases/${cid1}/flow`, null, token)).body?.data?.progress?.total) === 3)

    // ================= B. 建案不带待办 =================
    console.log('\n=== B. 建案不带待办（自行决定）===')
    const cname2 = MARK + '-建案无待办'
    await openAs(bossUser, token, '/cases', 'CRIMINAL')
    await fillCreateForm(cname2)
    checkTrue('B0 待办区块默认收起，不添一行也不影响建案',
      await p.evaluate(() => {
        const blk = document.querySelector('.cf-form-todo')
        return !!blk && !blk.querySelector('[class$="__row"]')
      }))
    await submitCreate()
    console.log('  提示 =', JSON.stringify(await toasts()))
    const hit2 = await findByName(cname2)
    checkTrue('B1 不点待办也能建案', !!hit2, cname2)
    createdIds.push(hit2?.id)
    check('B2 零待办（不生成意见，也不生成待办）',
      [
        ((await api('GET', `/watch/cases/${hit2?.id}/opinions`, null, token)).body?.data || []).length,
        ((await api('GET', `/todos/case/${hit2?.id}`, null, token)).body?.data || []).length
      ], [0, 0])

    // ================= C. 指派弹窗 =================
    console.log('\n=== C. 指派弹窗里的待办 ===')
    // 这里用 API 建一个**刑事**案件，而不是复用 B 组那个：
    // B 组案件的分类来自 cascader 的键盘选择，落点不稳（会选到「未立案」），
    // 而列表默认按当前案件类型过滤，类型对不上就查不到行（表现为 no-row）。
    // C 组要验的是"指派弹窗的待办"，不依赖建案弹窗的分类联动。
    const cname3 = MARK + '-指派用'
    const madeC = await api('POST', '/cases',
      { name: cname3, caseType: 'CRIMINAL', category: null }, token)
    const cid3 = madeC.body?.data?.id
    createdIds.push(cid3)
    checkTrue('C 准备：建了一个刑事案件', !!cid3, JSON.stringify(madeC.body).slice(0, 120))
    await openAs(bossUser, token, '/cases', 'CRIMINAL')
    await p.evaluate((nm) => {
      const inp = [...document.querySelectorAll('input')].find((x) => (x.placeholder || '').includes('案件名'))
      if (inp) { inp.value = nm; inp.dispatchEvent(new Event('input', { bubbles: true })) }
    }, cname3)
    await sleep(300)
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('button')].find((x) => x.innerText.trim() === '查询')
      btn && btn.click()
    })
    await sleep(1400)
    const openedAssign = await p.evaluate((nm) => {
      const row = [...document.querySelectorAll('.el-table__row')].find((r) => r.innerText.includes(nm))
      if (!row) return 'no-row'
      const btn = [...row.querySelectorAll('button')].find((x) => x.innerText.trim() === '指派')
      if (btn) { btn.click(); return 'btn' }
      return 'no-btn:' + row.innerText.replace(/\s+/g, ' ').slice(0, 60)
    }, cname3)
    console.log('  打开指派的方式 =', openedAssign)
    await sleep(1300)
    checkTrue('C0 指派弹窗打开', await p.evaluate(() =>
      !!document.querySelector('.cf-assign-dlg') && !!document.querySelector('.cf-assign-todo')))
    checkTrue('C1 有待办表格（与详情同一套）',
      await p.evaluate(() => {
        const heads = [...document.querySelectorAll('.cf-assign-todo [class$="__grid--head"] span')]
          .map((x) => x.innerText.trim()).filter(Boolean)
        return JSON.stringify(heads) === JSON.stringify(['序号', '待办内容（必填）', '截止时间（选填）', '重要程度'])
      }))
    checkTrue('C2 有 Enter 提示与 A/B/C 图例', await p.evaluate(() =>
      (document.querySelector('.cf-assign-todo [class$="__hint"]')?.innerText || '').includes('Enter')
      && (document.querySelector('.cf-assign-todo [class$="__legend"]')?.innerText || '').includes('A 最重要')))
    check('C3 默认给一行', (await rowState('cf-assign-todo'))?.length, 1)

    await fillContent('cf-assign-todo', 0, MARK + ' 24 小时内提取通话记录')
    await pickImp('cf-assign-todo', 0, 'A')
    await p.evaluate(() => {
      const inp = document.querySelector('.cf-assign-todo [class$="__content"] input')
      inp.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    })
    await sleep(400)
    rows = await rowState('cf-assign-todo')
    check('C4 Enter 续行', rows.length, 2)
    check('C5 新行沿用重要程度', rows[1]?.imp, 'A')
    await fillContent('cf-assign-todo', 1, MARK + ' 上门送达告知书')
    checkTrue('C6 统计跟着走',
      await p.evaluate(() => (document.querySelector('.cf-assign-todo__count')?.innerText || '').replace(/\s+/g, '').includes('已填2条')))
    await p.screenshot({ path: `${OUT}/tc2-assign-todo.png` })

    // 选承办人：EmployeePicker 是「关键词检索 → 结果表 → 点『主办』」三步，
    // 没有一行内勾选框，直接点行是选不中的（表现为「请至少选择一名承办人」）。
    await p.evaluate((nm) => {
      const inp = [...document.querySelectorAll('.cf-assign-dlg input')]
        .find((x) => (x.placeholder || '').includes('姓名'))
      if (inp) { inp.value = nm; inp.dispatchEvent(new Event('input', { bubbles: true })) }
    }, emp.name)
    await sleep(400)
    await p.evaluate(() => {
      const inp = [...document.querySelectorAll('.cf-assign-dlg input')]
        .find((x) => (x.placeholder || '').includes('姓名'))
      if (inp) inp.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    })
    await sleep(1400)
    const pickedOwner = await p.evaluate(() => {
      const row = document.querySelector('.cf-assign-dlg .el-table__row')
      if (!row) return false
      const btn = [...row.querySelectorAll('button')].find((x) => x.innerText.trim() === '主办')
      if (!btn) return false
      btn.click()
      return true
    })
    checkTrue('C7b 选中主办人', pickedOwner)
    await sleep(700)
    const submitted = await p.evaluate(() => {
      const btn = [...document.querySelectorAll('.cf-assign-dlg .el-dialog__footer button, .el-dialog__footer button')]
        .find((x) => x.innerText.includes('确认指派'))
      if (!btn) return false
      btn.click()
      return true
    })
    checkTrue('C7 点确认指派', submitted)
    await sleep(2000)
    console.log('  提示 =', JSON.stringify(await toasts()))
    const ops3 = await api('GET', `/watch/cases/${cid3}/opinions`, null, token)
    const tds3 = await api('GET', `/todos/case/${cid3}`, null, token)
    check('C8 指派后生成 2 条意见', (ops3.body?.data || []).length, 2)
    check('C9 指派后生成 2 条派生待办', (tds3.body?.data || []).length, 2)
    checkTrue('C10 每条待办都带 opinionId（与详情入口同源）',
      (tds3.body?.data || []).every((t) => !!t.opinionId),
      (tds3.body?.data || []).map((t) => t.opinionId))
    const b1 = Object.fromEntries((tds3.body?.data || []).map((t) => [t.content, t]))
    check('C11 A 级映射与详情一致',
      [b1[MARK + ' 24 小时内提取通话记录']?.urgency, b1[MARK + ' 24 小时内提取通话记录']?.importance],
      ['URGENT', 'KEY'])

    // ================= D. 三处字段完全同形 =================
    console.log('\n=== D. 三处产出的待办字段完全一致 ===')
    const shapeOf = (list) => (list || [])
      .map((t) => [!!t.opinionId, !!t.deadline, t.urgency, t.importance, !!t.opinionCreatorName, !!t.opinionCreatedAt])
      .sort()
    const viaDetail = await api('POST', `/watch/cases/${cid3}/opinions/batch`,
      { items: [{ content: MARK + ' 详情入口写的', deadline: '', importance: 'A' }] }, token)
    check('D0 详情入口写成功', viaDetail.body?.code, 0)
    const tdsAfter = await api('GET', `/todos/case/${cid3}`, null, token)
    const all = tdsAfter.body?.data || []
    const detailOne = all.filter((t) => t.content === MARK + ' 详情入口写的')
    const assignOnes = all.filter((t) => t.content.startsWith(MARK) && t.content !== MARK + ' 详情入口写的')
    check('D1 三个入口产出的字段形状一模一样',
      shapeOf(detailOne)[0], shapeOf(assignOnes)[0])

    // ================= E. 常用待办在两个新入口里可用 =================
    console.log('\n=== E. 常用待办（指派弹窗）===')
    await openAs(bossUser, token, '/cases', 'CRIMINAL')
    await p.evaluate((nm) => {
      const inp = [...document.querySelectorAll('input')].find((x) => (x.placeholder || '').includes('案件名'))
      if (inp) { inp.value = nm; inp.dispatchEvent(new Event('input', { bubbles: true })) }
    }, cname3)
    await sleep(300)
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('button')].find((x) => x.innerText.trim() === '查询')
      btn && btn.click()
    })
    await sleep(1400)
    await p.evaluate((nm) => {
      const row = [...document.querySelectorAll('.el-table__row')].find((r) => r.innerText.includes(nm))
      const btn = row && [...row.querySelectorAll('button')].find((x) => x.innerText.trim() === '指派')
      btn && btn.click()
    }, cname3)
    await sleep(1400)
    const chips = await p.evaluate(() => [...document.querySelectorAll('.cf-assign-todo [class$="__chip"]')]
      .map((c) => (c.innerText || '').trim()))
    console.log('  常用待办标签 =', JSON.stringify(chips.slice(0, 5)))
    checkTrue('E0 指派弹窗里也有常用待办', chips.length > 0, String(chips.length))
    const beforeClick = (await rowState('cf-assign-todo')) || []
    await p.evaluate(() => {
      const chip = document.querySelector('.cf-assign-todo [class$="__chip"]')
      chip && chip.click()
    })
    await sleep(700)
    const afterClick = (await rowState('cf-assign-todo')) || []
    checkTrue('E1 点常用待办是「填进表格」，不是直接落库',
      afterClick.length > beforeClick.length
      || afterClick.some((r, i) => (r.content || '') !== (beforeClick[i]?.content || '')),
      JSON.stringify(afterClick))
    const libAfter = (await api('GET', `/todos/case/${cid3}`, null, token)).body?.data || []
    check('E2 点标签本身不写库（主操作还没提交）', libAfter.length, 3)
    checkTrue('E3 弹窗还开着（可以接着写别的）',
      await p.evaluate(() => !!document.querySelector('.cf-assign-dlg')))

    // ================= F. 上限 10 =================
    console.log('\n=== F. 行数上限 ===')
    for (let i = 0; i < 12; i++) await addOne('cf-assign-todo')
    const maxRows = (await rowState('cf-assign-todo')) || []
    check('F1 最多 10 行', maxRows.length, 10)
    checkTrue('F2 到顶后「再添一条」置灰并说明上限',
      await p.evaluate(() => {
        const f = document.querySelector('.cf-assign-todo__foot')
        if (!f) return false
        const btn = [...f.querySelectorAll('button')].find((x) => x.innerText.includes('再添一条'))
        return (btn.disabled || btn.classList.contains('is-disabled')) && f.innerText.includes('一次最多 10 条')
      }))
    await p.screenshot({ path: `${OUT}/tc3-assign-limit.png` })
  } catch (e) {
    fail++
    console.log('  ! 异常中断：' + (e && e.message))
    console.log(e && e.stack ? String(e.stack).slice(0, 600) : '')
  } finally {
    console.log('\n控制台错误：' + (errs.length ? errs.join('\n  ') : 'none'))
    for (const id of createdIds) {
      if (!id) continue
      const tds = await api('GET', `/todos/case/${id}`, null, token)
      for (const t of tds.body?.data || []) {
        if (t.opinionId) await api('POST', `/watch/opinions/${t.opinionId}/remove`, {}, token)
      }
      const r = await api('DELETE', `/cases/${id}`, null, token)
      console.log(`  清理案件 ${id} → ${r.body?.code === 0 ? 'OK' : JSON.stringify(r.body).slice(0, 80)}`)
    }
    await b.close()
    console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
    if (fail) process.exit(1)
  }
}

run()
