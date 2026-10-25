// 抓 10-18 → 10-25 新功能的界面图，供《功能更新说明》Word 用（2026-10-25）
//
// 只造"措施到期"这一条测试数据（跑完删），其余都用库里现有真实数据截图。
// 用法：node scripts/cf-shots-updates.mjs   （前端 5173、后端 8080 需已启动）
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import { mkdirSync } from 'node:fs'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/案件指派demo/docs/manual-v4/shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
mkdirSync(OUT, { recursive: true })

const run = async () => {
  const b = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new',
    args: ['--no-sandbox', '--window-size=1920,1080']
  })
  const p = await b.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  await p.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1.25 })

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
  // 关键：注入 localStorage 后必须重载，否则 Pinia 的 userInfo 仍是空，
  // isFullAccess=false，列表里的「指派/编辑/删除」按钮都不渲染
  await p.reload({ waitUntil: 'networkidle2' })
  await sleep(800)
  const api = (path, method = 'GET', body) => p.evaluate(async (p2, m, bd) => {
    const r = await fetch(p2, {
      method: m,
      headers: { 'X-Token': localStorage.getItem('cf_token'), 'Content-Type': 'application/json' },
      body: bd ? JSON.stringify(bd) : undefined
    })
    return r.json()
  }, '/api' + path, method, body)

  /** 截某个元素（找不到就整页），返回文件名 */
  const shot = async (selector, name, pad = 0) => {
    await sleep(400)
    const el = await p.$(selector)
    if (el) {
      const box = await el.boundingBox()
      if (box) {
        await p.screenshot({
          path: `${OUT}/${name}.png`,
          clip: {
            x: Math.max(0, box.x - pad), y: Math.max(0, box.y - pad),
            width: Math.min(1600, box.width + pad * 2), height: Math.min(2400, box.height + pad * 2)
          }
        })
        console.log('  ✓', name, `(${Math.round(box.width)}x${Math.round(box.height)})`)
        return
      }
    }
    await p.screenshot({ path: `${OUT}/${name}.png` })
    console.log('  ✓', name, '(整页兜底)')
  }

  // ================= 造一条「措施到期」数据 =================
  console.log('\n[准备] 造措施到期数据')
  const list = (await api('/cases?page=1&size=100')).data.list
  const open = list.filter((c) => c.caseType === 'CRIMINAL' && !['DONE', 'CANCELLED'].includes(c.status))
  const target = open[0]
  const other = open[1] || open[0]
  console.log(`  案件：${target.caseNo} / ${other.caseNo}`)

  const mk = async (caseRow, name, measure, from, to, phone, idCard, address, remark) => {
    const r = await api(`/cases/${caseRow.id}/suspects`, 'POST', {
      name, gender: 'MALE', phone, measure, measureStartAt: from, measureEndAt: to,
      idCard, address, remark
    })
    return (r.data || []).find((s) => s.name === name)
  }
  const s1 = await mk(target, '示例甲', 'DETENTION', '2026-10-15', '2026-10-22', '13800000001',
    '440106199503127531', '广东省广州市天河区示意路 12 号 3 栋 501', '已送看守所，10-22 届满')
  const s2 = await mk(other, '示例乙', 'BAIL', '2026-10-20', '2026-10-28', '13800000002',
    '440106199811024428', '广东省广州市越秀区示意街 8 号', '取保候审，需定期报到')
  const dash = (await api('/cases/dashboard')).data
  console.log(`  到期统计：已逾期 ${dash.measureExpired}、7天内 ${dash.measureExpiring}、清单 ${(dash.measureAlerts || []).length} 条`)

  const created = [s1, s2].filter(Boolean)
  try {
    // ================= 1. 工作台：措施到期 KPI =================
    console.log('\n[1] 工作台')
    await p.goto(`${BASE}/#/dashboard`, { waitUntil: 'networkidle2' })
    await sleep(2500)
    await shot('.cf-kpi-grid', '01-dashboard-kpi', 8)
    // A4 版心只有 16cm，整行 9 个 KPI 缩进 Word 后字会小到看不清，
    // 另出一张"措施到期单卡 + 相邻两格"的特写图，正文里用它
    const kpiClip = await p.evaluate(() => {
      const cards = [...document.querySelectorAll('.cf-kpi')]
      const idx = cards.findIndex((c) => (c.innerText || '').includes('措施到期'))
      if (idx < 0) return null
      const from = Math.max(0, idx - 2)
      const first = cards[from].getBoundingClientRect()
      const last = cards[idx].getBoundingClientRect()
      return { x: first.x - 4, y: first.y - 4, w: last.right - first.x + 8, h: first.height + 8 }
    })
    if (kpiClip) {
      await p.screenshot({
        path: `${OUT}/01b-kpi-measure.png`,
        clip: { x: kpiClip.x, y: kpiClip.y, width: kpiClip.w, height: kpiClip.h }
      })
      console.log('  ✓ 01b-kpi-measure', `(${Math.round(kpiClip.w)}x${Math.round(kpiClip.h)})`)
    }
    // 措施到期面板：直接截元素，不做 window.scrollTo（滚动后 clip 的坐标基准会错位成空白图）
    const alertEl = (await p.evaluateHandle(() => [...document.querySelectorAll('.cf-panel')]
      .find((x) => (x.innerText || '').includes('强制措施到期')))).asElement()
    if (alertEl) {
      await alertEl.screenshot({ path: `${OUT}/02-dashboard-measure-alert.png` })
      const box = await alertEl.boundingBox()
      console.log('  ✓ 02-dashboard-measure-alert', box ? `(${Math.round(box.width)}x${Math.round(box.height)})` : '')
    }
    // 面板左侧 620px 特写：案件名称 + 措施 + 起算日，Word 里字才够大
    const alertClip = await p.evaluate(() => {
      const panel = [...document.querySelectorAll('.cf-panel')]
        .find((x) => (x.innerText || '').includes('强制措施到期'))
      const tbl = panel?.querySelector('.el-table')
      if (!tbl) return null
      const r = tbl.getBoundingClientRect()
      return { x: r.x, y: r.y, w: Math.min(900, r.width), h: Math.min(r.height, 200) }
    })
    if (alertClip) {
      await p.screenshot({
        path: `${OUT}/02b-alert-closeup.png`,
        clip: { x: alertClip.x, y: alertClip.y, width: alertClip.w, height: alertClip.h }
      })
      console.log('  ✓ 02b-alert-closeup', `(${Math.round(alertClip.w)}x${Math.round(alertClip.h)})`)
    }

    // ================= 2. 我的案件：到期明细 + 登记入口 =================
    console.log('\n[2] 我的案件')
    await p.goto(`${BASE}/#/my-cases`, { waitUntil: 'networkidle2' })
    await sleep(2200)
    const mineEl = await p.$('#cf-mine-measure')
    if (mineEl) {
      await mineEl.screenshot({ path: `${OUT}/03-my-cases-measure.png` })
      const mb = await mineEl.boundingBox()
      console.log('  ✓ 03-my-cases-measure', mb ? `(${Math.round(mb.width)}x${Math.round(mb.height)})` : '')
    }
    const mineClip = await p.evaluate(() => {
      const row = document.querySelector('#cf-mine-measure .cf-measure-list')
      if (!row) return null
      const r = row.getBoundingClientRect()
      return { x: r.x, y: r.y, w: Math.min(900, r.width), h: r.height }
    })
    if (mineClip) {
      await p.screenshot({
        path: `${OUT}/03b-mine-closeup.png`,
        clip: { x: mineClip.x, y: mineClip.y, width: mineClip.w, height: mineClip.h }
      })
      console.log('  ✓ 03b-mine-closeup', `(${Math.round(mineClip.w)}x${Math.round(mineClip.h)})`)
    }

    // ================= 3. 案件列表：报案/受案时间 =================
    console.log('\n[3] 案件列表')
    await p.goto(`${BASE}/#/cases`, { waitUntil: 'networkidle2' })
    await sleep(2200)
    // 按真实案件名过滤掉 TDS / E2E 自检数据，截图里只留业务数据
    await p.evaluate(() => {
      const inp = [...document.querySelectorAll('input')].find((x) => x.placeholder === '案件名 / 编号 / 备注')
      if (!inp) return
      inp.value = 'XX'
      inp.dispatchEvent(new Event('input', { bubbles: true }))
      inp.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, bubbles: true }))
    })
    await sleep(1800)
    // 只截到「状态」列：操作列在 1920 以下会被挤压成省略号，正文里不展示更干净
    const cut = await p.evaluate(() => {
      const tbl = document.querySelector('.el-table')
      const ths = [...document.querySelectorAll('.el-table__header th')]
      const st = ths.find((t) => t.innerText.trim() === '状态')
      if (!tbl || !st) return null
      const r = tbl.getBoundingClientRect(), sr = st.getBoundingClientRect()
      return { x: r.x, y: r.y, w: sr.right - r.x + 2, h: Math.min(r.height, 620) }
    })
    if (cut) {
      await p.screenshot({
        path: `${OUT}/04-case-list.png`,
        clip: { x: cut.x, y: cut.y, width: cut.w, height: cut.h }
      })
      console.log('  ✓ 04-case-list', `(${Math.round(cut.w)}x${Math.round(cut.h)})`)
    }
    // 前三列特写（时间 + 案件名称 + 类型），正文用它才能看清"报案/受案"标签
    const listClip = await p.evaluate(() => {
      const tbl = document.querySelector('.el-table')
      const ths = [...document.querySelectorAll('.el-table__header th')]
      const tp = ths.find((t) => t.innerText.trim() === '办案阶段')
      if (!tbl || !tp) return null
      const r = tbl.getBoundingClientRect(), tr = tp.getBoundingClientRect()
      const headH = ths[0] ? ths[0].getBoundingClientRect().height : 40
      return { x: r.x, y: r.y, w: tr.right - r.x, h: headH + 3 * 62 }
    })
    if (listClip) {
      await p.screenshot({
        path: `${OUT}/04b-list-closeup.png`,
        clip: { x: listClip.x, y: listClip.y, width: listClip.w, height: listClip.h }
      })
      console.log('  ✓ 04b-list-closeup', `(${Math.round(listClip.w)}x${Math.round(listClip.h)})`)
    }

    // ================= 4. 详情抽屉：嫌疑人面板（编辑入口） =================
    console.log('\n[4] 案件详情抽屉')
    const OK = await p.evaluate((nm) => {
      const heads = [...document.querySelectorAll('.el-table__header th')].map((x) => x.innerText.trim())
      const susIdx = heads.indexOf('嫌疑人')
      for (const tr of document.querySelectorAll('.el-table__row')) {
        const tds = [...tr.querySelectorAll('td')]
        if (!(susIdx >= 0 ? (tds[susIdx]?.innerText || '') : tr.innerText).includes(nm)) continue
        const btn = [...tr.querySelectorAll('button')].find((x) => x.innerText.trim() === '详情')
        if (btn) { btn.click(); return true }
      }
      return false
    }, '示例甲')
    console.log('  打开抽屉 =', OK)
    await sleep(2200)
    // 嫌疑人面板 = 含「嫌疑人（N 人）」标题的 panel
    const susPanel = await p.evaluateHandle(() => {
      const panels = [...document.querySelectorAll('.el-drawer .cf-panel')]
      return panels.find((x) => (x.querySelector('.cf-panel__head')?.innerText || '').includes('嫌疑人'))
    })
    if (susPanel) {
      const el = susPanel.asElement()
      if (el) {
        await el.screenshot({ path: `${OUT}/05-drawer-suspect.png` })
        const box = await el.boundingBox()
        console.log('  ✓ 05-drawer-suspect', box ? `(${Math.round(box.width)}x${Math.round(box.height)})` : '')
      }
    }
    await p.screenshot({ path: `${OUT}/05b-drawer-full.png` })
    console.log('  ✓ 05b-drawer-full')
    // 案件待办卡片（2026-10-18 紧凑化）：截抽屉里那块面板
    const todoEl = (await p.evaluateHandle(() => [...document.querySelectorAll('.el-drawer .cf-panel')]
      .find((x) => (x.querySelector('.cf-panel__head')?.innerText || '').includes('待办')))).asElement()
    if (todoEl) {
      await todoEl.screenshot({ path: `${OUT}/09b-todo-card.png` })
      const tb2 = await todoEl.boundingBox()
      console.log('  ✓ 09b-todo-card', tb2 ? `(${Math.round(tb2.width)}x${Math.round(tb2.height)})` : '')
    }

    // 抽屉只有 46% 宽，表格列多时会横向滚动；单独截一张"滚到最右"的操作列图，
    // 文档里用来指明「编辑 / 删除」按钮的位置
    const scrollInfo = await p.evaluate(() => {
      const panel = [...document.querySelectorAll('.el-drawer .cf-panel')]
        .find((x) => (x.querySelector('.cf-panel__head')?.innerText || '').includes('嫌疑人'))
      const wrap = panel?.querySelector('.el-table__body-wrapper .el-scrollbar__wrap')
        || panel?.querySelector('.el-scrollbar__wrap')
      if (!wrap) return null
      wrap.scrollLeft = wrap.scrollWidth
      return { sw: wrap.scrollWidth, cw: wrap.clientWidth }
    })
    console.log('  横向滚动 =', JSON.stringify(scrollInfo))
    if (scrollInfo && scrollInfo.sw > scrollInfo.cw + 4) {
      await sleep(500)
      const pb = await (await p.evaluateHandle(() => [...document.querySelectorAll('.el-drawer .cf-panel')]
        .find((x) => (x.querySelector('.cf-panel__head')?.innerText || '').includes('嫌疑人')))).asElement().boundingBox()
      const w = 340
      await p.screenshot({
        path: `${OUT}/05c-suspect-actions.png`,
        clip: { x: pb.x + pb.width - w, y: pb.y, width: w, height: pb.height }
      })
      console.log('  ✓ 05c-suspect-actions')
    }

    // ================= 5. 编辑嫌疑人弹窗（640px） =================
    console.log('\n[5] 编辑嫌疑人弹窗')
    const editOk = await p.evaluate(() => {
      const panels = [...document.querySelectorAll('.el-drawer .cf-panel')]
      const panel = panels.find((x) => (x.querySelector('.cf-panel__head')?.innerText || '').includes('嫌疑人'))
      const row = [...(panel?.querySelectorAll('.el-table__row') || [])].find((r) => r.innerText.includes('示例甲'))
      const btn = [...(row?.querySelectorAll('button') || [])].find((x) => x.innerText.trim() === '编辑')
      if (!btn) return false
      btn.click()
      return true
    })
    console.log('  点编辑 =', editOk)
    await sleep(1200)
    const dlg = await p.evaluateHandle(() => {
      const ds = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getBoundingClientRect().width > 100)
      return ds.find((x) => (x.querySelector('.el-dialog__title')?.innerText || '').includes('嫌疑人'))
    })
    const dlgEl = dlg.asElement()
    if (dlgEl) {
      const box = await dlgEl.boundingBox()
      if (box) {
        await p.screenshot({
          path: `${OUT}/06-suspect-dialog.png`,
          clip: { x: box.x - 8, y: box.y - 8, width: box.width + 16, height: box.height + 16 }
        })
        console.log('  ✓ 06-suspect-dialog', `(${Math.round(box.width)}x${Math.round(box.height)})`)
      }
    }
    // 关闭弹窗
    await p.evaluate(() => {
      const d = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getBoundingClientRect().width > 100)
        .find((x) => (x.querySelector('.el-dialog__title')?.innerText || '').includes('嫌疑人'))
      const btn = [...(d?.querySelectorAll('.el-dialog__footer button') || [])].find((x) => x.innerText.trim() === '取消')
      btn && btn.click()
    })
    await sleep(600)

    // ================= 6. 登记下一措施弹窗 =================
    console.log('\n[6] 登记下一措施')
    await p.goto(`${BASE}/#/my-cases`, { waitUntil: 'networkidle2' })
    await sleep(2400)
    const measureOk = await p.evaluate(() => {
      const btn = [...document.querySelectorAll('#cf-mine-measure button')].find((x) => x.innerText.trim() === '登记下一措施')
      if (!btn) return false
      btn.click()
      return true
    })
    console.log('  点登记 =', measureOk)
    await sleep(1400)
    const mDlg = await p.evaluateHandle(() => {
      const ds = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getBoundingClientRect().width > 100)
      return ds.find((x) => (x.querySelector('.el-dialog__title')?.innerText || '').includes('措施'))
    })
    const mEl = mDlg.asElement()
    if (mEl) {
      const box = await mEl.boundingBox()
      if (box) {
        await p.screenshot({
          path: `${OUT}/07-measure-dialog.png`,
          clip: { x: box.x - 8, y: box.y - 8, width: box.width + 16, height: box.height + 16 }
        })
        console.log('  ✓ 07-measure-dialog', `(${Math.round(box.width)}x${Math.round(box.height)})`)
      }
    }
    await p.keyboard.press('Escape')
    await sleep(600)

    // ================= 7. 盯办抽屉（措施 + 审批） =================
    console.log('\n[7] 案件盯办')
    await p.goto(`${BASE}/#/watch`, { waitUntil: 'networkidle2' })
    await sleep(2600)
    const watchOpen = await p.evaluate(() => {
      const tr = document.querySelector('.el-table__row')
      if (!tr) return false
      const btn = [...tr.querySelectorAll('button')].find((x) => /详情|盯办|查看/.test(x.innerText.trim()))
      if (btn) { btn.click(); return btn.innerText.trim() }
      tr.click()
      return 'row-click'
    })
    console.log('  打开 =', watchOpen)
    await sleep(2200)
    await p.screenshot({ path: `${OUT}/08-watch-drawer.png` })
    console.log('  ✓ 08-watch-drawer')
    // 抽屉里「强制措施与期限」区块特写：正文要看清"登记 / 变更"入口
    const meaBlock = await p.evaluate(() => {
      const panels = [...document.querySelectorAll('.el-drawer .cf-panel')]
      const el = panels.find((x) => (x.innerText || '').includes('强制措施与期限'))
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x, y: r.y, w: Math.min(r.width, 620), h: r.height }
    })
    if (meaBlock) {
      await p.screenshot({
        path: `${OUT}/08b-watch-measure.png`,
        clip: { x: meaBlock.x, y: meaBlock.y, width: meaBlock.w, height: meaBlock.h }
      })
      console.log('  ✓ 08b-watch-measure', `(${Math.round(meaBlock.w)}x${Math.round(meaBlock.h)})`)
    }

    // ================= 8. 待办（卡片更紧凑） =================
    console.log('\n[8] 待办')
    await p.goto(`${BASE}/#/todos`, { waitUntil: 'networkidle2' })
    await sleep(2200)
    await p.screenshot({ path: `${OUT}/09-todos.png` })
    console.log('  ✓ 09-todos')
    // 单张待办卡片特写（整页缩进 Word 后按钮文字看不清）
    const cardClip = await p.evaluate(() => {
      const cards = [...document.querySelectorAll('.cf-todo-card, .cf-card, .el-card')]
      const el = cards.find((c) => c.getBoundingClientRect().height > 90 && c.getBoundingClientRect().width > 300)
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x - 4, y: r.y - 4, w: Math.min(r.width, 760) + 8, h: r.height + 8 }
    })
    if (cardClip) {
      await p.screenshot({
        path: `${OUT}/09b-todo-card.png`,
        clip: { x: cardClip.x, y: cardClip.y, width: cardClip.w, height: cardClip.h }
      })
      console.log('  ✓ 09b-todo-card', `(${Math.round(cardClip.w)}x${Math.round(cardClip.h)})`)
    }
  } finally {
    for (const s of created) await api(`/cases/suspects/${s.id}`, 'DELETE').catch(() => {})
    console.log(`\n[清理] 删除测试嫌疑人 ${created.length} 条`)
  }

  console.log('\n页面错误:', errs.length ? errs.slice(0, 5) : '无')
  await b.close()
}

run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
