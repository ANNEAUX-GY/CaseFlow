// 端到端：添加待办改成「一次填多条」+ 批量录入布局（2026-10-11）
//
// 需求（用户原话）：
//   「添加待办板块，改成一下子可以添加多条待办，你看看如何优化布局」
//
// 覆盖点：
//   A 弹窗结构：表格式批量录入（序号 / 待办内容 / 截止时间 / 重要程度 / 删除）、
//     一行一件事、底部 A/B/C 图例、条数统计、默认一行
//   B 多条录入：Enter 在末尾续行、填完再按 Enter 继续、「再添一条」追加、每行独立设重要程度与截止时间
//   C 一次提交：3 条一起落库 → 3 条意见 + 3 条派生待办，顺序一致、序号连续无空洞
//   D 面板同步：弹窗自动关闭、卡片按提交顺序出现、进度显示 0/3
//   E 空内容拦截：一条都不填点保存 → 拦下且弹窗不关（不让用户白填）
//   F 删行：多行时删掉该行；只剩一行时只清空这一行，不清空整个列表
//   G 上限：最多 10 行，到顶后「再添一条」置灰
//   H 入口收敛：普通民警（e2e_staff）在案件详情里看不到「＋ 添加」，
//     接口层也被后端 403 拦住（真拦截在后端，前端只是不给他点）
//
// 测试数据自管：脚本自己建一个案件，跑完删掉（finally 里清理）。
//
// 用法：node scripts/cf-e2e-todo-batch.mjs   （前端 5173、后端 8080 需已启动）
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

  // ---------- 页面内小工具 ----------
  const box = (sel) => p.evaluate((s) => {
    const el = document.querySelector(s)
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { w: Math.round(r.width), h: Math.round(r.height) }
  }, sel)

  const toasts = () => p.evaluate(() => [...document.querySelectorAll('.el-message')].map((x) => x.innerText.trim()))

  /** 每行的内容 / 截止时间 / 当前重要程度 —— 断言弹窗里的真实状态 */
  const rowState = () => p.evaluate((s) => [...document.querySelectorAll(s)].map((r) => ({
    content: r.querySelector('.cf-btodo__content input')?.value ?? null,
    date: r.querySelector('.cf-btodo__date input')?.value ?? null,
    imp: ([...r.querySelectorAll('.cf-btodo__abc .el-radio-button')]
      .find((x) => x.classList.contains('is-active')) || {}).innerText?.trim() ?? null
  })), ROW)

  const fillContent = (i, text) => p.evaluate((s, idx, t) => {
    const inp = document.querySelectorAll(s)[idx].querySelector('.cf-btodo__content input')
    inp.value = t
    inp.dispatchEvent(new Event('input', { bubbles: true }))
  }, ROW, i, text)

  const pickImp = async (i, v) => {
    const ok = await p.evaluate((s, idx, val) => {
      const row = document.querySelectorAll(s)[idx]
      const btn = [...row.querySelectorAll('.cf-btodo__abc .el-radio-button')]
        .find((x) => x.innerText.trim() === val)
      if (!btn) return false
      const input = btn.querySelector('input')
      ;(input || btn).click()
      return true
    }, ROW, i, v)
    await sleep(250)
    return ok
  }

  const clickRowDel = async (i) => {
    await p.evaluate((s, idx) => {
      const row = document.querySelectorAll(s)[idx]
      const btn = row.querySelector('.cf-btodo__del')
      btn && btn.click()
    }, ROW, i)
    await sleep(300)
  }

  /** 面板右上角「＋ 添加」（空状态里那个「＋ 添加第一条」也算入口） */
  const openAddDialog = async () => {
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('.cf-todo button')].find((x) => x.innerText.includes('添加'))
      btn && btn.click()
    })
    await sleep(700)
  }

  const dlgFooterBtn = async (text) => {
    const ok = await p.evaluate((t) => {
      const dlg = document.querySelector('.cf-btodo-dlg')
      if (!dlg) return false
      // 页脚在 .el-dialog 内部（不是它的兄弟），所以从 dlg 往下查
      const btn = [...dlg.querySelectorAll('.el-dialog__footer button')]
        .find((x) => x.innerText.trim().startsWith(t))
      if (!btn) return false
      btn.click()
      return true
    }, text)
    await sleep(600)
    return ok
  }

  /** 弹窗是否真的开着。注意 .el-dialog 在 fixed 定位的遮罩里，offsetParent 恒为 null，只能用尺寸判断 */
  const dlgVisible = async () => {
    const bx = await box('.cf-btodo-dlg')
    return !!bx && bx.h > 0
  }

  const footerText = () => p.evaluate(() => {
    const dlg = document.querySelector('.cf-btodo-dlg')
    const b = dlg && [...dlg.querySelectorAll('.el-dialog__footer button')].pop()
    return b ? b.innerText.replace(/\s+/g, '') : ''
  })

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
  const name = '【自检】批量待办-' + Date.now()
  let caseId = null
  const madeOpinionIds = []

  try {
    const created = await api('POST', '/cases', { name, caseType: 'CRIMINAL' }, token)
    caseId = created.body?.data?.id
    checkTrue('准备：建了一个自检案件', !!caseId, JSON.stringify(created.body).slice(0, 200))
    if (!caseId) throw new Error('案件建不出来，后续无法进行')

    // ================= A. 弹窗结构 =================
    console.log('\n=== A. 批量录入弹窗的结构 ===')
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

    const opened = await p.evaluate((nm) => {
      const row = [...document.querySelectorAll('.el-table__row')].find((r) => r.innerText.includes(nm))
      if (!row) return 'no-row'
      const btn = [...row.querySelectorAll('button')].find((x) => x.innerText.trim() === '详情')
      if (btn) { btn.click(); return 'btn' }
      row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
      return 'dblclick'
    }, name)
    console.log('  打开案件详情的方式 =', opened)
    await sleep(1600)
    checkTrue('A0 案件详情抽屉打开，待办面板在位',
      !!(await box('.cf-todo')), JSON.stringify(await box('.cf-todo')))

    await openAddDialog()
    const dlgBox = await box('.cf-btodo-dlg')
    console.log('  弹窗尺寸 =', JSON.stringify(dlgBox))
    checkTrue('A1 批量弹窗出现', await dlgVisible(), JSON.stringify(dlgBox))
    checkTrue('A2 弹窗够宽（批量录入才放得下三列控件）', !!dlgBox && dlgBox.w >= 600, JSON.stringify(dlgBox))

    const heads = await p.evaluate(() =>
      [...document.querySelectorAll('.cf-btodo__grid--head span')].map((x) => x.innerText.trim()).filter(Boolean))
    console.log('  列头 =', JSON.stringify(heads))
    check('A3 列头把每一项都说清楚了（不必每行重复一遍标签）',
      heads, ['序号', '待办内容（必填）', '截止时间（选填）', '重要程度'])

    check('A4 默认给一行', (await rowState()).length, 1)
    checkTrue('A5 第一行自带「输入即续行」的提示（Enter）',
      (await p.evaluate(() => document.querySelector('.cf-btodo__hint')?.innerText || '')).includes('Enter'))
    checkTrue('A6 底部有 A/B/C 三档图例（只写字母看不出轻重）',
      (await p.evaluate(() => document.querySelector('.cf-btodo__legend')?.innerText || '')).includes('A 最重要'))
    checkTrue('A7 底部有「再添一条」与条数统计',
      (await p.evaluate(() => document.querySelector('.cf-btodo__foot')?.innerText || '')).includes('再添一条'),
      await p.evaluate(() => document.querySelector('.cf-btodo__foot')?.innerText || ''))
    checkTrue('A8 统计初始为「已填 0 条」',
      (await p.evaluate(() => document.querySelector('.cf-btodo__count')?.innerText.replace(/\s+/g, '') || '')).includes('已填0条'))
    await p.screenshot({ path: `${OUT}/tb1-dialog.png` })

    // ================= B. 多条录入 =================
    console.log('\n=== B. 一次填多条 ===')
    await fillContent(0, '72 小时内完成受害人走访')
    await pickImp(0, 'B')
    await sleep(250)

    // Enter 在末尾 = 直接续出下一行（不必去够底部的按钮）
    await p.evaluate((s) => {
      const inp = document.querySelectorAll(s)[0].querySelector('.cf-btodo__content input')
      inp.focus()
    }, ROW)
    await p.keyboard.press('Enter')
    await sleep(400)
    check('B1 末行按 Enter 直接续出新行', (await rowState()).length, 2)
    check('B2 新行沿用上一行的重要程度（一批常是同一档，省得每条点一遍）',
      (await rowState())[1].imp, 'B')
    check('B3 新行是空行，不会把上一行内容复制一份',
      (await rowState())[1].content, '')

    await fillContent(1, '核对嫌疑人前科材料并归档')
    const addBtnClicked = await p.evaluate(() => {
      const btn = [...document.querySelectorAll('.cf-btodo__foot button')].find((x) => x.innerText.includes('再添一条'))
      if (!btn) return false
      btn.click()
      return true
    })
    await sleep(400)
    checkTrue('B4 「再添一条」可用', addBtnClicked)
    check('B5 追加后共 3 行', (await rowState()).length, 3)

    await fillContent(2, '出具并送达案件办理情况说明')
    await pickImp(2, 'A')
    const dateInput = await p.$(`${ROW}:nth-child(3) .cf-btodo__date input`)
    if (dateInput) {
      await dateInput.click()
      await sleep(350)
      await dateInput.type('2026-11-30 18:00')
      await sleep(250)
      await p.keyboard.press('Enter')
      await sleep(500)
    }
    const rowsNow = await rowState()
    console.log('  三行状态 =', JSON.stringify(rowsNow))
    check('B6 每行独立设的重要程度', rowsNow.map((r) => r.imp), ['B', 'B', 'A'])
    checkTrue('B7 每行独立设的截止时间已写入', String(rowsNow[2].date || '').startsWith('2026-11-30'),
      JSON.stringify(rowsNow[2]))
    checkTrue('B8 统计跟着更新为 3 条',
      (await p.evaluate(() => document.querySelector('.cf-btodo__count')?.innerText.replace(/\s+/g, '') || '')).includes('已填3条'))
    checkTrue('B9 保存按钮带上条数（点之前就知道会存几条）', (await footerText()).includes('3条'), await footerText())
    await p.screenshot({ path: `${OUT}/tb2-three-rows.png` })

    // ================= C. 一次提交落库 =================
    console.log('\n=== C. 一次提交三条 ===')
    await dlgFooterBtn('保存')
    await sleep(1200)
    console.log('  提示 =', JSON.stringify(await toasts()))
    checkTrue('C1 一次保存把三条一起提交（找得到成功的提示）',
      (await toasts()).some((t) => t.includes('已添加 3 条待办')), JSON.stringify(await toasts()))
    check('C2 保存后弹窗自动关闭', await dlgVisible(), false)

    const ops = await api('GET', '/watch/cases/' + caseId + '/opinions', null, token)
    const list = ops.body?.data || []
    list.forEach((o) => madeOpinionIds.push(o.id))
    check('C3 三条一起落库（不多不少）', list.map((o) => o.content),
      ['72 小时内完成受害人走访', '核对嫌疑人前科材料并归档', '出具并送达案件办理情况说明'])
    check('C4 每条的重要程度各按各行保存', list.map((o) => o.importance), ['B', 'B', 'A'])
    checkTrue('C5 第三行的截止时间存进来了',
      String(list[2]?.deadline || '').startsWith('2026-11-30') && String(list[2]?.deadline || '').includes('18:00'),
      JSON.stringify(list.map((o) => o.deadline)))
    checkTrue('C6 其余两条不设截止时间就是空', !list[0]?.deadline && !list[1]?.deadline,
      JSON.stringify(list.map((o) => o.deadline)))
    check('C7 序号连续无空洞（批量插入也接得上）', list.map((o) => o.sortOrder), [1, 2, 3])

    const todos = await api('GET', '/todos/case/' + caseId, null, token)
    const tl = todos.body?.data || []
    check('C8 三条待办一并派生出来（领导提意见 = 民警待办）',
      tl.map((t) => t.content), ['72 小时内完成受害人走访', '核对嫌疑人前科材料并归档', '出具并送达案件办理情况说明'])
    check('C9 待办侧的重要程度也跟着走', tl.map((t) => t.opinionImportance), ['B', 'B', 'A'])

    // ================= D. 面板同步 =================
    console.log('\n=== D. 待办面板同步 ===')
    const cards = await p.evaluate(() => [...document.querySelectorAll('.cf-todo__card')]
      .map((c) => c.querySelector('.cf-todo__name')?.innerText.trim()))
    check('D1 卡片按提交顺序显示', cards,
      ['72 小时内完成受害人走访', '核对嫌疑人前科材料并归档', '出具并送达案件办理情况说明'])
    check('D2 进度为 0/3',
      await p.evaluate(() => document.querySelector('.cf-todo__progress-text')?.innerText.trim()), '0/3')

    // ================= E. 空内容拦截 =================
    console.log('\n=== E. 一条都不填点保存 ===')
    await openAddDialog()
    await dlgFooterBtn('保存')
    await sleep(500)
    console.log('  提示 =', JSON.stringify(await toasts()))
    checkTrue('E1 被拦下并说清楚原因',
      (await toasts()).some((t) => t.includes('请至少填写一条待办内容')), JSON.stringify(await toasts()))
    checkTrue('E2 弹窗不关（不让用户白填一遍）', await dlgVisible())
    const stillThree = await api('GET', '/watch/cases/' + caseId + '/opinions', null, token)
    check('E3 库里没有凭空多出记录', (stillThree.body?.data || []).length, 3)

    // ================= F. 删行 =================
    console.log('\n=== F. 删掉某一行 ===')
    await fillContent(0, '这一行是要被删掉的')
    await p.evaluate(() => {
      const btn = [...document.querySelectorAll('.cf-btodo__foot button')].find((x) => x.innerText.includes('再添一条'))
      btn && btn.click()
    })
    await sleep(400)
    check('F1 先加到 2 行', (await rowState()).length, 2)
    await clickRowDel(0)
    const afterDel = await rowState()
    check('F2 删掉第 1 行后只剩 1 行', afterDel.length, 1)
    check('F3 删掉的是那一行（剩的是空行，不是把内容留在原地）', afterDel[0].content, '')

    await fillContent(0, '不想要的内容')
    await clickRowDel(0)
    const afterClear = await rowState()
    check('F4 只剩一行时点删除 = 清空这一行（不清空整个列表）', afterClear.length, 1)
    check('F5 该行内容确实被清掉了', afterClear[0].content, '')

    // ================= G. 上限 =================
    console.log('\n=== G. 行数上限 ===')
    for (let i = 0; i < 12; i++) {
      await p.evaluate(() => {
        const btn = [...document.querySelectorAll('.cf-btodo__foot button')].find((x) => x.innerText.includes('再添一条'))
        if (btn && !btn.disabled && !btn.classList.contains('is-disabled')) btn.click()
      })
      await sleep(120)
    }
    const maxRows = await rowState()
    check('G1 最多 10 行（再多就不是这个弹窗该干的事了）', maxRows.length, 10)
    checkTrue('G2 到顶后「再添一条」置灰，并说明上限',
      await p.evaluate(() => {
        const foot = document.querySelector('.cf-btodo__foot')
        const btn = [...foot.querySelectorAll('button')].find((x) => x.innerText.includes('再添一条'))
        return (btn.disabled || btn.classList.contains('is-disabled')) && foot.innerText.includes('一次最多 10 条')
      }))
    await closeDialog()
    check('G3 取消后弹窗关闭', await dlgVisible(), false)
    const afterCancel = await api('GET', '/watch/cases/' + caseId + '/opinions', null, token)
    check('G4 取消不留痕（库里仍是 3 条）', (afterCancel.body?.data || []).length, 3)

    // ================= H. 普通民警看不到入口 =================
    console.log('\n=== H. 普通民警侧（入口收敛 + 后端真拦截）===')
    const staffLogin = await login('e2e_staff', 'e2e123456')
    if (!staffLogin) {
      console.log('  ! 没有 e2e_staff 账号，跳过（先跑 scripts/verify_lan.py 生成）')
    } else {
      const { token: staffToken, ...staffUser } = staffLogin
      const staffEmpId = staffLogin.employeeId
      // 把自检案件指派给他，让他能看见这份案件详情
      const asg = await api('POST', '/cases/' + caseId + '/assign', { ownerId: staffEmpId, memberIds: [] }, token)
      console.log('  指派给普通民警 =', asg.body?.code)

      // 后端：普通民警调批量接口必须被拦
      const denied = await api('POST', '/watch/cases/' + caseId + '/opinions/batch',
        { items: [{ content: '普通民警不该能提意见' }] }, staffToken)
      console.log('  普通民警调批量接口 =', JSON.stringify(denied.body).slice(0, 160))
      check('H1 后端拦住（业务码 403）', denied.body?.code, 403)
      checkTrue('H2 拒绝理由是人话', String(denied.body?.msg || '').includes('管理员或领导'), denied.body?.msg)

      // 前端：待办面板在，但「＋ 添加」不给他
      await openAs(staffUser, staffToken, '/my-cases', 'CRIMINAL')
      await p.evaluate((nm) => {
        const row = [...document.querySelectorAll('.el-table__row')].find((r) => r.innerText.includes(nm))
        if (!row) return
        const btn = [...row.querySelectorAll('button')].find((x) => x.innerText.trim() === '详情')
        if (btn) btn.click()
        else row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
      }, name)
      await sleep(1700)
      const staffPanel = await p.evaluate(() => {
        const panel = document.querySelector('.cf-todo')
        if (!panel) return null
        return {
          hasAdd: [...panel.querySelectorAll('button')].some((x) => x.innerText.includes('添加')),
          cards: panel.querySelectorAll('.cf-todo__card').length
        }
      })
      console.log('  普通民警看到的待办面板 =', JSON.stringify(staffPanel))
      checkTrue('H3 面板照常能看到待办', staffPanel && staffPanel.cards === 3, JSON.stringify(staffPanel))
      check('H4 但不给「＋ 添加」入口（后端是 403，给他等于送他一个报错）',
        staffPanel?.hasAdd, false)
      await p.screenshot({ path: `${OUT}/tb3-staff-panel.png` })
      const stillThree2 = await api('GET', '/watch/cases/' + caseId + '/opinions', null, token)
      check('H5 普通民警确实没能加进去', (stillThree2.body?.data || []).length, 3)
    }

    // ================= 清理意见（再删案件） =================
    for (const oid of madeOpinionIds) {
      await api('POST', '/watch/opinions/' + oid + '/remove', {}, token)
    }

    console.log('\n控制台错误：' + (errs.length ? errs.join('\n  ') : 'none'))
  } finally {
    console.log('\n=== 清理 ===')
    if (caseId) {
      const r = await api('DELETE', '/cases/' + caseId, null, token)
      console.log(`  删自检案件 ${caseId} → ${r.body && r.body.code === 0 ? 'OK' : JSON.stringify(r.body)}`)
    }
    await b.close()
  }

  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail > 0 || errs.length > 0 ? 1 : 0)
}

run().catch((e) => { console.error(e); process.exit(1) })
