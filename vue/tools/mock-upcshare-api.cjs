/* 本地开发用的 upcshare 接口替身（不参与构建，只在 node 里跑）。
 *
 *   终端 A： node tools/mock-upcshare-api.cjs   # 监听 8080，正好是 vite.config.js 里 /api 的代理目标
 *   终端 B： npm run dev                        # 5173
 *   终端 C： node tools/ui-check.mjs            # 用真实鼠标把批量上传页点一遍
 *
 * 它只在内存里存几条资料，并记录每一次请求，方便断言这类规则：
 * 「空目录不会发上传请求」「全站待审核不会混进当前子目录的已有资源」。
 * 端口 8080 撞了你本地的服务就改这一行，同时改 ui-check 里对应的地址。
 */
const http = require('http')

const state = {
  files: [
    { id: 'a1', original_name: '2024 真题.pdf', file_size: '2.3 MB', file_size_raw: 2411000, category: '高等数学2-2', sub_category: '期末试卷', status: 'approved', file_path: '高等数学2-2/期末试卷/2024 真题.pdf' },
    { id: 'a2', original_name: '第1章.pptx', file_size: '8.1 MB', file_size_raw: 8493000, category: '高等数学2-2', sub_category: '课件', status: 'approved', file_path: '高等数学2-2/课件/第1章.pptx' },
    // 别的学科分类的待审核文件：出现在全站 pending 列表里，但不该出现在本目录的「已有资源」
    { id: 'p9', original_name: '材料力学基本概念.pdf', file_size: '1.0 MB', file_size_raw: 1048576, category: '材料力学', sub_category: '基本概念', status: 'pending', file_path: '材料力学/基本概念/材料力学基本概念.pdf' }
  ],
  categories: ['高等数学2-2', '大学物理', 'C++', '材料力学', '测控专业认识实习'],
  log: []
}

const ADMIN = true

function json(res, code, data) {
  const body = JSON.stringify(data)
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(body)
}

function multipartFields(buf) {
  const text = buf.toString('binary')
  const boundary = text.slice(0, text.indexOf('\r\n'))
  const out = {}
  for (const chunk of text.split(boundary)) {
    const headerEnd = chunk.indexOf('\r\n\r\n')
    if (headerEnd < 0) continue
    const headers = chunk.slice(0, headerEnd)
    const nameM = /name="([^"]*)"/.exec(headers)
    if (!nameM) continue
    const fileM = /filename="([^"]*)"/.exec(headers)
    if (fileM) { out.__name = Buffer.from(fileM[1], "binary").toString("utf8"); continue }
    let value = chunk.slice(headerEnd + 4)
    if (value.endsWith('\r\n')) value = value.slice(0, -2)
    out[nameM[1]] = Buffer.from(value, 'binary').toString('utf8')
  }
  return out
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x')
  const path = url.pathname
  state.log.push(req.method + ' ' + req.url)

  if (path === '/__log' || path === '/api/__log') return json(res, 200, state.log)
  if (path === '/__reset' || path === '/api/__reset') {
    state.log.length = 0
    state.files = state.files.filter((f) => !String(f.id).startsWith('u'))
    return json(res, 200, { ok: true })
  }
  if (path === '/api/auth/me') {
    return json(res, 200, { logged_in: true, uid: '000043', username: 'tester', is_admin: ADMIN, user_level: 'admin' })
  }
  if (path === '/api/categories') return json(res, 200, state.categories)
  if (path === '/api/subcategories') {
    // 站点行为：忽略 category，返回全站目录名
    return json(res, 200, [...new Set(state.files.map((f) => f.sub_category))])
  }
  if (path === '/api/files') {
    const cat = url.searchParams.get('category')
    const sub = url.searchParams.get('sub_category')
    const page = Number(url.searchParams.get('page') || 1)
    const size = Number(url.searchParams.get('size') || 20)
    let items = state.files.filter((f) => f.status === 'approved')
    if (cat !== null) items = items.filter((f) => f.category === cat)
    if (sub !== null) items = items.filter((f) => f.sub_category === sub)
    return json(res, 200, { total: items.length, page, size, pages: Math.ceil(items.length / size) || 1, items })
  }
  if (path === '/api/admin/files') {
    const status = url.searchParams.get('status')
    const size = Number(url.searchParams.get('size') || 20)
    const items = status ? state.files.filter((f) => f.status === status) : state.files
    return json(res, 200, { total: items.length, page: 1, size, pages: 1, items })
  }
  if (path === '/api/upload' && req.method === 'POST') {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const f = multipartFields(Buffer.concat(chunks))
      const id = 'u' + (state.files.length + 1)
      state.files.push({
        id, original_name: f.__name || 'file', file_size: '1.0 KB', file_size_raw: 1024,
        category: f.category || '', sub_category: f.sub_category || '',
        status: 'pending', file_path: (f.category || '') + '/' + (f.sub_category || '') + '/' + (f.__name || 'file')
      })
      json(res, 200, { message: '上传成功，等待审核', id, filename: f.__name || 'file', points: 1 })
    })
    return undefined
  }
  const approve = path.match(/^\/api\/admin\/approve\/(.+)$/)
  if (approve && req.method === 'POST') {
    const item = state.files.find((f) => f.id === decodeURIComponent(approve[1]))
    if (item) item.status = 'approved'
    return json(res, 200, { ok: true, msg: 'File approved' })
  }
  const del = path.match(/^\/api\/admin\/files\/(.+)$/)
  if (del && req.method === 'DELETE') {
    const id = decodeURIComponent(del[1])
    state.files = state.files.filter((f) => f.id !== id)
    return json(res, 200, { ok: true, msg: 'File deleted' })
  }
  return json(res, 404, { ok: false, msg: 'not found' })
})

// multipart 解析只取字段，文件名从 Content-Disposition 里单独拿
server.on('request', () => {})

server.listen(8080, () => console.log('mock upcshare api on http://127.0.0.1:8080'))
