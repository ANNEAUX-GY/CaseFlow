// 子任务详情 + 反馈可编辑：普通用户与管理层两端核对
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'
import fs from 'fs'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/案件指派demo/shots'
fs.mkdirSync(OUT, { recursive: true })

const loginAs = async (page, username, password) => {
  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle2' })
  const r = await page.evaluate(async (u, p) => {
    const res = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: u, password: p })
    })
    const j = await res.json()
    if (j.code !== 0) return { ok: false, msg: j.msg }
    const { token, ...userInfo } = j.data
    localStorage.setItem('cf_token', token)
    localStorage.setItem('cf_user', JSON.stringify(userInfo))
    // 案件类型门控：盯办/待办/案件管理/到期提醒四个栏目没选类型会被守卫弹去选择页，
    // 这里直接选刑事案件，否则后面所有断言都在选择页上跑（白跑一轮）。
    localStorage.setItem('cf_case_type', 'CRIMINAL')
    return { ok: true, name: userInfo.displayName }
  }, username, password)
  if (!r.ok) throw new Error('登录失败 ' + username + ': ' + r.msg)
  return r.name
}

/** 跳到某页并**强制整页重载**。
 *  只改 hash 不会重新执行 main.js，Pinia store 里的旧 userInfo 还在，
 *  会出现「登录了 boss 但侧栏还是民警菜单」——必须 reload 才生效。 */
const goto = async (page, hash) => {
  await page.goto(`${BASE}/#${hash}`, { waitUntil: 'networkidle2' })
  await page.reload({ waitUntil: 'networkidle2' })
}


/** 等某个选择器出现（最多 timeoutMs）。抽屉内容是异步拉的，
 *  固定 sleep 会拍到半空的面板——曾因此误判「面板不可见」。 */
const waitFor = async (page, sel, timeoutMs = 15000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const ok = await page.evaluate((s) => !!document.querySelector(s), sel)
    if (ok) return true
    await new Promise((r) => setTimeout(r, 400))
  }
  return false
}

const run = async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  })
  const page = await browser.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text()) })
  await page.setViewport({ width: 1440, height: 950 })

  // ---------- 管理层 ----------
  await loginAs(page, 'boss', 'admin123')
  await goto(page, '/watch')
  await new Promise((r) => setTimeout(r, 2500))
  // 打开第一条案件的盯办抽屉：先看页面上到底有什么可点的
  const probe = await page.evaluate(() => ({
    url: location.hash,
    rows: document.querySelectorAll('.el-table__row').length,
    btns: [...document.querySelectorAll('button')].map((b) => b.innerText.trim()).filter(Boolean).slice(0, 14),
    text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 200)
  }))
  console.log('探针:', JSON.stringify(probe, null, 1))
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => x.innerText.includes('侦查详情') || x.innerText.includes('详情'))
    b?.click()
  })
  await waitFor(page, '.cf-todo__cards')
  const hasPanel = await page.evaluate(() => !!document.querySelector('.cf-todo__cards'))
  console.log('管理层：案件待办面板可见 =', hasPanel)

  // 展开第一张卡的子任务区
  await page.evaluate(() => {
    const tg = document.querySelector('.cf-todo__sub-toggle')
    tg?.click()
  })
  await new Promise((r) => setTimeout(r, 1500))
  const subRows = await page.evaluate(() => {
    const out = []
    document.querySelectorAll('.cf-todo__sub').forEach((s) => {
      const btn = [...s.querySelectorAll('button')].find((b) => b.innerText.trim() === '详情')
      out.push({ text: s.innerText.replace(/\s+/g, ' ').trim().slice(0, 60), hasDetailBtn: !!btn })
    })
    return out
  })
  console.log('管理层：子任务行 =', JSON.stringify(subRows, null, 1))
  await page.screenshot({ path: `${OUT}/sub-panel-admin.png` })

  // 点子任务「详情」→ 应打开该子任务自己的详情浮窗
  await page.evaluate(() => {
    const s = document.querySelector('.cf-todo__sub')
    const btn = [...s.querySelectorAll('button')].find((b) => b.innerText.trim() === '详情')
    btn?.click()
  })
  await waitFor(page, '.cf-td__sec')
  await new Promise((r) => setTimeout(r, 900))
  let dlg = await page.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('反馈记录'))
    if (!d) return null
    return {
      title: d.querySelector('.el-dialog__title')?.innerText,
      hasBack: !!d.querySelector('.cf-td__back'),
      backText: d.querySelector('.cf-td__back')?.innerText.replace(/\s+/g, ' ').trim(),
      isSubtag: d.innerText.includes('子任务'),
      fbBlocks: [...d.querySelectorAll('.cf-td__fb')].map((f) => ({
        meta: f.querySelector('.cf-td__fb-meta')?.innerText.replace(/\s+/g, ' ').trim().slice(0, 70),
        declare: f.querySelector('.cf-td__fb-declare')?.innerText.replace(/\s+/g, ' ').trim(),
        hasEdit: !![...f.querySelectorAll('button')].find((b) => b.innerText.trim() === '修改')
      }))
    }
  })
  console.log('管理层：子任务详情浮窗 =', JSON.stringify(dlg, null, 1))
  await page.screenshot({ path: `${OUT}/sub-detail-admin.png` })

  // 点「修改」看回填
  if (dlg?.fbBlocks?.length) {
    await page.evaluate(() => {
      const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('反馈记录'))
      const fb = d.querySelector('.cf-td__fb')
      const btn = [...fb.querySelectorAll('button')].find((b) => b.innerText.trim() === '修改')
      btn?.click()
    })
    await new Promise((r) => setTimeout(r, 1500))
    const editDlg = await page.evaluate(() => {
      const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('修改反馈'))
      if (!d) return null
      const inputs = [...d.querySelectorAll('.cf-fb__declare input')].map((i) => i.value)
      return {
        title: d.querySelector('.el-dialog__title')?.innerText,
        confirmBtn: [...d.querySelectorAll('button')].map((b) => b.innerText.trim()).filter(Boolean).pop(),
        note: d.querySelector('textarea')?.value,
        declareInputs: inputs
      }
    })
    console.log('管理层：修改反馈弹窗回填 =', JSON.stringify(editDlg, null, 1))
    await page.screenshot({ path: `${OUT}/sub-edit-fb-admin.png` })
    await page.keyboard.press('Escape')
    await new Promise((r) => setTimeout(r, 500))
  }
  // 返回主任务
  await page.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('反馈记录'))
    d?.querySelector('.cf-td__back button')?.click()
  })
  await new Promise((r) => setTimeout(r, 1800))
  const afterBack = await page.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('反馈记录'))
    return { title: d?.querySelector('.el-dialog__title')?.innerText, hasBack: !!d?.querySelector('.cf-td__back') }
  })
  console.log('管理层：返回后 =', JSON.stringify(afterBack))
  await page.screenshot({ path: `${OUT}/sub-back-admin.png` })

  // ---------- 普通用户 ----------
  console.log('\n--- 普通用户 ---')
  await loginAs(page, 'test1', 'e2e123456')
  await goto(page, '/my-cases')
  await new Promise((r) => setTimeout(r, 2500))
  const sProbe = await page.evaluate(() => ({
    url: location.hash,
    rows: document.querySelectorAll('.el-table__row').length,
    btns: [...document.querySelectorAll('button')].map((b) => b.innerText.trim()).filter(Boolean).slice(0, 12)
  }))
  console.log('普通用户探针:', JSON.stringify(sProbe))
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => x.innerText.includes('侦查详情') || x.innerText.includes('详情'))
    b?.click()
  })
  await waitFor(page, '.cf-todo__cards')
  const staffPanel = await page.evaluate(() => !!document.querySelector('.cf-todo__cards'))
  console.log('普通用户：案件待办面板可见 =', staffPanel)
  await page.evaluate(() => document.querySelector('.cf-todo__sub-toggle')?.click())
  await new Promise((r) => setTimeout(r, 1500))
  const staffSubs = await page.evaluate(() => {
    const out = []
    document.querySelectorAll('.cf-todo__sub').forEach((s) => {
      const btn = [...s.querySelectorAll('button')].find((b) => b.innerText.trim() === '详情')
      out.push({ text: s.innerText.replace(/\s+/g, ' ').trim().slice(0, 50), hasDetailBtn: !!btn })
    })
    return out
  })
  console.log('普通用户：子任务行 =', JSON.stringify(staffSubs))
  await page.evaluate(() => {
    const s = document.querySelector('.cf-todo__sub')
    const btn = [...s.querySelectorAll('button')].find((b) => b.innerText.trim() === '详情')
    btn?.click()
  })
  await waitFor(page, '.cf-td__sec')
  await new Promise((r) => setTimeout(r, 900))
  const staffDlg = await page.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('反馈记录'))
    if (!d) return null
    return {
      title: d.querySelector('.el-dialog__title')?.innerText,
      hasBack: !!d.querySelector('.cf-td__back'),
      fbCount: d.querySelectorAll('.cf-td__fb').length,
      editBtns: [...d.querySelectorAll('.cf-td__fb button')].filter((b) => b.innerText.trim() === '修改').length
    }
  })
  console.log('普通用户：子任务详情浮窗 =', JSON.stringify(staffDlg))
  await page.screenshot({ path: `${OUT}/sub-detail-staff.png` })

  console.log('\n错误:', errs.length ? errs.slice(0, 8) : '无')
  await browser.close()
}
run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
