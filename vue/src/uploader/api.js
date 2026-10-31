/* 批量投递的接口层。
 *
 * 约定：
 *  - 一律走站点自己的 /api/**，同源 + Cookie，不新增任何后端接口；
 *  - JSON 请求复用站点已有的 api()（src/api/http.js），错误对象上带 status；
 *  - 只有上传例外——fetch 拿不到上传进度，必须用 XMLHttpRequest 的 upload.onprogress。
 *
 * 实测出来的三条硬事实都写在这里，别当 bug 删掉：
 *  1. /api/subcategories 忽略 category 参数，所以「某学科分类用过哪些子目录」只能按文件聚合；
 *  2. /api/files 的 size 上限是 100，/api/admin/files 是 200；
 *  3. /api/admin/files?status=pending 是全站范围，必须自己按学科分类 + 子目录过滤。
 */
import { api } from '../api/http.js'
import { dirsFromItems, nameKey, normalizeText, dedupeKey, scopeKey } from './logic.js'

const PAGE = { files: 100, admin: 200, maxPages: 40 }

/** GET/DELETE/POST 都走站点的 api()；approve 没有请求体，所以显式清空 Content-Type */
function postEmpty(path) {
  return api(path, { method: 'POST', headers: {} })
}

/** 当前登录者（未登录也返回 200，靠 logged_in 字段判断） */
export function loadMe() {
  return api('/api/auth/me')
}

/** 站内全部学科分类：文件夹名 → 正式名的匹配依据 */
export function loadCategories() {
  return api('/api/categories')
}

/**
 * 站内已有资料索引：已通过的 + 待审核的（后者只有管理员读得到）。
 * onWarn 用来把"待审核没读到"这件事告诉调用方——静默掉会导致重复上传。
 */
export async function loadSiteIndex({ onWarn } = {}) {
  const byKey = {}
  const byName = {}
  const subsByCategory = {}
  const stats = { approved: 0, pending: 0, pages: 0 }

  const collect = (items) => {
    for (const item of items || []) {
      const name = String(item.original_name || '')
      const size = Number(item.file_size_raw != null ? item.file_size_raw : item.file_size) || 0
      const scope = scopeKey(item.category, item.sub_category)
      byKey[dedupeKey(name, size, scope)] = true
      byName[nameKey(name, scope)] = true
      const cat = String(item.category || '').trim()
      const sub = String(item.sub_category || '').trim()
      if (cat && sub) {
        const key = normalizeText(cat)
        const list = subsByCategory[key] || (subsByCategory[key] = [])
        if (!list.includes(sub)) list.push(sub)
      }
    }
  }

  const walk = async (pathFor, maxPages) => {
    for (let page = 1; page <= maxPages; page++) {
      const data = await api(pathFor(page))
      if (!data || !data.items) break
      collect(data.items)
      stats.pages++
      if (page >= (data.pages || 0)) break
    }
  }

  await walk((p) => `/api/files?size=${PAGE.files}&page=${p}`, PAGE.maxPages)
  stats.approved = Object.keys(byKey).length

  try {
    const data = await api(`/api/admin/files?status=pending&size=${PAGE.admin}&page=1`)
    if (data && data.items) {
      collect(data.items)
      stats.pending = data.items.length
      if ((data.pages || 0) > 1) {
        await walk((p) => `/api/admin/files?status=pending&size=${PAGE.admin}&page=${p}`, 10)
      }
    }
  } catch (err) {
    // 普通成员没有 admin 权限是常态，静默；其余错误要说，否则待审核文件会被重复上传
    if (err.status !== 401 && err.status !== 403 && onWarn) {
      onWarn(`待审核列表没读到：${err.message}（查重可能漏掉未审核的文件）`)
    }
  }

  return { byKey, byName, subsByCategory, stats }
}

/**
 * 某个学科分类在站内真实用过的子目录。
 * 站点没有「按学科分类取子目录」的接口，只能按 category 拉文件再自己聚合，
 * 顺带拿到每个子目录的文件数。
 */
export async function loadCourseDirs(category) {
  const items = []
  const q = encodeURIComponent(category)
  for (let page = 1; page <= PAGE.maxPages; page++) {
    const data = await api(`/api/files?category=${q}&size=${PAGE.files}&page=${page}`)
    if (!data || !data.items) break
    items.push(...data.items)
    if (page >= (data.pages || 1)) break
  }
  return dirsFromItems(items)
}

/**
 * 「已有资源」：当前学科分类 + 当前子目录下的站内文件（已通过 + 待审核）。
 * 空串是有效的 sub_category 筛选值——直接挂在学科分类下的文件就是这么存的。
 */
export async function loadSiteFiles(category, subCategory, { onWarn } = {}) {
  const base = `/api/files?category=${encodeURIComponent(category)}` +
    `&sub_category=${encodeURIComponent(subCategory || '')}&size=${PAGE.files}&page=`
  const items = []
  for (let page = 1; page <= 20; page++) {
    const data = await api(base + page)
    if (!data || !data.items) break
    items.push(...data.items)
    if (page >= (data.pages || 1)) break
  }

  let pending = []
  try {
    const data = await api(`/api/admin/files?status=pending&size=${PAGE.admin}&page=1`)
    // 待审核那份是全站范围的，不筛就会把别的学科分类的文件搬进当前子目录的列表里，
    // 管理员一伸手删掉的是别人的文件。
    const cat = normalizeText(category)
    const sub = normalizeText(subCategory)
    pending = ((data && data.items) || []).filter((item) =>
      item && normalizeText(item.category) === cat && normalizeText(item.sub_category) === sub)
  } catch (err) {
    // 只有"没权限"可以静默（普通成员本来就没有这块）；5xx / 断网必须说出来，
    // 否则用户看到的是"站内一个文件都没有"，接着就会重复上传
    if (err.status !== 401 && err.status !== 403 && onWarn) {
      onWarn(`待审核列表没取到：${err.message}`)
    }
  }

  const seen = new Set()
  return [...items, ...pending].filter((item) => {
    if (!item || seen.has(item.id)) return false
    seen.add(item.id)
    return true
  })
}

export function deleteSiteFile(id) {
  return api(`/api/admin/files/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function approveFile(id) {
  return postEmpty(`/api/admin/approve/${encodeURIComponent(id)}`)
}

/**
 * 上传单个文件。用 XHR 而不是 fetch：fetch 没有上传进度事件。
 * 浏览器会自动带上 multipart 边界，所以不要手动设置 Content-Type。
 *
 * @param {{file: File, name?: string, category: string, subCategory: string,
 *          onProgress?: Function, signal?: AbortSignal}} opts
 * @returns {Promise<{id: string, filename: string, message?: string}>}
 */
export function uploadFile(opts) {
  const { file, name, category, subCategory, onProgress, signal } = opts
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    let aborted = false

    const abort = () => {
      aborted = true
      try { xhr.abort() } catch (e) { /* 已经结束了 */ }
    }
    if (signal) {
      if (signal.aborted) { abort(); return }
      signal.addEventListener('abort', abort, { once: true })
    }

    xhr.open('POST', '/api/upload', true)
    xhr.responseType = 'text'
    xhr.withCredentials = true
    if (onProgress) {
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) onProgress(event.loaded, event.total)
      }
    }
    xhr.onload = () => {
      let data = null
      try { data = JSON.parse(xhr.responseText) } catch (e) { data = null }
      if (xhr.status >= 200 && xhr.status < 300 && data && data.id) {
        resolve(data)
        return
      }
      const detail = data && (data.detail || data.msg || data.message)
      const err = new Error(detail || `上传失败（HTTP ${xhr.status}）`)
      err.status = xhr.status
      reject(err)
    }
    xhr.onerror = () => {
      const err = new Error('网络中断，上传没有完成')
      err.status = 0
      reject(err)
    }
    xhr.onabort = () => {
      const err = new Error('已停止')
      err.aborted = aborted
      reject(err)
    }

    const form = new FormData()
    form.append('file', file, name || file.name)
    form.append('description', '')
    form.append('category', category)
    form.append('sub_category', subCategory || '')
    xhr.send(form)
  })
}
