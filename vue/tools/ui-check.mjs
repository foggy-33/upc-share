/* 批量上传页的无头交互验证（需要先起 mock 后端和一个开了调试端口的 Chrome）。
 *
 *   node tools/mock-upcshare-api.cjs &
 *   npm run dev &
 *   chrome --headless=new --remote-debugging-port=9447 \
 *     --user-data-dir=%TEMP%/upc-chrome http://127.0.0.1:5173/admin &
 *   node tools/ui-check.mjs
 *
 * 18 项断言：没选学科分类不给建子目录、默认名编号、站内已有子目录自动带出、
 * 上传请求次数、管理员自动通过审核、已有资源按学科分类+子目录过滤、两段式删除、
 * 整理规范弹层（内容 / 按钮 / 复制反馈 / 关闭）、课程栏三个盒子对齐。
 * 全程只打本地 mock，不会碰生产站。
 */
import { writeFileSync } from 'node:fs'

const CDP = 'http://127.0.0.1:9447'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const list = await (await fetch(CDP + '/json/list')).json()
const page = list.find((t) => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl)
let seq = 0
const pending = new Map()
ws.onmessage = (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id)
    pending.delete(m.id)
    m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
  }
}
await new Promise((r) => { ws.onopen = r })
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++seq
  pending.set(id, { resolve, reject })
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
  return r.result.value
}
await send('Runtime.enable')
await send('Page.enable')
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

async function click(sel) {
  await ev(`document.querySelector(${JSON.stringify(sel)})?.scrollIntoView({block:'center'})`)
  await sleep(160)
  const box = JSON.parse(await ev(`JSON.stringify(document.querySelector(${JSON.stringify(sel)})?.getBoundingClientRect()||null)`))
  if (!box) throw new Error('找不到元素 ' + sel)
  const x = box.x + box.width / 2, y = box.y + box.height / 2
  for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1, buttons: type === 'mousePressed' ? 1 : 0 })
  }
  await sleep(220)
}
async function type(sel, text) {
  await click(sel)
  await sleep(120)
  await send('Input.insertText', { text })
  await sleep(260)
}
async function blur() {
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await ev(`document.activeElement && document.activeElement.blur()`)
  await sleep(900)
}
const SNAP = `(() => {
  const q = (s) => document.querySelector(s)
  const add = q('.add-dir')
  return JSON.stringify({
    course: q('#batch-course') ? q('#batch-course').value : null,
    dirs: [...document.querySelectorAll('.dir-name')].map(n => n.textContent),
    files: document.querySelectorAll('.file-row').length,
    addDisabled: add ? add.disabled : null,
    addTitle: add ? add.title : null,
    hint: q('.batch-course-bar .form-message') ? q('.batch-course-bar .form-message').textContent.trim() : '',
    uploadAllDisabled: q('.queue .btn-upload:last-of-type') ? q('.queue .btn-upload:last-of-type').disabled : null,
    log: [...document.querySelectorAll('.log-line')].map(l => l.textContent).slice(-3).join(' | ')
  })
})()`
const snap = async () => JSON.parse(await ev(SNAP))

let failed = 0
const check = (name, ok, got) => {
  console.log((ok ? '  ok  ' : '  FAIL ') + name + (ok ? '' : '  →  ' + got))
  if (!ok) failed++
}

await send('Page.navigate', { url: 'http://127.0.0.1:5173/admin' })
await ev(`fetch('/api/__reset').then(r => r.ok).catch(() => false)`)
for (let i = 0; i < 60; i++) {
  await sleep(300)
  if (await ev(`!!document.querySelector('.add-dir')`)) break
}
await sleep(1200)

const a = await snap()
check('1 页面渲染出来了（导航 + 学科分类栏 + 队列）', a.course === '' && a.dirs.length === 0 && a.addDisabled === true, JSON.stringify(a))

await click('.add-dir')
const b = await snap()
check('2 没选学科分类：点「新建子目录」建不出东西，也不弹提示', b.dirs.length === 0 && b.hint === '', JSON.stringify(b))

await type('#batch-course', '高等数学2-2')
await blur()
const c = await snap()
check('3 输入站内已有学科分类 → 自动带出站内子目录', /高等数学2-2/.test(c.course) && c.dirs.includes('期末试卷') && c.dirs.includes('课件'), JSON.stringify(c))
check('4 选了学科分类后「新建子目录」解禁', c.addDisabled === false && c.addTitle === '', JSON.stringify(c))

await click('.add-dir')
await click('.add-dir')
const d = await snap()
check('5 连点两次：默认名分得开（新子目录 / 新子目录 2）',
  d.dirs.slice(-2).join('|') === '新子目录|新子目录 2', JSON.stringify(d))

// 往当前子目录塞两个文件
await ev(`(() => {
  const fp = document.querySelector('[data-role=file-picker]')
  const dt = new DataTransfer()
  dt.items.add(new File(['hello'], '第一章.pdf', { type: 'application/pdf' }))
  dt.items.add(new File(['x'], '第二章.pdf', { type: 'application/pdf' }))
  fp.files = dt.files
  fp.dispatchEvent(new Event('change', { bubbles: true }))
  return true
})()`)
await sleep(900)
const e = await snap()
check('6 文件进了当前子目录，上传按钮亮起', e.files === 2 && e.uploadAllDisabled === false, JSON.stringify(e))

// 勾上「自动通过审核」再全部上传
await click('.queue .opt:nth-child(2) input')
await click('.queue .btn-upload:last-of-type')
await sleep(3500)
const reqs = await ev(`fetch('/api/__log').then(r => r.json())`)
const uploads = reqs.filter((r) => r.startsWith('POST /api/upload'))
const approves = reqs.filter((r) => r.startsWith('POST /api/admin/approve'))
check('7 两个文件 = 两次上传请求', uploads.length === 2, JSON.stringify(uploads))
check('8 管理员勾了自动通过 → 各调一次 approve', approves.length === 2, JSON.stringify(approves))
const f = await snap()
check('9 日志报告完成 2/2', /完成 2\/2/.test(f.log), JSON.stringify(f.log))

// 已有资源：新传的两个文件在「新子目录 2」下，且不该看到别的学科分类的待审核文件
await click('.work-head .btn-upload.small')
await sleep(1200)
const siteRows = await ev(`[...document.querySelectorAll('.site-row .site-name')].map(n => n.textContent)`)
check('10 已有资源只列本学科分类 + 本子目录的文件',
  siteRows.length === 2 && siteRows.every((n) => /第.章.pdf/.test(n)), JSON.stringify(siteRows))

// 两段式删除
await click('.site-del')
const armedText = await ev(`document.querySelector('.site-del').textContent`)
check('11 删除要先点一次确认', /确认/.test(armedText), armedText)
await click('.site-del')
await sleep(900)
const afterDel = await ev(`[...document.querySelectorAll('.site-row .site-name')].map(n => n.textContent)`)
check('12 确认后站内文件被删除', afterDel.length === 1, JSON.stringify(afterDel))

// 「不知道怎么整理？」弹层
await click('.help-link')
await sleep(600)
const help = await ev(`(() => {
  const card = document.querySelector('.help-card')
  const body = document.querySelector('.help-body')
  const acts = [...document.querySelectorAll('.help-actions button')]
  return JSON.stringify({
    open: !!card,
    role: card ? card.getAttribute('role') : null,
    text: body ? body.textContent : '',
    buttons: acts.map(b => b.textContent.trim()),
    focused: document.activeElement ? document.activeElement.textContent.trim() : null
  })
})()`)
const h = JSON.parse(help)
check('13 点「不知道怎么整理？」→ 弹出规范卡片（结构 + 200MB 都写明）',
  h.open && h.role === 'dialog' && h.text.includes('<学科分类>/<子目录>/<文件>') && h.text.includes('200MB'),
  JSON.stringify({ open: h.open, role: h.role }))
check('14 卡片写明白名单与个人信息规则，且不出现 mp4',
  h.text.includes('pdf、doc、docx') && h.text.includes('姓名、学号') && !h.text.includes('mp4'),
  JSON.stringify(h.text.slice(0, 60)))
check('15 底部两颗按钮 =「我知道了」「复制 prompt」，打开时焦点在复制按钮上',
  h.buttons.join('|') === '我知道了|复制 prompt' && h.focused === '复制 prompt', JSON.stringify(h))

await click('.help-actions .btn-upload:not(.ghost)')
await sleep(500)
const afterCopy = await ev(`JSON.stringify({
  label: (document.querySelector('.help-actions .btn-upload:not(.ghost)') || {}).textContent,
  warn: !!document.querySelector('.help-warn')
})`)
const c2 = JSON.parse(afterCopy)
check('16 点「复制 prompt」→ 要么按钮变成已复制，要么给出手动复制提示',
  /已复制到剪贴板/.test(c2.label || '') || c2.warn === true, afterCopy)

const shotHelp = await send('Page.captureScreenshot', { format: 'png' })
writeFileSync(new URL('./help-page.png', import.meta.url).pathname.slice(1), Buffer.from(shotHelp.data, 'base64'))

await click('.help-actions .btn-upload.ghost')
await sleep(500)
check('17 点「我知道了」→ 弹层关闭', (await ev(`!document.querySelector('.help-card')`)) === true,
  await ev(`!!document.querySelector('.help-card')`))

// 课程栏三个盒子（输入框 / 从文件夹导入 / 不知道怎么整理）：几何和样式都要一致
await ev(`document.querySelector('.batch-course-bar').scrollIntoView({ block: 'center' })`)
await sleep(300)
const boxes = await ev(`(() => {
  const pick = (s) => { const n = document.querySelector(s); if (!n) return null
    const r = n.getBoundingClientRect(); const c = getComputedStyle(n)
    return { h: Math.round(r.height), top: Math.round(r.top), radius: c.borderTopLeftRadius,
      border: c.borderTopWidth + ' ' + c.borderTopStyle + ' ' + c.borderTopColor, bg: c.backgroundColor,
      font: c.fontSize, weight: c.fontWeight, color: c.color } }
  return JSON.stringify({ input: pick('.course-input'), browse: pick('.browse'), help: pick('.help-link') })
})()`)
const bx = JSON.parse(boxes)
const same = (k) => bx.input && bx.browse && bx.help && bx.input[k] === bx.browse[k] && bx.browse[k] === bx.help[k]
check('18 课程栏三个盒子同高、同基线、同圆角、同描边、同底色、同字号字重字色',
  same('h') && same('top') && same('radius') && same('border') && same('bg') &&
    same('font') && same('weight') && same('color'),
  JSON.stringify(bx) + ' → 高 ' + [bx.input.h, bx.browse.h, bx.help.h].join('/') +
  ' / 圆角 ' + [bx.input.radius, bx.browse.radius, bx.help.radius].join('/'))

const shot = await send('Page.captureScreenshot', { format: 'png' })
writeFileSync(new URL('./batch-page.png', import.meta.url).pathname.slice(1), Buffer.from(shot.data, 'base64'))
console.log(failed ? '\n未通过 ' + failed + ' 项' : '\n全部通过 ✅')
ws.close()
process.exit(failed ? 1 : 0)
