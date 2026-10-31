/* 批量投递的纯逻辑：路径折算、站内比对、目标收集、限速。
 *
 * 这个文件不碰 DOM、不碰 fetch、不碰全局状态——所有函数都是"给输入、出结果"，
 * 所以能用 node --test 直接跑（见同目录 logic.test.mjs），不需要任何测试框架。
 * 改这里任何一条规则，请同时改测试。
 */

// 和 FileController.ALLOWED 保持一致
export const ALLOWED_EXT = ['.pdf', '.doc', '.docx', '.zip', '.rar', '.7z', '.tar', '.gz',
  '.ppt', '.pptx', '.xls', '.xlsx', '.txt', '.md', '.csv']
export const MAX_BYTES = 200 * 1024 * 1024   // 单文件上限；站点本身允许 1GB，这里按产品要求收紧
export const DIR_DEFAULT = '新子目录'         // 「新建子目录」没改名之前的占位名（会编号）
export const PACE = { burst: 3, intervalMs: 10500 }   // Nginx 6r/m + burst 3，留 5% 余量
export function extOf(name) {
  var s = String(name || '')
  var i = s.lastIndexOf('.')
  return i >= 0 ? s.slice(i).toLowerCase() : ''
}

export function isAllowedName(name) {
  return ALLOWED_EXT.indexOf(extOf(name)) >= 0
}

// 服务端会拒掉含 <>:"|?* 和控制字符、或带 .. 的文件名
export function hasBadName(name) {
  var s = String(name || '')
  if (!s) return true
  if (s.indexOf('..') >= 0) return true
  return /[<>:"|?*\x00-\x1f]/.test(s)
}

export function formatSize(bytes) {
  var n = Number(bytes) || 0
  var units = ['B', 'KB', 'MB', 'GB', 'TB']
  var i = 0
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return (i === 0 ? n.toFixed(0) : n.toFixed(1)) + ' ' + units[i]
}

// 匹配学科名时忽略空格、标点、全半角差异
export function normalizeText(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/[\s\u3000\u00b7\u30fb._\-—–()（）\[\]【】《》<>"'`、,，.。:：;；/\\+]/g, '')
}

export function matchScore(a, b) {
  var x = normalizeText(a)
  var y = normalizeText(b)
  if (!x || !y) return 0
  if (x === y) return 100
  if (y.indexOf(x) >= 0 || x.indexOf(y) >= 0) {
    var shortLen = Math.min(x.length, y.length)
    var longLen = Math.max(x.length, y.length)
    return 60 + (shortLen / longLen) * 30
  }
  var p = 0
  while (p < x.length && p < y.length && x[p] === y[p]) p++
  if (p >= 2) return 30 + (p / Math.max(x.length, y.length)) * 30
  return 0
}

export function bestCategory(name, categories) {
  var best = null
  var bestScore = 0
  var list = categories || []
  for (var i = 0; i < list.length; i++) {
    var s = matchScore(name, list[i])
    if (s > bestScore) { bestScore = s; best = list[i] }
  }
  return bestScore >= 60 ? { name: best, score: Math.round(bestScore) } : null
}

/**
 * 下拉候选的模糊排序：完全匹配 > 前缀 > 包含 > 字符子序列。
 * 中文用户常打简称（“高数”），所以子序列必须有兜底。
 */
export function fuzzyScore(raw, query) {
  var q = normalizeText(query)
  var v = normalizeText(raw)
  if (!q) return 0
  if (!v) return 0
  if (v === q) return 100
  if (v.indexOf(q) === 0) return 88 + 10 * (q.length / v.length)
  if (v.indexOf(q) > 0) return 72 + 8 * (q.length / v.length)
  var i = 0
  for (var j = 0; j < v.length && i < q.length; j++) {
    if (v[j] === q[i]) i++
  }
  return i === q.length ? 40 + 18 * (q.length / v.length) : 0
}

export function fuzzyFilter(list, query, limit) {
  var max = Number(limit) > 0 ? Number(limit) : 8
  var seen = {}
  var out = []
  var src = list || []
  for (var i = 0; i < src.length; i++) {
    var value = String(src[i] == null ? '' : src[i])
    var key = normalizeText(value)
    if (!key || seen[key]) continue
    seen[key] = true
    out.push({ value: value, score: fuzzyScore(value, query), idx: i })
  }
  if (normalizeText(query)) {
    out = out.filter(function (item) { return item.score > 0 })
    out.sort(function (a, b) {
      return b.score - a.score || a.idx - b.idx
    })
  }
  return out.slice(0, max).map(function (item) { return item.value })
}

/** 子目录候选：本学科已有子目录优先，再接全局子目录列表。 */
// 子目录候选：只用这门课在站内真实用过的子目录 + 本次已经建出来的子目录。
// 不掺全站子目录名 —— 别的课的「课设参考」对当前学科分类没有意义，用户反馈过下拉被污染。
export function dirOptions(subsOfCourse, courseDirs) {
  var out = []
  var seen = {}
  function add(value) {
    var name = String(value == null ? '' : value).trim()
    var key = normalizeText(name)
    if (!key || seen[key]) return
    seen[key] = true
    out.push(name)
  }
  ;(subsOfCourse || []).forEach(add)
  ;(courseDirs || []).forEach(function (dir) { add(dir && dir.name) })
  return out
}

// 站内文件列表 → 这门课真实用过的子目录（同名合并、按出现次数从多到少）。
// 根目录文件（sub_category 为空）也算一个子目录，否则"文件直接挂在学科分类下"的课看起来就是空的。
export function dirsFromItems(items) {
  var map = {}
  var order = []
  ;(items || []).forEach(function (item) {
    if (!item) return
    var name = String(item.sub_category || '').trim()
    var key = normalizeText(name) || ' '
    if (!map[key]) { map[key] = { name: name, count: 0 }; order.push(key) }
    map[key].count++
  })
  order.sort(function (a, b) { return map[b].count - map[a].count })
  return order.map(function (k) { return map[k] })
}

// 站内文件 + 本地待传文件 → 子目录行的可删除性判断（见 dirDeletable）
export function dirHasUploaded(dir) {
  return !!(dir && (dir.fromSite || (dir.files || []).some(function (f) {
    return f.status === 'done' || f.status === 'running'
  })))
}

/**
 * 把 webkitRelativePath 折算成站点使用的 学科 / 子目录。
 *
 * 站点把文件放在 resources/<学科>/<子目录>/<文件名>，所以子目录就是文件的所在文件夹。
 * 用户选中的根文件夹可能是「高等数学2-2」本身，也可能是「我的课程资料」这种总目录，
 * 所以先拿根目录去和站内已有学科比对：
 *   命中 → 根目录就是学科；
 *   没命中且只有两层 → 第一层当学科；
 *   没命中且更深 → 认为第一层是总目录，第二层才是学科。
 */
export function splitRelativePath(relPath, categories) {
  var parts = String(relPath || '').split('/').filter(Boolean)
  if (parts.length === 0) return { category: '', subCategory: '', source: 'empty' }
  var dirs = parts.slice(0, -1)
  if (dirs.length === 0) return { category: '', subCategory: '', source: 'flat' }

  if (dirs.length === 1) {
    var only = bestCategory(dirs[0], categories)
    return only
      ? { category: only.name, subCategory: '', source: 'matched' }
      : { category: dirs[0], subCategory: '', source: 'new' }
  }

  var rootHit = bestCategory(dirs[0], categories)
  if (rootHit) {
    return { category: rootHit.name, subCategory: dirs.slice(1).join('/'), source: 'matched' }
  }
  var secondHit = bestCategory(dirs[1], categories)
  if (secondHit) {
    return { category: secondHit.name, subCategory: dirs.slice(2).join('/'), source: 'matched' }
  }
  // 根目录是“总目录/我的课程资料”这类容器时，第二层才是学科分类；
  // 否则把根目录当学科分类，避免站内没有的学科分类名（如“工业现场总线技术”）
  // 在三级以上被误判成“附件/子目录”。
  if (isContainerName(dirs[0]) && dirs.length >= 2) {
    return { category: dirs[1], subCategory: dirs.slice(2).join('/'), source: 'new' }
  }
  return { category: dirs[0], subCategory: dirs.slice(1).join('/'), source: 'new' }
}

export function isContainerName(name) {
  var key = normalizeText(name)
  return key === normalizeText('我的课程资料') ||
    key === normalizeText('我的资料') ||
    key === normalizeText('课程资料') ||
    key === normalizeText('课程文件') ||
    key === normalizeText('资料') ||
    key === normalizeText('总目录') ||
    key === 'files' || key === 'downloads' || key === 'desktop' ||
    key === normalizeText('下载') || key === normalizeText('桌面')
}

export function safeSegment(value) {
  var s = String(value || '').trim()
  if (!s) return ''
  if (s.indexOf('..') >= 0) return ''
  if (/[<>:"|?*\x00-\x1f]/.test(s)) return ''
  return s
}

// 查重必须带"学科分类/子目录"范围：站点把文件落在 resources/<学科>/<子目录>/<文件名>，
// 不同学科分类里的「第一章.pdf」是两个不同的文件，不该被判成已存在而默认跳过。
export function scopeKey(category, subCategory) {
  return normalizeText(category) + '/' + normalizeText(subCategory)
}

export function dedupeKey(name, size, scope) {
  return (scope ? scope + '@' : '') + normalizeText(name) + '|' + String(Number(size) || 0)
}

export function nameKey(name, scope) {
  return (scope ? scope + '@' : '') + normalizeText(name)
}

export function classify(entry, index, scope) {
  if (!isAllowedName(entry.name)) return 'unsupported'
  if (hasBadName(entry.name)) return 'badname'
  if (Number(entry.size) > MAX_BYTES) return 'toolarge'
  if (!index) return 'ready'
  if (index.byKey[dedupeKey(entry.name, entry.size, scope)]) return 'dup'
  if (index.byName[nameKey(entry.name, scope)]) return 'dupname'
  return 'ready'
}

export function buildGroups(entries, categories) {
  var map = {}
  var order = []
  for (var i = 0; i < entries.length; i++) {
    var entry = entries[i]
    var hit = splitRelativePath(entry.relPath, categories)
    var key = hit.category + '\u0000' + hit.subCategory
    if (!map[key]) {
      map[key] = {
        category: hit.category,
        subCategory: hit.subCategory,
        source: hit.source,
        files: []
      }
      order.push(key)
    }
    map[key].files.push(entry)
  }
  return order.map(function (k) { return map[k] })
}

/** 学科分类下的子目录：同名（归一化后）合并，否则新建。 */
export function upsertDir(course, dirName, entries) {
  var name = String(dirName == null ? '' : dirName).trim()
  var key = normalizeText(name)
  for (var i = 0; i < course.dirs.length; i++) {
    if (normalizeText(course.dirs[i].name) === key) {
      course.dirs[i].files = course.dirs[i].files.concat(entries || [])
      return course.dirs[i]
    }
  }
  var dir = { name: name, files: (entries || []).slice() }
  course.dirs.push(dir)
  return dir
}

// 「新建子目录」连点两次，过去会得到两个一模一样的「新子目录」：左栏两行、
// 右栏标题都分不出谁是谁，用户只能靠猜。默认名按当前已有名字往后编号
// （新子目录 2、新子目录 3…）；用户自己改过的名字不参与、也不会被覆盖。
export function defaultDirName(existing) {
  var list = existing || []
  var taken = {}
  for (var i = 0; i < list.length; i++) taken[normalizeText(list[i])] = true
  var name = DIR_DEFAULT
  for (var n = 2; taken[normalizeText(name)]; n++) name = DIR_DEFAULT + ' ' + n
  return name
}

/* 每个学科分类都固定有一行「根目录」（文件直接挂在分类下，sub_category 传空串）。
 * 站内还没有根文件时也要给这一行，否则用户想把文件直接放分类下就没有落点；
 * 它不参与上传目标收集（空目录本来就传不出东西），也不给删除。 */
export function ensureRootDir(course) {
  if (!course || !course.name) return course
  const dirs = course.dirs || (course.dirs = [])
  const root = dirs.find((d) => !String(d.name || '').trim())
  if (root) { root.isRoot = true; return course }
  dirs.unshift({ name: '', files: [], fromSite: false, isRoot: true })
  return course
}

/* 拖进来的文件夹树 → File 数组，每个 File 的 webkitRelativePath 与 input[webkitdirectory]
 * 给出来的形状一致（第一段是被拖进来的文件夹名），所以后面折算结果也一致。 */
export function flattenDropTree(nodes) {
  var out = []
  function walk(list, prefix) {
    for (var i = 0; i < (list || []).length; i++) {
      var n = list[i]
      if (!n) continue
      var path = prefix ? prefix + '/' + n.name : n.name
      if (n.isDirectory) walk(n.children, path)
      else if (n.file) {
        try {
          Object.defineProperty(n.file, 'webkitRelativePath', { value: path, configurable: true })
        } catch (e) { /* 改不了就退化成只有文件名 */ }
        out.push(n.file)
      }
    }
  }
  walk(nodes, '')
  return out
}

/* 结果导出：一张 CSV（UTF-8 BOM + CRLF，Excel 双击就能看），
 * 传一半断掉时拿这张表核对比看屏幕快。 */
export function csvCell(value) {
  var s = (value === null || value === undefined) ? '' : String(value)
  return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

export function exportCsv(rows) {
  var lines = ['relPath,category,sub_category,status,id,error']
  for (var i = 0; i < (rows || []).length; i++) {
    var r = rows[i] || {}
    lines.push([r.relPath, r.category, r.subCategory, r.status, r.id, r.error].map(csvCell).join(','))
  }
  return '﻿' + lines.join('\r\n') + '\r\n'
}

export function targetFrom(course, dir, entry) {
  return {
    group: { category: course.name, subCategory: dir.name, course: course, dir: dir },
    entry: entry
  }
}

export function collectDirTargets(course, dir, skipDup) {
  var targets = []
  ;((dir && dir.files) || []).forEach(function (entry) {
    if (entry.status === 'ready' || entry.status === 'failed') {
      targets.push(targetFrom(course, dir, entry))
    } else if (!skipDup && (entry.status === 'dup' || entry.status === 'dupname')) {
      targets.push(targetFrom(course, dir, entry))
    }
  })
  return targets
}

export function collectCourseTargets(course, skipDup) {
  var targets = []
  ;((course && course.dirs) || []).forEach(function (dir) {
    targets = targets.concat(collectDirTargets(course, dir, skipDup))
  })
  return targets
}
// 令牌桶：先给 burst 个令牌，之后每 intervalMs 补一个
export function Pacer(burst, intervalMs) {
  this.capacity = burst
  this.intervalMs = intervalMs
  this.tokens = burst
  this.last = Date.now()
}
Pacer.prototype.take = function () {
  var now = Date.now()
  var gained = Math.floor((now - this.last) / this.intervalMs)
  if (gained > 0) {
    this.tokens = Math.min(this.capacity, this.tokens + gained)
    this.last += gained * this.intervalMs
  }
  var self = this
  if (this.tokens > 0) {
    this.tokens--
    return Promise.resolve(0)
  }
  var wait = this.intervalMs - ((Date.now() - this.last) % this.intervalMs)
  return new Promise(function (resolve) {
    setTimeout(function () {
      self.tokens = 0
      self.last = Date.now()
      resolve(wait)
    }, wait)
  })
}

