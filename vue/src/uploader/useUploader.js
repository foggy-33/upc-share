/* 批量投递的状态与动作（组合式函数）。
 *
 * 这里是"业务"，logic.js 是"规则"，api.js 是"和站点说话"。
 * 三条不能忘的规矩：
 *  1. 状态全在内存，不落 localStorage——浏览器也不允许保留 File 句柄，刷新必须重选；
 *  2. 子目录必须挂在某个学科分类下，没选分类不给建（v0.3.22 起的规则）；
 *  3. 上传一律按 PACE 排队，不要为了快而并发——站点 Nginx 会 429，还会连累别人。
 */
import { computed, reactive } from 'vue'
import {
  MAX_BYTES, PACE, Pacer, buildGroups, collectCourseTargets, collectDirTargets, dedupeKey,
  defaultDirName, dirHasUploaded, dirOptions, formatSize, nameKey, normalizeText, safeSegment,
  scopeKey, classify, upsertDir
} from './logic.js'
import * as net from './api.js'

const LOG_MAX = 200

function newEntry(file, relPath) {
  return {
    file,
    name: file.name,
    size: Number(file.size) || 0,
    relPath: relPath || file.name,
    status: 'ready',      // ready | dup | dupname | unsupported | toolarge | badname | running | done | failed
    error: '',
    id: ''
  }
}

export function useUploader() {
  const s = reactive({
    me: null,
    categories: [],
    index: null,
    indexPromise: null,
    course: { name: '', dirs: [] },
    activeDir: null,
    courseFocusName: '',
    running: false,
    cancelled: false,
    controller: null,
    progress: { doneBytes: 0, totalBytes: 0, doneFiles: 0, totalFiles: 0, current: '' },
    log: [],
    options: { skipDup: true, autoApprove: false, dryRun: false },
    site: { show: false, items: null, loading: false, error: '' },
    pendingImport: null,
    dirsCache: {},
    subsByCategory: {},
    loadingDirs: false,
    loadingIndex: false,
    helpOpen: false,     // 「不知道怎么整理？」的说明弹层
    renameTick: 0        // 递增一次 = 请求把焦点交给子目录名输入框（左栏菜单里的「重命名」）
  })

  /* ---------- 派生状态 ---------- */

  const isAdmin = computed(() => !!(s.me && s.me.is_admin))
  const courseValid = computed(() => !!safeSegment(s.course.name))
  const allFiles = computed(() => s.course.dirs.reduce((acc, d) => acc.concat(d.files), []))
  const pendingTargets = computed(() => collectCourseTargets(s.course, s.options.skipDup))
  const dirTargets = computed(() => collectDirTargets(s.course, s.activeDir, s.options.skipDup))
  const canUploadDir = computed(() => !s.running && !s.loadingIndex && courseValid.value && dirTargets.value.length > 0)
  const canUploadAll = computed(() => !s.running && !s.loadingIndex && courseValid.value && pendingTargets.value.length > 0)
  const courseDirs = computed(() => (s.course.dirs || []).map((d) => d.name))
  const dirCandidates = computed(() => dirOptions(
    (s.subsByCategory[normalizeText(s.course.name)] || []), courseDirs.value))
  const categoryCandidates = computed(() => s.categories)

  /** 唯一一处"还差什么"的解释：只在真的有文件等着传、而上传又点不了时才说 */
  const hint = computed(() => {
    if (s.me && s.me.logged_in === false) return '请先在页面上登录，登录后重新进入「上传资料」。'
    if (!courseValid.value && allFiles.value.length > 0) {
      return '文件先收下了，但「上传」还点不了：先在上方选择或输入一个学科分类。'
    }
    return ''
  })

  function logLine(text, kind = '') {
    s.log.push({ at: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text, kind })
    if (s.log.length > LOG_MAX) s.log.splice(0, s.log.length - LOG_MAX)
  }

  /* ---------- 初始化 ---------- */

  async function init() {
    try {
      s.me = await net.loadMe()
    } catch (e) {
      s.me = { logged_in: false }
      logLine('没拿到登录状态：' + e.message, 'err')
    }
    try {
      s.categories = await net.loadCategories()
    } catch (e) {
      logLine('学科分类列表没读到：' + e.message, 'err')
    }
    await ensureIndex()
  }

  /** 索引失败后要清掉 promise，否则查重会永久静默失效 */
  function ensureIndex() {
    if (s.index) return Promise.resolve(s.index)
    if (!s.indexPromise) {
      s.loadingIndex = true
      s.indexPromise = net.loadSiteIndex({ onWarn: (m) => logLine(m, 'err') })
        .then((index) => {
          s.index = index
          s.subsByCategory = Object.assign(s.subsByCategory, index.subsByCategory)
          applyIndex()
          return index
        })
        .catch((err) => {
          logLine('站内索引读取失败：' + err.message + '（查重可能不准）', 'err')
          return null
        })
        .finally(() => {
          s.loadingIndex = false
          if (!s.index) s.indexPromise = null
        })
    }
    return s.indexPromise
  }

  /** 把站内比对结果落到每个待传文件上；终态（done/running/failed）不参与重分类 */
  function applyIndex() {
    if (!s.index) return
    for (const dir of s.course.dirs) {
      const scope = scopeKey(s.course.name, dir.name)
      for (const entry of dir.files) {
        if (entry.status === 'done' || entry.status === 'running' || entry.status === 'failed') continue
        entry.status = classify(entry, s.index, scope)
      }
    }
  }

  /* ---------- 学科分类 ---------- */

  function focusCourse() {
    s.courseFocusName = s.course.name
  }

  /** 从下拉里选，或者输入后失焦命中站内学科分类 */
  async function pickCourse(name) {
    const value = String(name == null ? '' : name).trim()
    if (!value) return
    if (normalizeText(value) === normalizeText(s.courseFocusName)) {
      s.course.name = value
      return
    }
    const files = allFiles.value.length
    if ((s.course.dirs.length || files) &&
      !window.confirm(`切换到「${value}」会清空当前整理的 ${s.course.dirs.length} 个子目录` +
        (files ? `和 ${files} 个文件` : '') + '。已上传到站点的文件不受影响。')) {
      return
    }
    await startCourse(value)
  }

  /** 手打一个站内没有的名字：只改本次落盘的 category，不动已有文件 */
  function renameCourse(name) {
    const value = String(name == null ? '' : name).trim()
    if (!value) { s.course.name = ''; return }
    const before = s.courseFocusName
    if ((s.course.dirs.length || allFiles.value.length) &&
      normalizeText(value) !== normalizeText(before) &&
      !window.confirm(`这些子目录和文件会跟着改挂到「${value}」下，共 ${s.course.dirs.length} 个子目录、` +
        `${allFiles.value.length} 个文件。确认这个学科分类名没写错？`)) {
      s.course.name = before
      return
    }
    s.course.name = value
    s.courseFocusName = value
  }

  async function startCourse(name) {
    const value = String(name || '').trim()
    if (!value) return
    s.course = { name: value, dirs: [] }
    s.activeDir = null
    s.courseFocusName = value
    s.site = { show: false, items: null, loading: false, error: '' }
    if (!safeSegment(value)) return

    const key = normalizeText(value)
    if (s.dirsCache[key]) {
      fillExistingDirs(s.dirsCache[key])
      return
    }
    if (!s.categories.some((c) => normalizeText(c) === key)) return
    s.loadingDirs = true
    try {
      const list = await net.loadCourseDirs(value)
      if (normalizeText(s.course.name) !== key) return
      s.dirsCache[key] = list
      s.subsByCategory[key] = list.map((d) => d.name)
      fillExistingDirs(list)
    } catch (err) {
      logLine(`读取该学科分类的已有子目录失败：${err.message}（不影响上传）`, 'err')
    } finally {
      s.loadingDirs = false
    }
  }

  function fillExistingDirs(list) {
    if (!list || !list.length) return false
    s.course.dirs = list.map((d) => ({ name: d.name, files: [], fromSite: true }))
    s.activeDir = s.course.dirs[0]
    applyIndex()
    logLine(`已载入 ${list.length} 个已有子目录（只显示子目录，不显示已有文件）`, 'ok')
    return true
  }

  function clearCourse() {
    const files = allFiles.value.length
    if (files && !window.confirm(`清除学科分类「${s.course.name}」会把它下面的 ${s.course.dirs.length} ` +
      `个子目录和 ${files} 个待传文件一起去掉。是否继续？`)) return
    const name = s.course.name
    s.course = { name: '', dirs: [] }
    s.activeDir = null
    s.courseFocusName = ''
    s.site = { show: false, items: null, loading: false, error: '' }
    if (name) logLine(`已清除学科分类「${name}」的选择，可以重新选了。`)
  }

  /* ---------- 子目录 ---------- */

  function addDir(name) {
    if (s.running) return
    // 站点里子目录必须挂在某个学科分类下面；没有分类就没有落点。
    // 界面上这颗按钮同时是禁用的，这里是给键盘和其它调用路径兜底。
    if (!safeSegment(s.course.name)) {
      logLine('先选择或输入一个学科分类，再建子目录。', 'err')
      return
    }
    const dir = { name: name || defaultDirName(s.course.dirs.map((d) => d.name)), files: [] }
    s.course.dirs.push(dir)
    s.activeDir = dir
    ensureIndex()
  }

  function renameDir(dir, to) {
    const value = String(to == null ? '' : to).trim()
    const from = dir.name
    if (normalizeText(value) === normalizeText(from)) return
    dir.name = value
    if (s.activeDir === dir) { /* 视图自己会重算 */ }
    logLine(value ? `子目录名已改成「${value}」，本次上传会落到这个子目录。`
      : '子目录名已清空，本次上传会直接挂在学科分类下。', 'ok')
  }

  function canRenameDir(dir) {
    return !!dir && !dirHasUploaded(dir)
  }

  /** 左栏菜单点「重命名」：先切成当前子目录，再让右侧把焦点交给名字输入框 */
  function requestRename(dir) {
    if (!canRenameDir(dir)) return
    s.activeDir = dir
    s.renameTick++
  }

  function removeDir(dir) {
    if (!canRenameDir(dir)) return
    if (dir.files.length &&
      !window.confirm(`移除「${dir.name || '未命名子目录'}」会把里面 ${dir.files.length} 个待传文件` +
        '一起从本次列表去掉。是否继续？')) return
    const i = s.course.dirs.indexOf(dir)
    if (i < 0) return
    s.course.dirs.splice(i, 1)
    s.activeDir = s.course.dirs[Math.max(0, i - 1)] || null
  }

  /** 站内子目录是"有文件才存在"的：文件删完，这一行就该跟着消失 */
  function dropEmptiedSiteDir(dir) {
    if (!dir || !dir.fromSite || dir.files.length) return
    const i = s.course.dirs.indexOf(dir)
    if (i < 0) return
    s.course.dirs.splice(i, 1)
    if (s.activeDir === dir) s.activeDir = s.course.dirs[0] || null
    logLine(`「${dir.name || '根目录'}」已经没有文件，子目录一并去掉了。`, 'ok')
  }

  /* ---------- 文件 ---------- */

  function stageFiles(entries, dir) {
    const target = dir || s.activeDir
    if (!target) return
    target.files = target.files.concat(entries)
    applyIndex()
    logLine(`已添加 ${entries.length} 个文件到「${target.name || '根目录'}」`)
    ensureIndex()
  }

  function addFiles(fileList) {
    if (s.running) return
    const entries = Array.prototype.slice.call(fileList || []).map((f) => newEntry(f))
    if (!entries.length) return
    if (!s.activeDir) addDir()
    if (!s.activeDir) return       // addDir 可能因为还没选学科分类而拒绝
    stageFiles(entries)
  }

  /** 「从文件夹导入」：先算出结构，弹一次看得见规模的确认，再落进工作台 */
  function importFromFolder(fileList) {
    const files = Array.prototype.slice.call(fileList || [])
    if (!files.length) return
    const entries = files.map((f) => newEntry(f, f.webkitRelativePath || f.name))
    const groups = buildGroups(entries, s.categories)
    if (!groups.length) { logLine('这个文件夹里没有找到可上传的文件。', 'err'); return }
    const dirs = new Set(groups.map((g) => g.subCategory))
    if (groups.length > 1 && new Set(groups.map((g) => g.category)).size > 1) {
      s.pendingImport = { groups, multi: true }
      return
    }
    s.pendingImport = {
      groups,
      multi: false,
      category: groups[0].category,
      fileCount: groups.reduce((n, g) => n + g.files.length, 0),
      dirCount: dirs.size
    }
  }

  function confirmImport(courseName) {
    const pending = s.pendingImport
    s.pendingImport = null
    if (!pending) return
    applyImport(pending.groups, courseName == null ? pending.category : courseName)
  }

  function cancelImport() {
    s.pendingImport = null
    logLine('已取消导入。')
  }

  function applyImport(groups, courseName) {
    const name = courseName == null ? (groups[0] && groups[0].category) || '' : courseName
    if (allFiles.value.length && normalizeText(s.course.name) !== normalizeText(name) &&
      !window.confirm(`当前学科分类已有文件。导入「${name || '未指定学科分类'}」会替换当前学科分类，继续？`)) return
    if (!s.course.name || normalizeText(s.course.name) !== normalizeText(name)) {
      s.course = { name, dirs: [] }
      s.activeDir = null
      s.courseFocusName = name
    }
    let added = 0
    for (const g of groups) {
      if (normalizeText(g.category) !== normalizeText(name)) continue
      upsertDir(s.course, g.subCategory, g.files)
      added += g.files.length
    }
    if (!s.activeDir) s.activeDir = s.course.dirs[0] || null
    applyIndex()
    logLine(`已导入 ${added} 个文件，${s.course.dirs.length} 个子目录。`, 'ok')
  }

  function removeEntry(dir, entry) {
    if (s.running) return
    const i = dir.files.indexOf(entry)
    if (i >= 0) dir.files.splice(i, 1)
  }

  /* ---------- 上传 ---------- */

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

  function uploadDir(dir) {
    return runTargets(collectDirTargets(s.course, dir, s.options.skipDup))
  }

  function uploadAll() {
    return runTargets(collectCourseTargets(s.course, s.options.skipDup))
  }

  function retryEntry(entry) {
    const dir = s.course.dirs.find((d) => d.files.includes(entry))
    if (!dir) return Promise.resolve()
    return runTargets([{ group: { category: s.course.name, subCategory: dir.name }, entry }])
  }

  function stop() {
    s.cancelled = true
    if (s.controller) s.controller.abort()
    logLine('正在停止…', 'err')
  }

  async function runTargets(targets) {
    if (!targets || !targets.length) return
    const autoApprove = s.options.autoApprove && isAdmin.value
    const dryRun = s.options.dryRun

    s.running = true
    s.cancelled = false
    s.controller = new AbortController()

    const totalBytes = targets.reduce((sum, t) => sum + (Number(t.entry.size) || 0), 0)
    Object.assign(s.progress, { doneBytes: 0, totalBytes, doneFiles: 0, totalFiles: targets.length, current: '' })
    if (dryRun) logLine('试运行：只检查，不会真正上传任何文件。')

    const pacer = new Pacer(PACE.burst, PACE.intervalMs)
    let processed = 0

    for (let i = 0; i < targets.length; i++) {
      processed++
      if (s.cancelled) break
      const { group, entry } = targets[i]
      const category = safeSegment(group.category)
      const subCategory = safeSegment(group.subCategory)
      if (!category) { entry.status = 'badname'; continue }

      if (dryRun) {
        entry.status = 'ready'
        logLine(`将上传：${entry.relPath} → ${category}/${subCategory}`)
        s.progress.doneFiles = i + 1
        s.progress.doneBytes += entry.size
        continue
      }

      entry.status = 'running'
      entry.error = ''
      logLine(`上传中：${entry.relPath} → ${category}/${subCategory || '(无子目录)'}`)

      let attempt = 0
      let ok = false
      while (!ok && attempt < 4 && !s.cancelled) {
        attempt++
        try {
          await pacer.take()
          if (s.cancelled) break
          s.progress.current = entry.name
          const result = await net.uploadFile({
            file: entry.file,
            name: entry.name,
            category,
            subCategory,
            signal: s.controller.signal,
            onProgress: (loaded) => {
              s.progress.current = `${entry.name}（${formatSize(loaded)}/${formatSize(entry.size)}）`
            }
          })
          entry.id = result.id
          entry.status = 'done'
          if (autoApprove) {
            try {
              await net.approveFile(result.id)
              logLine(`✓ 已上传并审核通过：${entry.name}`, 'ok')
            } catch (approveError) {
              logLine(`✓ 已上传，但自动审核失败：${entry.name}（${approveError.message}）`, 'err')
            }
          } else {
            logLine(`✓ 已上传（待审核）：${entry.name}`, 'ok')
          }
          if (s.index) {
            const scope = scopeKey(group.category, group.subCategory)
            s.index.byKey[dedupeKey(entry.name, entry.size, scope)] = true
            s.index.byName[nameKey(entry.name, scope)] = true
          }
          ok = true
        } catch (err) {
          if (err.aborted || s.cancelled) {
            // 中断也要把状态落回 failed：留在 running 会让这个文件再也进不了待传队列，
            // 还会让所在子目录被 dirHasUploaded() 判成"站内已有"而锁死改名/移除
            entry.status = 'failed'
            entry.error = '已停止'
            throw err
          }
          const status = err.status || 0
          const retryable = status === 0 || status === 429 || status >= 500
          logLine(`✗ ${entry.name} 失败：${err.message}` + (retryable ? `（${attempt}/4，重试中）` : ''), 'err')
          if (!retryable || attempt >= 4) { entry.status = 'failed'; entry.error = err.message; break }
          await sleep(status === 429 ? 15000 * attempt : 4000 * attempt)
        }
      }
      if (!ok && entry.status !== 'failed') {
        entry.status = 'failed'
        entry.error = s.cancelled ? '已停止' : '多次重试仍失败'
      }
      s.progress.doneFiles = i + 1
      s.progress.doneBytes += entry.size
      await sleep(250)
    }

    s.running = false
    s.controller = null
    const done = targets.filter((t) => t.entry.status === 'done').length
    if (s.cancelled) logLine(`已停止。本轮处理 ${processed}/${targets.length}，可随时再传。`, 'err')
    else if (dryRun) logLine(`试运行结束：检查了 ${processed} 个文件，没有上传。`, 'ok')
    else logLine(`本轮结束：完成 ${done}/${targets.length}，可以在站点「待审核」里复查。`, 'ok')
  }

  /* ---------- 已有资源（仅管理员） ---------- */

  async function toggleSite() {
    if (!isAdmin.value) return
    s.site.show = !s.site.show
    if (!s.site.show) return
    const dir = s.activeDir
    const key = normalizeText(s.course.name) + '/' + normalizeText(dir && dir.name)
    if (s.site.items && s.site.key === key) return
    s.site.loading = true
    s.site.error = ''
    try {
      const items = await net.loadSiteFiles(s.course.name, dir ? dir.name : '', {
        onWarn: (m) => logLine(m, 'err')
      })
      s.site = { show: true, key, items, loading: false, error: '' }
    } catch (err) {
      s.site = { show: true, key, items: [], loading: false, error: err.message }
    }
  }

  async function deleteSiteFile(item, dir) {
    try {
      await net.deleteSiteFile(item.id)
      s.site.items = (s.site.items || []).filter((x) => x.id !== item.id)
      logLine(`已删除站内文件：${item.original_name}，剩余 ${(s.site.items || []).length} 个。`, 'ok')
      dropEmptiedSiteDir(dir)
    } catch (err) {
      logLine(`删除失败：${item.original_name}（${err.message}）`, 'err')
    }
  }

  return {
    s,
    MAX_BYTES,
    // 派生
    isAdmin, courseValid, allFiles, pendingTargets, dirTargets, canUploadDir, canUploadAll,
    dirCandidates, categoryCandidates, hint,
    // 动作
    init, logLine, focusCourse, pickCourse, renameCourse, startCourse, clearCourse,
    ensureIndex, addDir, renameDir, canRenameDir, requestRename, removeDir, addFiles, stageFiles,
    importFromFolder, confirmImport, cancelImport, applyImport, removeEntry,
    uploadDir, uploadAll, retryEntry, stop, toggleSite, deleteSiteFile
  }
}
