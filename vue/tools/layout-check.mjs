/* 布局骨架回归检查（需要 mock 后端 + Vite + 开了调试端口的 Chrome）。
 *
 *   node tools/mock-upcshare-api.cjs &
 *   npm run dev &
 *   chrome --headless=new --remote-debugging-port=9447 \
 *     --user-data-dir=%TEMP%/upc-chrome http://127.0.0.1:5173/ &
 *   node tools/layout-check.mjs
 *
 * 检查：滚动条预留后跨页导航 x 稳定、Dashboard 与导航同宽同 x、
 * 长文件名/上传者不改变表格列宽和操作列位置、操作按钮不被裁切、
 * 768px 表格卡片化、390px 上传页单列与首页/后台工具栏不挤压。
 */
const CDP = 'http://127.0.0.1:9447'
const BASE = 'http://127.0.0.1:5173'
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
const rect = (sel) => ev(`(() => {
  const el = document.querySelector(${JSON.stringify(sel)})
  if (!el) return null
  const b = el.getBoundingClientRect()
  return { x: +b.x.toFixed(1), w: +b.width.toFixed(1) }
})()`)
const same = (a, b, tol = 0.6) => a && b && Math.abs(a.x - b.x) <= tol && Math.abs(a.w - b.w) <= tol

let failed = 0
const check = (name, ok, got) => {
  console.log((ok ? '  ok  ' : '  FAIL ') + name + (ok ? '' : '  ->  ' + got))
  if (!ok) failed++
}

await send('Runtime.enable')
await send('Page.enable')
await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 720, deviceScaleFactor: 1, mobile: false })

async function go(path, waitSel) {
  await send('Page.navigate', { url: BASE + path })
  for (let i = 0; i < 30; i++) {
    await sleep(200)
    if (await ev(`!!document.querySelector(${JSON.stringify(waitSel)})`)) return true
  }
  return false
}

// 1) 跨页导航 x 稳定：/courses 有滚动条，/ 与 /admin 没有
const shells = []
for (const [path, sel] of [['/courses', '.nav-container'], ['/', '.nav-container'], ['/admin', '.nav-container'], ['/dashboard', '.dash-section']]) {
  if (!(await go(path, sel))) throw new Error('页面未加载：' + path)
  const nav = await rect('.nav-container')
  const content = await rect(sel === '.dash-section' ? '.dash-section' : '.container')
  shells.push({ path, nav, content })
}
const navXs = shells.map((s) => s.nav.x)
check('跨页导航 x 稳定（滚动条不推页面）', Math.max(...navXs) - Math.min(...navXs) <= 0.6, JSON.stringify(shells))
const dash = shells.find((s) => s.path === '/dashboard')
check('Dashboard 与导航同 x 同宽', same(dash.content, dash.nav), JSON.stringify(dash))
const gutter = await ev(`getComputedStyle(document.documentElement).getPropertyValue('scrollbar-gutter').trim()`)
check('滚动条槽位稳定（scrollbar-gutter: stable）', gutter === 'stable', gutter)

// 2) 表格长文本不改变列宽与操作列
if (!(await go('/dashboard', '.file-table tbody tr'))) throw new Error('dashboard 表格未渲染')
const tableBefore = await ev(`(() => {
  const th = [...document.querySelectorAll('.file-table thead th')].map((e) => {
    const b = e.getBoundingClientRect(); return { t: e.textContent.trim(), x: +b.x.toFixed(1), w: +b.width.toFixed(1) }
  })
  const a = document.querySelector('.file-table tbody tr .action-row')
  const ab = a ? a.getBoundingClientRect() : null
  return { th, action: ab ? { x: +ab.x.toFixed(1), w: +ab.width.toFixed(1) } : null,
    layout: getComputedStyle(document.querySelector('.file-table')).tableLayout }
})()`)
await ev(`(() => {
  const tr = document.querySelector('.file-table tbody tr')
  if (!tr) return false
  tr.children[1].textContent = '超长学科分类'.repeat(20)
  tr.children[2].textContent = '超长上传者用户名'.repeat(20)
  return true
})()`)
await sleep(300)
const tableAfter = await ev(`(() => {
  const th = [...document.querySelectorAll('.file-table thead th')].map((e) => {
    const b = e.getBoundingClientRect(); return { t: e.textContent.trim(), x: +b.x.toFixed(1), w: +b.width.toFixed(1) }
  })
  const a = document.querySelector('.file-table tbody tr .action-row')
  const ab = a ? a.getBoundingClientRect() : null
  return { th, action: ab ? { x: +ab.x.toFixed(1), w: +ab.width.toFixed(1) } : null,
    layout: getComputedStyle(document.querySelector('.file-table')).tableLayout }
})()`)
const thStable = tableBefore.th.length === tableAfter.th.length &&
  tableBefore.th.every((th, i) => same(th, tableAfter.th[i]))
check('长名字不改变表头列宽', thStable, JSON.stringify({ before: tableBefore.th, after: tableAfter.th }))
check('操作列不被长名字挤压', same(tableBefore.action, tableAfter.action), JSON.stringify({ before: tableBefore.action, after: tableAfter.action }))
check('文件表 table-layout: fixed', tableBefore.layout === 'fixed', tableBefore.layout)

// 容器位置不变不代表按钮没被裁：直接量最后一颗按钮的右边缘有没有越过单元格
const actionFit = JSON.parse(await ev(`(() => {
  const td = document.querySelector('.file-table tbody tr td:last-child')
  if (!td) return JSON.stringify({ ok: false, why: '没有操作列' })
  const box = td.getBoundingClientRect()
  const btns = [...td.querySelectorAll('.action-btn')].map((b) => b.getBoundingClientRect())
  return JSON.stringify({
    ok: true,
    tdRight: +box.right.toFixed(1),
    lastRight: btns.length ? +Math.max(...btns.map((b) => b.right)).toFixed(1) : 0,
    narrowest: btns.length ? +Math.min(...btns.map((b) => b.width)).toFixed(1) : 0
  })
})()`))
check('操作按钮完整落在列内（未被裁切）',
  actionFit.ok && actionFit.lastRight <= actionFit.tdRight + 0.6 && actionFit.narrowest >= 24,
  JSON.stringify(actionFit))

// 3) 窄屏：表格切成卡片，页面不产生横向溢出
await send('Emulation.setDeviceMetricsOverride', { width: 768, height: 900, deviceScaleFactor: 1, mobile: false })
if (!(await go('/dashboard', '.file-table tbody tr'))) throw new Error('窄屏 dashboard 表格未渲染')
await sleep(300)
const narrow = JSON.parse(await ev(`(() => {
  const table = document.querySelector('.file-table')
  return JSON.stringify({
    overflow: document.documentElement.scrollWidth - innerWidth,
    display: table ? getComputedStyle(table).display : '',
    rows: document.querySelectorAll('.file-table tbody tr').length
  })
})()`))
check('768px 下表格卡片化且无横向溢出',
  narrow.overflow <= 1 && narrow.display === 'block' && narrow.rows > 0,
  JSON.stringify(narrow))

// 4) 竖屏：上传页必须单列，不能把三栏挤成竖排文字；首页标题与视图切换同一行
await send('Emulation.setDeviceMetricsOverride', {
  width: 390, height: 844, deviceScaleFactor: 2, mobile: true, screenWidth: 390, screenHeight: 844
})
if (!(await go('/admin', '.batch-main'))) throw new Error('竖屏上传页未渲染')
await sleep(800)
const mobileBatch = JSON.parse(await ev(`(() => {
  const box = (s) => { const n = document.querySelector(s); if (!n) return null; const b = n.getBoundingClientRect(); return { w: Math.round(b.width), h: Math.round(b.height) } }
  const btn = document.querySelector('.queue-right .btn-upload')
  const bb = btn ? btn.getBoundingClientRect() : null
  return JSON.stringify({
    overflow: document.documentElement.scrollWidth - innerWidth,
    flex: getComputedStyle(document.querySelector('.batch-main')).flexDirection,
    rail: box('.dir-rail'), work: box('.work'), input: box('.course-input'), browse: box('.browse'),
    upload: bb ? { w: Math.round(bb.width), h: Math.round(bb.height) } : null
  })
})()`))
check('390px 上传页单列铺满（子目录/拖放区同宽，输入框与上传键不缩成竖排）',
  mobileBatch.overflow <= 1 && mobileBatch.flex === 'column' &&
    Math.abs(mobileBatch.rail.w - mobileBatch.work.w) <= 1 &&
    mobileBatch.input.w > 240 && mobileBatch.browse.w > 240 &&
    mobileBatch.upload && mobileBatch.upload.w > 240 && mobileBatch.upload.h >= 34,
  JSON.stringify(mobileBatch))

await ev(`localStorage.removeItem('upcshare-subject-view')`)
if (!(await go('/', '.view-toggle'))) throw new Error('竖屏首页未渲染')
await sleep(400)
const mobileHome = JSON.parse(await ev(`(() => {
  const title = document.querySelector('.section-title')
  const toggle = document.querySelector('.view-toggle')
  const grid = document.querySelector('.subjects-grid')
  const tb = title.getBoundingClientRect(), vb = toggle.getBoundingClientRect()
  const card = document.querySelector('.subject-card')
  const cb = card ? card.getBoundingClientRect() : null
  const gb = grid ? grid.getBoundingClientRect() : null
  return JSON.stringify({
    overflow: document.documentElement.scrollWidth - innerWidth,
    sameRow: Math.abs((tb.top + tb.height / 2) - (vb.top + vb.height / 2)) <= 4,
    cards: document.querySelectorAll('.subject-card').length,
    cardW: cb ? Math.round(cb.width) : 0, gridW: gb ? Math.round(gb.width) : 0
  })
})()`))
check('390px 首页标题与视图切换同一行，学科卡两列且无横向溢出',
  mobileHome.overflow <= 1 && mobileHome.sameRow && mobileHome.cards >= 1 &&
    (mobileHome.cards === 1
      ? mobileHome.cardW >= mobileHome.gridW - 2
      : mobileHome.cardW <= mobileHome.gridW * 0.6),
  JSON.stringify(mobileHome))

if (!(await go('/dashboard', '.dash-search-row .action-btn'))) throw new Error('竖屏 Dashboard 未渲染')
await sleep(500)
const mobileDash = JSON.parse(await ev(`(() => {
  const btn = document.querySelector('.dash-search-row .action-btn')
  const b = btn.getBoundingClientRect()
  return JSON.stringify({
    overflow: document.documentElement.scrollWidth - innerWidth,
    w: Math.round(b.width), h: Math.round(b.height), text: btn.textContent.trim()
  })
})()`))
check('390px 后台「刷新」保持完整按钮，搜索行不溢出',
  mobileDash.overflow <= 1 && mobileDash.w >= 44 && mobileDash.h <= 44 && mobileDash.text === '刷新',
  JSON.stringify(mobileDash))

// 文件卡：名字/路径/上传者+大小/状态+操作四行，不再每个字段一行
await ev(`(() => {
  const tab = [...document.querySelectorAll('.dash-tab')].find((n) => n.textContent.includes('全部文件'))
  if (tab) tab.click()
  return !!tab
})()`)
for (let i = 0; i < 30; i++) {
  await sleep(200)
  if (await ev(`!!document.querySelector('.file-table[data-view="files"] tbody tr')`)) break
}
await sleep(300)
const mobileFiles = JSON.parse(await ev(`(() => {
  const tr = document.querySelector('.file-table[data-view="files"] tbody tr')
  const r = tr ? tr.getBoundingClientRect() : null
  return JSON.stringify({
    overflow: document.documentElement.scrollWidth - innerWidth,
    rowH: r ? Math.round(r.height) : 0,
    display: tr ? getComputedStyle(tr).display : ''
  })
})()`))
check('390px 后台文件卡压缩为四行（上传者/大小、状态/操作并排）',
  mobileFiles.overflow <= 1 && mobileFiles.display === 'grid' && mobileFiles.rowH > 0 && mobileFiles.rowH <= 170,
  JSON.stringify(mobileFiles))

// 论坛：不再出现第二条汉堡栏，板块入口收进话题栏
if (!(await go('/forum', '.forum-section-btn'))) throw new Error('竖屏论坛未渲染')
await sleep(500)
const mobileForum = JSON.parse(await ev(`(() => {
  const btn = document.querySelector('.forum-section-btn')
  const h1 = document.querySelector('.forum-hero h1')
  const b = btn.getBoundingClientRect()
  return JSON.stringify({
    overflow: document.documentElement.scrollWidth - innerWidth,
    sectionVisible: b.width > 0 && getComputedStyle(btn).display !== 'none',
    oldHead: !!document.querySelector('.forum-mobile-head'),
    titleSize: h1 ? parseFloat(getComputedStyle(h1).fontSize) : 0
  })
})()`))
check('390px 论坛头部只有一条导航栏，板块入口在话题栏内',
  mobileForum.overflow <= 1 && mobileForum.sectionVisible && !mobileForum.oldHead && mobileForum.titleSize <= 28,
  JSON.stringify(mobileForum))

console.log(failed ? '\n未通过 ' + failed + ' 项' : '\n全部通过 ✅')
ws.close()
process.exit(failed ? 1 : 0)
