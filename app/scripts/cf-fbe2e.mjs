// 端到端：子任务详情里改「上传平台 / 上传文件名」——只改一项，其余不动
// 从案件盯办页进（那里能拿到带上传声明的真实反馈）
import puppeteer from 'file:///C:/Users/adamin/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = 'http://127.0.0.1:5173'
const OUT = 'D:/案件指派demo/shots'

const run = async () => {
  const b = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new', args: ['--no-sandbox']
  })
  const p = await b.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 200)) })
  await p.setViewport({ width: 1440, height: 950 })

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
  await p.goto(`${BASE}/#/watch`, { waitUntil: 'networkidle2' })
  await p.reload({ waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 2500))
  await p.evaluate(() => {
    ;[...document.querySelectorAll('button')].find((x) => x.innerText.includes('侦查详情'))?.click()
  })
  // 等待办卡片真的渲染出来（抽屉内容异步拉，固定 sleep 会拍到半空的面板）
  for (let i = 0; i < 30 && !(await p.evaluate(() => !!document.querySelector('.cf-todo__cards'))); i++) {
    await new Promise((r) => setTimeout(r, 400))
  }

  const opened = await p.evaluate(() => {
    for (const c of document.querySelectorAll('.cf-todo__card')) {
      if (c.innerText.includes('与受害人见面')) { c.querySelector('.cf-todo__sub-toggle')?.click(); return true }
    }
    return false
  })
  console.log('展开「与受害人见面」子任务区 =', opened)
  await new Promise((r) => setTimeout(r, 2000))

  const clicked = await p.evaluate(() => {
    for (const s of document.querySelectorAll('.cf-todo__sub')) {
      if (s.innerText.includes('完成物证调取')) {
        ;[...s.querySelectorAll('button')].find((x) => x.innerText.trim() === '详情')?.click()
        return true
      }
    }
    return false
  })
  console.log('点子任务详情 =', clicked)
  for (let i = 0; i < 25 && !(await p.evaluate(() => !!document.querySelector('.cf-td__fb'))); i++) {
    await new Promise((r) => setTimeout(r, 400))
  }
  await new Promise((r) => setTimeout(r, 800))

  const view = await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('反馈记录'))
    if (!d) return null
    return {
      title: d.querySelector('.el-dialog__title')?.innerText,
      back: d.querySelector('.cf-td__back')?.innerText.replace(/\s+/g, ' ').trim(),
      declare: [...d.querySelectorAll('.cf-td__fb-declare')].map((x) => x.innerText.replace(/\s+/g, ' ').trim()),
      text: [...d.querySelectorAll('.cf-td__fb-text')].map((x) => x.innerText.trim())
    }
  })
  console.log('子任务详情 =', JSON.stringify(view, null, 1))
  await p.screenshot({ path: `${OUT}/sub-declare-view.png` })

  await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('反馈记录'))
    ;[...d.querySelectorAll('.cf-td__fb button')].find((x) => x.innerText.trim() === '修改')?.click()
  })
  await new Promise((r) => setTimeout(r, 1500))
  const before = await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('修改反馈'))
    if (!d) return null
    return {
      inputs: [...d.querySelectorAll('.cf-fb__declare input')].map((i) => i.value),
      note: d.querySelector('textarea')?.value
    }
  })
  console.log('修改弹窗回填 =', JSON.stringify(before))

  // 只改「文件名称」那一个输入框（声明区最后一个）
  await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('修改反馈'))
    const inputs = [...d.querySelectorAll('.cf-fb__declare input')]
    const el = inputs[inputs.length - 1]
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(el, '123_已核对.pdf')
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await new Promise((r) => setTimeout(r, 400))
  await p.screenshot({ path: `${OUT}/sub-edit-declare.png` })

  await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('修改反馈'))
    ;[...d.querySelectorAll('button')].find((x) => x.innerText.trim() === '保存修改')?.click()
  })
  await new Promise((r) => setTimeout(r, 2500))
  const after = await p.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.innerText.includes('反馈记录'))
    return {
      declare: [...d.querySelectorAll('.cf-td__fb-declare')].map((x) => x.innerText.replace(/\s+/g, ' ').trim()),
      edited: [...d.querySelectorAll('.cf-td__fb-edited')].map((x) => x.innerText.replace(/\s+/g, ' ').trim())
    }
  })
  console.log('保存后 =', JSON.stringify(after, null, 1))
  await p.screenshot({ path: `${OUT}/sub-declare-saved.png` })
  console.log('错误:', errs.length ? errs.slice(0, 5) : '无')
  await b.close()
}
run().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
