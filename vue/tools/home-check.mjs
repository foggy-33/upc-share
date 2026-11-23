/* 首页搜索与视图切换回归（需要 mock 后端 + Vite + 开了调试端口的 Chrome）。
 *
 *   node tools/mock-upcshare-api.cjs &
 *   npm run dev &
 *   chrome --headless=new --remote-debugging-port=9447 \
 *     --user-data-dir=%TEMP%/upc-chrome http://127.0.0.1:5173/ &
 *   node tools/home-check.mjs
 *
 * 检查：宫格/列表切换并记忆、全局搜索返回学科卡片、
 * 进入学科后仍可按文件名搜索、表格截断列带完整 title。
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
const setInput = (sel, value) => ev(`(() => {
  const el = document.querySelector(${JSON.stringify(sel)})
  if (!el) return false
  el.value = ${JSON.stringify(value)}
  el.dispatchEvent(new Event('input', { bubbles: true }))
  return true
})()`)

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

if (!(await go('/', '.subjects-grid'))) throw new Error('首页未加载')
await ev(`fetch('/api/__reset').then(r => r.ok).catch(() => false)`)
await ev(`localStorage.removeItem('upcshare-subject-view')`)
if (!(await go('/', '.subjects-grid'))) throw new Error('首页未重载')
await sleep(500)

const defaultGrid = JSON.parse(await ev(`JSON.stringify({
  list: !!document.querySelector('.subjects-grid.list'),
  tags: [...document.querySelectorAll('.subject-card-tag')].map((n) => n.textContent.trim().toUpperCase())
})`))
check('默认宫格，文件后缀标签正常显示',
  defaultGrid.list === false && defaultGrid.tags.length > 0 && !defaultGrid.tags.some((t) => /UNDEFINED/.test(t)),
  JSON.stringify(defaultGrid))
await ev(`document.querySelector('.view-toggle').click()`)
await sleep(60)
const hasFlip = await ev(`[...document.querySelectorAll('.subject-card')].some((card) =>
  [...card.getAnimations()].some((a) =>
    a.constructor.name === 'Animation' && (a.effect?.getKeyframes?.() || []).length > 0))`)
check('切换有 FLIP 平滑动画', hasFlip, String(hasFlip))
await sleep(400)
check('切到列表并记住', await ev(`!!document.querySelector('.subjects-grid.list') && localStorage.getItem('upcshare-subject-view') === 'list'`))
const arrow = JSON.parse(await ev(`(() => {
  const card = document.querySelector('.subjects-grid.list .subject-card')
  const arrow = card?.querySelector('.subject-arrow')
  const tags = card?.querySelector('.subject-card-tags')
  if (!card || !arrow || !tags) return JSON.stringify(null)
  const cb = card.getBoundingClientRect(), ab = arrow.getBoundingClientRect(), tb = tags.getBoundingClientRect()
  return JSON.stringify({ gap: +(cb.right - ab.right).toFixed(1), afterTags: ab.left >= tb.right - 1 })
})()`))
check('列表箭头固定在卡片最右侧', arrow && arrow.gap < 30 && arrow.afterTags, JSON.stringify(arrow))
if (!(await go('/', '.subjects-grid'))) throw new Error('首页未重载')
await sleep(400)
check('刷新后仍是列表', await ev(`!!document.querySelector('.subjects-grid.list')`))
await ev(`document.querySelector('.view-toggle').click()`)
await sleep(300)

await setInput('.subject-search input', '高数')
await sleep(500)
const search = await ev(`JSON.stringify({
  title: document.querySelector('.section-title')?.textContent.replace(/\\s+/g, ' ').trim(),
  titleLink: document.querySelector('.section-title .title-link')?.textContent.trim(),
  cards: [...document.querySelectorAll('.subject-card-name')].map((n) => n.textContent.trim()),
  table: !!document.querySelector('.file-table')
})`)
const searchData = JSON.parse(search)
check('全局搜索返回学科卡片而不是文件表',
  searchData.title.includes('搜索结果') && searchData.titleLink === '全部学科' &&
    searchData.cards.includes('高等数学2-2') && !searchData.table, search)

await setInput('.subject-search input', 'zzz-没有这个学科')
await sleep(500)
check('无匹配学科时显示空状态', /没有匹配的学科分类/.test(await ev(`document.querySelector('.empty-state')?.textContent || ''`)))

await setInput('.subject-search input', '高等')
await sleep(500)
await ev(`(() => {
  const card = [...document.querySelectorAll('.subject-card')].find((n) => n.textContent.includes('高等数学2-2'))
  if (card) card.click()
  return !!card
})()`)
for (let i = 0; i < 30; i++) {
  await sleep(200)
  if (await ev(`!!document.querySelector('.file-table')`)) break
}
await sleep(500)
const fileView = await ev(`JSON.stringify({
  title: document.querySelector('.section-title')?.textContent.replace(/\\s+/g, ' ').trim(),
  titleLink: document.querySelector('.section-title .title-link')?.textContent.trim(),
  breadcrumb: !!document.querySelector('.breadcrumb')
})`)
const fv = JSON.parse(fileView)
check('点击学科进入文件视图（标题合并面包屑）',
  fv.title.includes('高等数学2-2') && fv.titleLink === '全部学科' && !fv.breadcrumb, fileView)

await setInput('.subject-search input', '1')
await sleep(900)
const rows = await ev(`JSON.stringify([...document.querySelectorAll('.file-table tbody tr')].map((tr) => tr.innerText.replace(/\\s+/g, ' ').trim()))`)
const rowData = JSON.parse(rows)
check('学科内仍可按文件名搜索', rowData.length === 1 && /第1章/.test(rowData[0]), rows)

await ev(`document.querySelector('.file-actions .danger')?.click()`)
await sleep(200)
const armedText = await ev(`document.querySelector('.file-actions .danger')?.textContent.trim() || ''`)
check('管理员删除需二次确认', armedText === '确认删除', armedText)
await ev(`document.querySelector('.file-actions .danger')?.click()`)
await sleep(900)
await setInput('.subject-search input', '')
await sleep(900)
const afterDelete = await ev(`(() => {
  const table = document.querySelector('.file-table')
  return JSON.stringify({ hasTable: !!table, text: table ? table.innerText : '' })
})()`)
const ad = JSON.parse(afterDelete)
check('二次确认后文件被删除，同目录其他文件仍在',
  ad.hasTable && !/第1章/.test(ad.text) && /2024 真题/.test(ad.text), afterDelete)

if (!(await go('/dashboard', '.dash-tab'))) throw new Error('Dashboard 未加载')
await ev(`(() => {
  const tab = [...document.querySelectorAll('.dash-tab')].find((n) => n.textContent.includes('全部文件'))
  if (tab) tab.click()
  return !!tab
})()`)
for (let i = 0; i < 30; i++) {
  await sleep(200)
  if (await ev(`!!document.querySelector('.file-name-text')`)) break
}
await sleep(500)
const titles = await ev(`(() => {
  const tr = document.querySelector('.file-table tbody tr')
  return JSON.stringify({
    name: tr?.querySelector('.file-name-text')?.title || '',
    category: tr?.children[1]?.title || '',
    uploader: tr?.children[2]?.title || ''
  })
})()`)
const t = JSON.parse(titles)
check('Dashboard 截断列带完整 title', t.name && t.category.includes('/') && t.uploader, titles)

// 从带 ?q= 的链接进来再清空搜索：URL 也要跟着清掉，否则刷新会回到旧搜索
if (!(await go('/?q=高等', '.title-link'))) throw new Error('带搜索参数的首页未加载')
await sleep(500)
const beforeClear = await ev(`document.querySelector('.subject-search input')?.value || ''`)
await ev(`document.querySelector('.section-title .title-link')?.click()`)
await sleep(700)
const afterClear = JSON.parse(await ev(`JSON.stringify({
  search: location.search,
  value: document.querySelector('.subject-search input')?.value ?? null
})`))
check('清空搜索后 URL 不再保留 q 参数',
  beforeClear === '高等' && !/[?&]q=/.test(afterClear.search) && afterClear.value === '',
  JSON.stringify({ beforeClear, afterClear }))

console.log(failed ? '\n未通过 ' + failed + ' 项' : '\n全部通过 ✅')
ws.close()
process.exit(failed ? 1 : 0)
