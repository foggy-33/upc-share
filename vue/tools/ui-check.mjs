/* 批量上传页的无头交互验证（需要先起 mock 后端和一个开了调试端口的 Chrome）。
 *
 *   node tools/mock-upcshare-api.cjs &
 *   npm run dev &
 *   chrome --headless=new --remote-debugging-port=9447 \
 *     --user-data-dir=%TEMP%/upc-chrome http://127.0.0.1:5173/admin &
 *   node tools/ui-check.mjs
 *
 * 29 项断言：没选学科分类不给建子目录、默认名编号、站内已有子目录自动带出、
 * 上传请求次数、管理员自动通过审核、已有资源按学科分类+子目录过滤、两段式删除、
 * 「?」徽标（位置 / tooltip / 键盘可达）、规范弹层（内容 / 按钮 / 复制反馈 / 关闭）、
 * 课程栏两个盒子对齐、整个面板只有两档盒子与四档字号、拖入文件夹走导入识别、导出结果 CSV、根目录常驻与两段式垃圾桶、页面铺满与未登录提示。
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

// 页面铺满：标题区不能把三栏挤到屏幕下半截（PR 版收掉了站点落地页的 64/80 留白）。
// 只在这一步量：后面塞进子目录、打开「已有资源」之后内容本来就该撑出滚动条。
const layout = JSON.parse(await ev(`(() => {
  const main = document.querySelector('.batch-main')
  const q = document.querySelector('.queue')
  return JSON.stringify({
    mainTop: Math.round(main.getBoundingClientRect().top),
    bottomGap: Math.round(innerHeight - q.getBoundingClientRect().bottom),
    overflow: document.documentElement.scrollHeight - innerHeight,
    heading: Math.round(document.querySelector('.page-heading').getBoundingClientRect().height)
  })
})()`))
check('28 页面铺满：三栏顶部在 300px 以内、底部不留大片空白、整页不出纵向滚动',
  layout.mainTop <= 300 && layout.bottomGap <= 80 && layout.overflow <= 4, JSON.stringify(layout))

const loginHint = await ev(`!!document.querySelector('.batch-login')`)
check('29 已登录时不显示「请先登录」提示', loginHint === false, String(loginHint))

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

// 「?」徽标 → 规范弹层
// 先量徽标本身：贴在导入按钮右上角、默认收起 tooltip、键盘聚焦会浮出
const badge = JSON.parse(await ev(`(() => {
  const b = document.querySelector('.help-badge')
  const imp = document.querySelector('.browse')
  const tip = document.querySelector('.help-tip')
  if (!b || !imp || !tip) return JSON.stringify({ missing: true })
  const br = b.getBoundingClientRect(), ir = imp.getBoundingClientRect(), tc = getComputedStyle(tip)
  return JSON.stringify({
    text: b.textContent.trim(), label: b.getAttribute('aria-label'),
    anchored: br.top < ir.top && br.right > ir.right - 4 && br.left < ir.right,
    round: getComputedStyle(b).borderTopLeftRadius,
    tipText: tip.textContent.trim(), tipHiddenByDefault: tc.opacity === '0',
    focusable: b.tabIndex >= 0 && b.tagName === 'BUTTON'
  })
})()`))
check('13 「?」徽标压在导入按钮右上角，默认收起、文案是「查看文件夹导入规范」',
  badge.missing !== true && badge.text === '?' && badge.label === '查看文件夹导入规范' &&
    badge.anchored === true && badge.round === '50%' && badge.tipText === '查看文件夹导入规范' &&
    badge.tipHiddenByDefault === true && badge.focusable === true, JSON.stringify(badge))
// 用真的 Tab 键走焦：programmatic .focus() 不算 :focus-visible（Chrome 只在键盘发起的聚焦上加这个伪类），
// 所以直接 focus() 再读 tooltip 会假失败。
await ev(`document.querySelector('.browse').focus()`)
for (let i = 0; i < 3; i++) {
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 })
  await sleep(200)
  if (await ev(`document.activeElement === document.querySelector('.help-badge')`)) break
}
const tipShown = await ev(`(() => {
  const tip = document.querySelector('.help-tip')
  return JSON.stringify({ focused: document.activeElement === document.querySelector('.help-badge'),
    opacity: getComputedStyle(tip).opacity })
})()`)
const ts = JSON.parse(tipShown)
check('14 键盘 Tab 能走到「?」，聚焦时 tooltip 浮出（不只靠鼠标）',
  ts.focused === true && Number(ts.opacity) > 0.9, tipShown)
await click('.help-badge')
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
check('15 点「?」→ 弹出规范卡片（结构 + 200MB 都写明）',
  h.open && h.role === 'dialog' && h.text.includes('<学科分类>/<子目录>/<文件>') && h.text.includes('200MB'),
  JSON.stringify({ open: h.open, role: h.role }))
check('16 卡片写明白名单与个人信息规则，且不出现 mp4',
  h.text.includes('pdf、doc、docx') && h.text.includes('姓名、学号') && !h.text.includes('mp4'),
  JSON.stringify(h.text.slice(0, 60)))
check('17 底部两颗按钮 =「我知道了」「复制 prompt」，打开时焦点在复制按钮上',
  h.buttons.join('|') === '我知道了|复制 prompt' && h.focused === '复制 prompt', JSON.stringify(h))

await click('.help-actions .btn-upload:not(.ghost)')
await sleep(500)
const afterCopy = await ev(`JSON.stringify({
  label: (document.querySelector('.help-actions .btn-upload:not(.ghost)') || {}).textContent,
  warn: !!document.querySelector('.help-warn')
})`)
const c2 = JSON.parse(afterCopy)
check('18 点「复制 prompt」→ 要么按钮变成已复制，要么给出手动复制提示',
  /已复制到剪贴板/.test(c2.label || '') || c2.warn === true, afterCopy)

const shotHelp = await send('Page.captureScreenshot', { format: 'png' })
writeFileSync(new URL('./help-page.png', import.meta.url).pathname.slice(1), Buffer.from(shotHelp.data, 'base64'))

await click('.help-actions .btn-upload.ghost')
await sleep(500)
check('19 点「我知道了」→ 弹层关闭', (await ev(`!document.querySelector('.help-card')`)) === true,
  await ev(`!!document.querySelector('.help-card')`))

// 课程栏两个盒子（学科分类输入框 / 从文件夹导入）：几何和样式都要一致
await ev(`document.querySelector('.batch-course-bar').scrollIntoView({ block: 'center' })`)
await sleep(300)
const boxes = await ev(`(() => {
  const pick = (s) => { const n = document.querySelector(s); if (!n) return null
    const r = n.getBoundingClientRect(); const c = getComputedStyle(n)
    return { h: Math.round(r.height), top: Math.round(r.top), radius: c.borderTopLeftRadius,
      border: c.borderTopWidth + ' ' + c.borderTopStyle + ' ' + c.borderTopColor, bg: c.backgroundColor,
      font: c.fontSize, weight: c.fontWeight, color: c.color } }
  return JSON.stringify({ input: pick('.course-input'), browse: pick('.browse') })
})()`)
const bx = JSON.parse(boxes)
const same = (k) => bx.input && bx.browse && bx.input[k] === bx.browse[k]
check('20 课程栏两个盒子同高、同基线、同圆角、同描边、同底色、同字号字重字色',
  same('h') && same('top') && same('radius') && same('border') && same('bg') &&
    same('font') && same('weight') && same('color'),
  JSON.stringify(bx) + ' → 高 ' + [bx.input.h, bx.browse.h].join('/') +
  ' / 圆角 ' + [bx.input.radius, bx.browse.radius].join('/'))

// 整个面板只允许两档盒子：主操作 42px/15px·600、次级 32px/13px·500，圆角一律 8px
const tiers = JSON.parse(await ev(`(() => {
  const pick = (s) => { const n = document.querySelector(s); if (!n) return null
    const r = n.getBoundingClientRect(); const c = getComputedStyle(n)
    return { h: Math.round(r.height), radius: c.borderTopLeftRadius, font: c.fontSize, weight: c.fontWeight } }
  return JSON.stringify({
    primary: ['.course-input', '.browse', '.queue-right .btn-upload:last-of-type'].map(pick),
    secondary: ['.head-actions .btn-upload.small', '.work-head .btn-upload.small'].map(pick)
  })
})()`))
const tierOK = (list, h, font, weight) => list.every((x) => x && x.h === h &&
  x.radius === '8px' && x.font === font && x.weight === weight)
check('21 面板盒子只有两档：主操作 42/15·600、次级 32/13·500、圆角一律 8px',
  tierOK(tiers.primary, 42, '15px', '600') && tierOK(tiers.secondary, 32, '13px', '500'),
  JSON.stringify(tiers))

// 字号只允许四档：20（页面标题）/ 15（区块与主操作）/ 13（正文）/ 12（辅助）
const sizes = JSON.parse(await ev(`(() => {
  const found = {}
  document.querySelectorAll('.batch-workbench *, .batch-card *, .batch-course-bar *, .queue *, .help-card *').forEach((n) => {
    if (['STYLE', 'SCRIPT', 'TITLE'].includes(n.tagName)) return
    const own = [...n.childNodes].some((c) => c.nodeType === 3 && c.textContent.trim())
    if (!own) return
    const fs = getComputedStyle(n).fontSize
    found[fs] = (found[fs] || 0) + 1
  })
  return JSON.stringify(found)
})()`))
const ALLOWED = ['20px', '15px', '13px', '12px']
const strays = Object.keys(sizes).filter((k) => !ALLOWED.includes(k))
check('22 字号全部落在四档内（20/15/13/12），越界的会被列出来',
  // 只要求"不许出现第五种字号"：某一档在当前状态下没出现是正常的（没弹层时就没有 15px 标题）
  strays.length === 0 && sizes['13px'] > 0 && sizes['12px'] > 0,
  '实测 ' + JSON.stringify(sizes) + '；越界 ' + JSON.stringify(strays))

// 拖文件夹进来：必须走「从文件夹导入」那条识别链路，而不是把文件塞进当前子目录
const dropped = JSON.parse(await ev(`(async () => {
  const dir = (name, kids) => ({
    name, isDirectory: true, isFile: false,
    createReader: () => { let sent = false
      return { readEntries: (ok) => { if (sent) { ok([]); return } sent = true; ok(kids) } } }
  })
  const file = (name) => ({ name, isDirectory: false, isFile: true, file: (ok) => ok(new File(['x'], name)) })
  const tree = dir('大学物理', [
    dir('实验报告', [file('实验三 干涉.pdf'), file('实验四 衍射.pdf')]),
    dir('课件', [file('第 3 章.pptx')])
  ])
  const zone = document.querySelector('.drop')
  const dt = {
    items: [{ kind: 'file', webkitGetAsEntry: () => tree }],
    files: []
  }
  const e = new Event('drop', { bubbles: true, cancelable: true })
  e.dataTransfer = dt
  zone.dispatchEvent(e)
  await new Promise((r) => setTimeout(r, 900))
  const card = document.querySelector('.import-confirm, .chooser, .batch-mask')
  const text = card ? card.textContent : ''
  return JSON.stringify({
    asked: /3/.test(text) && /个文件/.test(text),
    dirs: /个子目录/.test(text),
    stillStaged: document.querySelectorAll('.file-row').length
  })
})()`))
check('23 拖入三层文件夹 → 展开成 3 个文件并弹导入确认（不是塞进当前子目录）',
  dropped.asked === true && dropped.dirs === true, JSON.stringify(dropped))
await ev(`(() => {
  const btn = [...document.querySelectorAll('button')].find((b) => /取消/.test(b.textContent))
  if (btn) btn.click()
  return true
})()`)
await sleep(400)

// 导出结果：拦下 createObjectURL 与 a.click，读回 blob 内容核对
const exported = JSON.parse(await ev(`(async () => {
  const before = []
  const origUrl = URL.createObjectURL
  const origClick = HTMLAnchorElement.prototype.click
  let captured = null
  URL.createObjectURL = function (blob) { captured = blob; return origUrl.call(URL, blob) }
  HTMLAnchorElement.prototype.click = function () { before.push(this.download) }
  document.querySelector('.queue-right .btn-upload.small').click()
  await new Promise((r) => setTimeout(r, 400))
  URL.createObjectURL = origUrl
  HTMLAnchorElement.prototype.click = origClick
  if (!captured) return JSON.stringify({ ok: false, why: '没拿到 Blob' })
  // BOM 要按字节看：Blob.text() 用 TextDecoder 解码时会吃掉开头的 BOM，拿它量会得到假失败
  const bytes = new Uint8Array(await captured.arrayBuffer())
  const text = new TextDecoder('utf-8', { ignoreBOM: true }).decode(bytes)
  return JSON.stringify({
    ok: true,
    name: before[0] || '',
    bom: [bytes[0], bytes[1], bytes[2]].join('-'),
    header: text.slice(1).split('\\r\\n')[0],
    rows: text.trim().split('\\r\\n').length - 1
  })
})()`))
check('24 「导出结果」导出的 CSV 带 BOM、表头正确、行数对得上',
  exported.ok === true && exported.bom === '239-187-191' &&
    exported.header === 'relPath,category,sub_category,status,id,error' &&
    /^upcshare-投递结果-.*\.csv$/.test(exported.name) && exported.rows >= 2,
  JSON.stringify(exported))

// 根目录常驻 + 行右侧两段式垃圾桶（⋯ 菜单已撤掉）
const rail = JSON.parse(await ev(`(() => {
  const rows = [...document.querySelectorAll('.dir-row')]
  const info = (r) => {
    const del = r.querySelector('.dir-del')
    return { name: r.querySelector('.dir-name').textContent.trim(),
      hasSvg: del ? !!del.querySelector('svg') : false,
      disabled: del ? del.disabled : null, title: del ? del.title : '',
      bg: del ? getComputedStyle(del).backgroundColor : '' }
  }
  return JSON.stringify({ all: rows.map(info), count: rows.length })
})()`))
const first = rail.all[0] || {}
check('25 每个学科分类都固定有一行「根目录」排在最前，且它的垃圾桶禁用（根目录是分类本身）',
  first.name === '根目录' && first.hasSvg === true && first.disabled === true &&
    /根目录就是学科分类本身/.test(first.title), JSON.stringify(rail.all.slice(0, 3)))

const siteRow = rail.all.find((r) => r.name === '期末试卷')
check('26 站内已有的子目录：垃圾桶禁用并说明「站点没有目录这个实体」',
  !!siteRow && siteRow.disabled === true && /站点没有「目录」这个实体/.test(siteRow.title),
  JSON.stringify(siteRow))

const delFlow = JSON.parse(await ev(`(async () => {
  const rows = [...document.querySelectorAll('.dir-row')]
  // 挑一颗可用的（站内已有的行、根目录行都是禁用的）
  const target = rows.filter((r) => !r.querySelector('.dir-del').disabled).pop()
  const btn = target.querySelector('.dir-del')
  const before = rows.length
  const idle = { bg: getComputedStyle(btn).backgroundColor, armed: btn.classList.contains('armed') }
  btn.click()
  await new Promise((r) => setTimeout(r, 220))
  const mid = { count: document.querySelectorAll('.dir-row').length,
    armed: btn.classList.contains('armed'), bg: getComputedStyle(btn).backgroundColor, title: btn.title }
  btn.click()
  await new Promise((r) => setTimeout(r, 350))
  return JSON.stringify({ before, idle, mid, after: document.querySelectorAll('.dir-row').length })
})()`))
check('27 垃圾桶第一次点击只"武装"（摇一下 + 加深、不删行），第二次才真删',
  delFlow.mid.count === delFlow.before && delFlow.mid.armed === true &&
    delFlow.mid.bg !== delFlow.idle.bg && /再点一次/.test(delFlow.mid.title) &&
    delFlow.after === delFlow.before - 1, JSON.stringify(delFlow))

const shot = await send('Page.captureScreenshot', { format: 'png' })
writeFileSync(new URL('./batch-page.png', import.meta.url).pathname.slice(1), Buffer.from(shot.data, 'base64'))
console.log(failed ? '\n未通过 ' + failed + ' 项' : '\n全部通过 ✅')
ws.close()
process.exit(failed ? 1 : 0)
