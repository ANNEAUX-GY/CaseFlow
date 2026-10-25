// 端到端：案件详情抽屉里的「编辑嫌疑人」（2026-10-22 用户要求）
//
// 覆盖点：
//   A. 嫌疑人行上有「编辑」入口（原来只有「删除」）
//   B. 点开是「编辑嫌疑人」且原值全部回显——姓名/身份证/电话/住址/强制措施/措施起止日
//   C. 保存走 PUT，只改的字段变，没动的（姓名、措施）不能丢
//   D. 保存后抽屉里的表格立即刷新成新值
//
// 【测试数据自管】自己造一条带强制措施的临时嫌疑人，跑完删掉，任何数据状态下可重跑。
//
// 用法：node scripts/cf-e2e-suspect-edit.mjs   （前端 5173 与后端 8080 需已启动）
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/盯办/_e2e_shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const TEMP_NAME = '自检编辑嫌疑人'
const NEW_PHONE = '13911112222'
const OLD_PHONE = '13800000000'

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

  // 登录（同源 fetch，避开沙箱对回环端口的代理干扰）
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
  const api = (path, method = 'GET', body) => p.evaluate(async (p2, m, bd) => {
    const r = await fetch(p2, {
      method: m,
      headers: { 'X-Token': localStorage.getItem('cf_token'), 'Content-Type': 'application/json' },
      body: bd ? JSON.stringify(bd) : undefined
    })
    return r.json()
  }, '/api' + path, method, body)

  /** 轮询等待条件成立 */
  const waitFor = async (fn, ms = 9000) => {
    for (let i = 0; i < ms / 200; i++) { if (await fn()) return true; await sleep(200) }
    return false
  }
  const drawerOpen = () => p.evaluate(() =>
    [...document.querySelectorAll('.el-drawer')].some((d) => d.getBoundingClientRect().width > 100))

  /**
   * 在列表里找到「嫌疑人」列含 marker 的行并点「详情」。
   *
   * <p>不能按编号列定位：2026-10-22 起列表的编号列已换成「报案/受案时间」，
   * 案号只出现在卡片标题里，表格中已无该列。用刚造的临时嫌疑人名做锚点最稳。
   */
  const clickDetail = (marker) => p.evaluate((mk) => {
    const heads = [...document.querySelectorAll('.el-table__header th')].map((x) => x.innerText.trim())
    const susIdx = heads.indexOf('嫌疑人')
    for (const tr of document.querySelectorAll('.el-table__row')) {
      const tds = [...tr.querySelectorAll('td')]
      if (!tds.length) continue
      const cell = susIdx >= 0 ? (tds[susIdx]?.innerText || '') : tr.innerText
      if (!cell.includes(mk)) continue
      const btn = [...tr.querySelectorAll('button')].find((x) => x.innerText.trim() === '详情')
      if (!btn) return 'no-btn'
      btn.click()
      return 'ok'
    }
    return 'no-row'
  }, marker)

  /** 抽屉内嫌疑人面板的某一行（按姓名定位） */
  const clickEdit = (name) => p.evaluate((nm) => {
    const panels = [...document.querySelectorAll('.el-drawer .cf-panel')]
    const panel = panels.find((pn) => (pn.querySelector('.cf-panel__head')?.innerText || '').includes('嫌疑人'))
    if (!panel) return 'no-panel'
    const rows = [...panel.querySelectorAll('.el-table__row')]
    const row = rows.find((r) => r.innerText.includes(nm))
    if (!row) return 'no-row:' + rows.map((r) => r.innerText.split('\n')[0]).join(',')
    const btn = [...row.querySelectorAll('button')].find((x) => x.innerText.trim() === '编辑')
    if (!btn) return 'no-btn:' + [...row.querySelectorAll('button')].map((x) => x.innerText.trim()).join(',')
    btn.click()
    return 'ok'
  }, name)

  const rowText = (name) => p.evaluate((nm) => {
    const panels = [...document.querySelectorAll('.el-drawer .cf-panel')]
    const panel = panels.find((pn) => (pn.querySelector('.cf-panel__head')?.innerText || '').includes('嫌疑人'))
    if (!panel) return '<无面板>'
    const row = [...panel.querySelectorAll('.el-table__row')].find((r) => r.innerText.includes(nm))
    return row ? row.innerText.replace(/\s+/g, ' ').trim() : '<无此行>'
  }, name)

  /** 读嫌疑人弹窗的标题与各字段值（未打开返回 null） */
  const readDialog = () => p.evaluate(() => {
    const ds = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getBoundingClientRect().width > 100)
    const d = ds.find((x) => (x.querySelector('.el-dialog__title')?.innerText || '').includes('嫌疑人'))
    if (!d) return null
    const items = [...d.querySelectorAll('.el-form-item')]
    const fi = (label) => items.find((x) => (x.querySelector('.el-form-item__label')?.innerText || '').trim().startsWith(label))
    const val = (label) => { const x = fi(label); return x ? (x.querySelector('input')?.value ?? '') : '<无此字段>' }
    const text = (label) => { const x = fi(label); return x ? x.innerText.replace(/\s+/g, ' ').trim() : '<无此字段>' }
    return {
      title: (d.querySelector('.el-dialog__title')?.innerText || '').trim(),
      name: val('姓名'),
      idCard: val('身份证号'),
      phone: val('联系电话'),
      address: val('住址'),
      measureText: text('强制措施'),
      startAt: val('措施开始'),
      endAt: val('措施结束')
    }
  })

  const setPhone = (v) => p.evaluate((val) => {
    const d = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getBoundingClientRect().width > 100)
      .find((x) => (x.querySelector('.el-dialog__title')?.innerText || '').includes('嫌疑人'))
    const fi = [...d.querySelectorAll('.el-form-item')]
      .find((x) => (x.querySelector('.el-form-item__label')?.innerText || '').trim().startsWith('联系电话'))
    const inp = fi.querySelector('input')
    inp.value = val
    inp.dispatchEvent(new Event('input', { bubbles: true }))
    return inp.value
  }, v)

  const clickSave = () => p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getBoundingClientRect().width > 100)
      .find((x) => (x.querySelector('.el-dialog__title')?.innerText || '').includes('嫌疑人'))
    if (!d) return 'no-dialog'
    const btn = [...d.querySelectorAll('.el-dialog__footer button')].find((x) => x.innerText.trim() === '保存')
    if (!btn) return 'no-btn'
    btn.click()
    return 'ok'
  })

  // ============ 准备：挑一个能在列表里看到的刑事案件，造一条带措施的临时嫌疑人 ============
  console.log('\n[准备] 造临时嫌疑人（拘留 2026-10-10 → 2026-10-20）')
  const list = (await api('/cases?page=1&size=100')).data.list
  const criminal = list.filter((c) => c.caseType === 'CRIMINAL')
  if (!criminal.length) throw new Error('库里没有刑事案件，无法测试')
  const target = criminal[0]
  console.log(`  目标案件 = ${target.caseNo}（${target.caseName || ''}）`)

  const created = (await api(`/cases/${target.id}/suspects`, 'POST', {
    name: TEMP_NAME, gender: 'MALE', idCard: '440106200001011234', phone: OLD_PHONE,
    address: '广东省广州市天河区自检路 1 号',
    measure: 'DETENTION', measureStartAt: '2026-10-10', measureEndAt: '2026-10-20'
  })).data.find((s) => s.name === TEMP_NAME)
  if (!created) throw new Error('临时嫌疑人没建成功')
  console.log(`  已建 id=${created.id}，措施=${created.measure} ${created.measureStartAt} → ${created.measureEndAt}`)

  let deleted = false
  try {
    // ============ A. 列表 → 详情 → 嫌疑人行有「修改」 ============
    console.log('\n[A] 从案件管理打开详情，嫌疑人行应有「编辑」入口')
    await p.goto(`${BASE}/#/cases`, { waitUntil: 'networkidle2' })
    await sleep(1500)
    // 库里案件多时分页会把目标行甩到后面，用关键词搜索锁定
    await p.evaluate((no) => {
      const inp = [...document.querySelectorAll('input')].find((x) => x.placeholder === '案件名 / 编号 / 备注')
      if (!inp) return
      inp.value = no
      inp.dispatchEvent(new Event('input', { bubbles: true }))
      inp.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, bubbles: true }))
    }, target.caseNo)
    await sleep(1600)
    const clicked = await clickDetail(TEMP_NAME)
    check('点开「详情」', clicked, 'ok')
    check('详情抽屉已弹出', await waitFor(drawerOpen), true)
    await sleep(900)

    const editClick = await clickEdit(TEMP_NAME)
    check('嫌疑人行有「编辑」按钮并可点', editClick, 'ok')

    // ============ B. 弹窗标题 + 原值回显 ============
    console.log('\n[B] 编辑弹窗应回显原值')
    check('弹窗已打开', await waitFor(async () => !!(await readDialog())), true)
    await sleep(400)
    const dlg = await readDialog()
    console.log('  弹窗 =', JSON.stringify(dlg))
    check('标题是「编辑嫌疑人」', dlg?.title, '编辑嫌疑人')
    check('姓名回显', dlg?.name, TEMP_NAME)
    check('身份证回显', dlg?.idCard, '440106200001011234')
    check('电话回显', dlg?.phone, OLD_PHONE)
    check('住址回显', dlg?.address, '广东省广州市天河区自检路 1 号')
    checkIn('强制措施回显「拘留」', dlg?.measureText, '拘留')
    check('措施开始日回显', dlg?.startAt, '2026-10-10')
    check('措施结束日回显', dlg?.endAt, '2026-10-20')
    await p.screenshot({ path: `${OUT}/SE1-dialog.png` })

    // ============ C. 只改电话，保存 ============
    console.log('\n[C] 改电话后保存')
    const typed = await setPhone(NEW_PHONE)
    check('电话输入框已改', typed, NEW_PHONE)
    check('点「保存」', await clickSave(), 'ok')
    await sleep(1800)
    check('弹窗已关闭', await waitFor(async () => !(await readDialog())), true)

    const after = (await api(`/cases/${target.id}/suspects`)).data.find((s) => s.id === created.id)
    console.log('  落库 =', JSON.stringify({
      name: after?.name, phone: after?.phone, measure: after?.measure,
      start: after?.measureStartAt, end: after?.measureEndAt
    }))
    check('电话已更新', after?.phone, NEW_PHONE)
    check('姓名未被改动', after?.name, TEMP_NAME)
    check('身份证未被改动', after?.idCard, '440106200001011234')
    check('措施仍是拘留（未被空值抹掉）', after?.measure, 'DETENTION')
    check('措施起止日仍保留', [after?.measureStartAt, after?.measureEndAt], ['2026-10-10', '2026-10-20'])

    // ============ D. 抽屉表格刷新 ============
    console.log('\n[D] 保存后抽屉表格立即刷新')
    await sleep(700)
    const txt = await rowText(TEMP_NAME)
    console.log('  行文本 =', txt)
    checkIn('表格里电话已变新值', txt, NEW_PHONE)
    checkIn('表格里仍显示拘留', txt, '拘留')
    await p.screenshot({ path: `${OUT}/SE2-after-save.png` })

    // ============ E. 再改一次：措施改回「未采取」应清空起止时间 ============
    console.log('\n[E] 措施改回「未采取」')
    check('再次点「编辑」', await clickEdit(TEMP_NAME), 'ok')
    await sleep(800)
    // 清空图标是 v-if 渲染、hover 才出现（Element Plus 的 showClearBtn），
    // 先真的把鼠标移进下拉框，再找 .el-select__clear
    const selBox = await p.evaluate(() => {
      const d = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getBoundingClientRect().width > 100)
        .find((x) => (x.querySelector('.el-dialog__title')?.innerText || '').includes('嫌疑人'))
      if (!d) return null
      const fi = [...d.querySelectorAll('.el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label')?.innerText || '').trim().startsWith('强制措施'))
      const sel = fi?.querySelector('.el-select')
      if (!sel) return null
      const r = sel.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    })
    check('拿到措施下拉坐标', !!selBox, true)
    if (selBox) {
      await p.mouse.move(selBox.x - 60, selBox.y)
      await sleep(150)
      await p.mouse.move(selBox.x, selBox.y)
      await sleep(600)
    }
    const cleared = await p.evaluate(() => {
      const d = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getBoundingClientRect().width > 100)
        .find((x) => (x.querySelector('.el-dialog__title')?.innerText || '').includes('嫌疑人'))
      if (!d) return 'no-dialog'
      const fi = [...d.querySelectorAll('.el-form-item')]
        .find((x) => (x.querySelector('.el-form-item__label')?.innerText || '').trim().startsWith('强制措施'))
      const clear = fi?.querySelector('.el-select__clear')
      if (!clear) return 'no-clear'
      clear.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      return 'ok'
    })
    check('点掉措施下拉的清空图标', cleared, 'ok')
    await sleep(500)
    check('保存', await clickSave(), 'ok')
    await sleep(1800)
    const cleared2 = (await api(`/cases/${target.id}/suspects`)).data.find((s) => s.id === created.id)
    console.log('  落库 =', JSON.stringify({
      measure: cleared2?.measure, start: cleared2?.measureStartAt, end: cleared2?.measureEndAt
    }))
    check('措施已改回 NONE', cleared2?.measure, 'NONE')
    check('措施起止时间已清空', [cleared2?.measureStartAt, cleared2?.measureEndAt], [null, null])
  } finally {
    await api(`/cases/suspects/${created.id}`, 'DELETE').catch(() => {})
    deleted = true
    console.log(`\n[清理] 临时嫌疑人已删除 = ${deleted}`)
  }

  console.log('\n控制台错误:', errs.length ? errs.slice(0, 6) : '无')
  if (errs.length) fail++
  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  await b.close()
  process.exit(fail ? 1 : 0)
}

run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
