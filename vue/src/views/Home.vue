<template>
  <NavBar @home="reset" />
  <main>
    <section class="hero">
      <div class="hero-content">
        <div class="notice-card">
          <div class="notice-head">
            <span class="notice-badge">公告</span>
          </div>
          <p class="notice-text">{{ notice.text || '欢迎大家使用 upcshare！未来会有很激进的更新，建议大家加一下群1082868823，出现服务器挂掉或者遇到其他问题可以随时联系，也可以收到关于变动的最新消息' }}</p>
        </div>

        <div class="stats-row">
          <div class="stat-item"><span class="stat-value">{{ stats.subject_count ?? '-' }}</span><span class="stat-label">学科</span></div>
          <div class="stat-divider"></div>
          <div class="stat-item"><span class="stat-value">{{ stats.total_files ?? '-' }}</span><span class="stat-label">文件</span></div>
          <div class="stat-divider"></div>
          <div class="stat-item"><span class="stat-value">{{ stats.total_size || '-' }}</span><span class="stat-label">总大小</span></div>
          <div class="stat-divider"></div>
          <div class="stat-item"><span class="stat-value">{{ stats.total_downloads ?? '-' }}</span><span class="stat-label">下载</span></div>
        </div>
      </div>
    </section>

    <section class="subjects-section" v-if="!selectedSubject">
      <div class="container">
        <div class="section-bar">
          <h2 class="section-title">
            <template v-if="query">
              <a href="#" class="title-link" @click.prevent="reset">全部学科</a>
              <span class="title-sep">/</span>
              <span>搜索结果</span>
            </template>
            <template v-else>全部学科</template>
          </h2>
          <div class="subject-tools">
            <div class="nav-search subject-search">
              <input v-model="query" placeholder="搜索学科分类..." />
            </div>
            <button
              class="view-toggle"
              type="button"
              :title="viewMode === 'list' ? '切换为宫格' : '切换为列表'"
              :aria-label="viewMode === 'list' ? '切换为宫格' : '切换为列表'"
              @click="toggleView"
            >
              <svg v-if="viewMode === 'list'" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M4 4h7v7H4V4Zm9 0h7v7h-7V4ZM4 13h7v7H4v-7Zm9 0h7v7h-7v-7Z"/></svg>
              <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M4 5h3v3H4V5Zm0 6h3v3H4v-3Zm0 6h3v3H4v-3ZM9 6h11v2H9V6Zm0 6h11v2H9v-2Zm0 6h11v2H9v-2Z"/></svg>
            </button>
          </div>
        </div>

        <div ref="gridEl" class="subjects-grid" :class="{ list: viewMode === 'list' }">
          <button v-for="s in filteredSubjects" :key="s.name" class="subject-card" @click="openSubject(s.name)">
            <div class="subject-card-top">
              <div>
                <div class="subject-card-name" :title="s.name">{{ s.name }}</div>
                <div class="subject-card-meta">{{ s.file_count }} 个文件 · {{ s.total_size }}</div>
              </div>
              <span class="subject-arrow">›</span>
            </div>
            <div class="subject-card-tags">
              <span
                v-for="(_, ext) in s.extensions"
                :key="ext"
                class="subject-card-tag"
                :class="extClass(ext)"
              >
                {{ cleanExt(ext) }}
              </span>
            </div>
          </button>
        </div>
        <div v-if="query && !filteredSubjects.length" class="empty-state compact">没有匹配的学科分类</div>
      </div>
    </section>

    <section class="files-section" v-else>
      <div class="container">
        <div class="section-bar">
          <h2 class="section-title">
            <a href="#" class="title-link" @click.prevent="reset">全部学科</a>
            <span class="title-sep">/</span>
            <span>{{ selectedSubject }}</span>
          </h2>
          <div class="nav-search subject-search">
            <input v-model="query" placeholder="搜索当前学科的文件..." @input="searchFiles" />
          </div>
        </div>

        <div class="folders-row" v-if="folders.length">
          <button class="folder-chip" :class="{active: !subCategory}" @click="setFolder('')">全部</button>
          <button v-for="f in folders" :key="f.path" class="folder-chip" :class="{active: subCategory === f.path}" @click="setFolder(f.path)">
            {{ f.path }} <span class="folder-chip-count">{{ f.file_count }}</span>
          </button>
        </div>

        <p v-if="deleteError" class="form-message error">{{ deleteError }}</p>
        <div class="file-table-wrap">
          <table class="file-table home-files">
            <colgroup>
              <col style="width:37%"><col style="width:15%"><col style="width:10%">
              <col style="width:8%"><col style="width:12%"><col style="width:18%">
            </colgroup>
            <thead>
              <tr><th>文件名</th><th>学科</th><th>大小</th><th>下载量</th><th>日期</th><th class="col-action"></th></tr>
            </thead>
            <tbody>
              <tr v-for="f in files" :key="f.id">
                <td>
                  <div class="file-name-cell">
                    <span class="file-ext-badge" :class="extClass(f.extension)">{{ cleanExt(f.extension) }}</span>
                    <div class="file-name-wrap">
                      <span class="file-name-text" :title="f.original_name">{{ f.original_name }}</span>
                      <span v-if="f.sub_category" class="file-sub-path" :title="f.sub_category">{{ f.sub_category }}</span>
                    </div>
                  </div>
                </td>
                <td :title="[f.category, f.sub_category].filter(Boolean).join('/')">{{ f.category }}</td>
                <td>{{ f.file_size }}</td>
                <td>{{ f.download_count ?? 0 }}</td>
                <td>{{ String(f.created_at).slice(0, 10) }}</td>
                <td class="col-action">
                  <div class="file-actions">
                    <button
                      v-if="me.is_admin"
                      class="dl-btn-sm danger"
                      :class="{ armed: armedDelete === f.id }"
                      type="button"
                      :disabled="deleting === f.id"
                      :title="armedDelete === f.id ? '再点一次确认删除，4 秒内有效' : '删除这个站内文件（仅管理员，需点两次）'"
                      @click="requestDelete(f)"
                    >{{ deleting === f.id ? '删除中…' : armedDelete === f.id ? '确认删除' : '删除' }}</button>
                    <a class="dl-btn-sm" :href="`/api/download/${f.id}`">下载</a>
                  </div>
                </td>
              </tr>
              <tr v-if="files.length === 0">
                <td colspan="6"><div class="empty-state compact">暂无文件</div></td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="pagination" v-if="pages > 1">
          <button class="page-btn" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
          <button class="page-btn active">{{ page }} / {{ pages }}</button>
          <button class="page-btn" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
        </div>
      </div>
    </section>
  </main>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavBar from '../components/NavBar.vue'
import { api } from '../api/http'
import { currentUser as me } from '../authState'
import { fuzzyFilter } from '../uploader/logic'

const stats = reactive({})
const route = useRoute()
const router = useRouter()
const notice = reactive({ text: '欢迎大家使用 upcshare！' })
const subjects = ref([])
const folders = ref([])
const files = ref([])
const selectedSubject = ref('')
const subCategory = ref('')
const query = ref(String(route.query.q || ''))
const page = ref(1)
const pages = ref(0)
let timer = 0
let filesSeq = 0
const armedDelete = ref('')
const deleting = ref('')
const deleteError = ref('')
let deleteTimer = 0
const VIEW_KEY = 'upcshare-subject-view'
const gridEl = ref(null)
let savedView = 'grid'
try { savedView = localStorage.getItem(VIEW_KEY) || 'grid' } catch { /* keep grid */ }
const viewMode = ref(savedView === 'list' ? 'list' : 'grid')
const filteredSubjects = computed(() => {
  const q = query.value.trim()
  if (!q) return subjects.value
  const names = fuzzyFilter(subjects.value.map((s) => s.name), q, Math.max(1, subjects.value.length))
  const matched = new Set(names)
  return subjects.value.filter((s) => matched.has(s.name))
})

onMounted(async () => {
  Object.assign(stats, await api('/api/stats'))
  Object.assign(notice, await api('/api/notice'))
  subjects.value = await api('/api/subjects')
})

watch(() => route.query.q, value => {
  const next = String(value || '')
  if (next === query.value) return
  query.value = next
  selectedSubject.value = ''
  subCategory.value = ''
  page.value = 1
  files.value = []
})

/** 清空搜索时同步去掉 URL 上的 ?q=，否则刷新后旧搜索词会回来 */
function clearQueryParam() {
  if (!Object.prototype.hasOwnProperty.call(route.query, 'q')) return
  const rest = { ...route.query }
  delete rest.q
  router.replace({ path: route.path, query: rest })
}

async function openSubject(name) {
  armedDelete.value = ''
  deleteError.value = ''
  selectedSubject.value = name
  subCategory.value = ''
  query.value = ''
  clearQueryParam()
  page.value = 1
  const data = await api(`/api/subjects/${encodeURIComponent(name)}/folders`)
  folders.value = data.folders || []
  await loadFiles()
}

async function loadFiles() {
  const seq = ++filesSeq
  const params = new URLSearchParams({ page: page.value, size: 30 })
  if (selectedSubject.value) params.set('category', selectedSubject.value)
  if (subCategory.value) params.set('sub_category', subCategory.value)
  if (query.value) params.set('q', query.value)
  const data = await api(`/api/files?${params}`)
  // 输入很快时旧请求可能后到：只认最后一次发起的结果
  if (seq !== filesSeq) return
  files.value = data.items
  pages.value = data.pages
}

function setFolder(path) {
  subCategory.value = path
  page.value = 1
  loadFiles()
}

function searchFiles() {
  clearTimeout(timer)
  timer = setTimeout(() => {
    page.value = 1
    loadFiles()
  }, 250)
}

async function toggleView() {
  const grid = gridEl.value
  const cards = grid ? [...grid.querySelectorAll('.subject-card')] : []
  const first = cards.map((card) => card.getBoundingClientRect())
  viewMode.value = viewMode.value === 'list' ? 'grid' : 'list'
  try { localStorage.setItem(VIEW_KEY, viewMode.value) } catch { /* keep this session */ }
  await nextTick()
  if (!grid || !cards.length || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const last = cards.map((card) => card.getBoundingClientRect())
  cards.forEach((card, i) => {
    const dx = first[i].left - last[i].left
    const dy = first[i].top - last[i].top
    card.animate([
      { transform: dx || dy ? `translate(${dx}px, ${dy}px)` : 'none', opacity: 0.72 },
      { transform: 'none', opacity: 1 }
    ], { duration: 300, easing: 'cubic-bezier(.2,.8,.2,1)' })
  })
}

function requestDelete(file) {
  if (deleting.value) return
  if (armedDelete.value !== file.id) {
    armedDelete.value = file.id
    clearTimeout(deleteTimer)
    deleteTimer = setTimeout(() => { armedDelete.value = '' }, 4000)
    return
  }
  clearTimeout(deleteTimer)
  deleteFile(file)
}

async function deleteFile(file) {
  armedDelete.value = ''
  deleting.value = file.id
  deleteError.value = ''
  try {
    await api(`/api/admin/files/${encodeURIComponent(file.id)}`, { method: 'DELETE' })
    await loadFiles()
    Object.assign(stats, await api('/api/stats'))
    subjects.value = await api('/api/subjects')
  } catch (err) {
    deleteError.value = err.message
  } finally {
    deleting.value = ''
  }
}

function go(p) {
  page.value = p
  loadFiles()
}

function reset() {
  clearTimeout(timer)
  clearTimeout(deleteTimer)
  filesSeq++
  armedDelete.value = ''
  deleteError.value = ''
  selectedSubject.value = ''
  subCategory.value = ''
  query.value = ''
  clearQueryParam()
  folders.value = []
  files.value = []
  page.value = 1
  pages.value = 0
}

function cleanExt(ext) {
  return String(ext || 'file').replace('.', '').toUpperCase()
}

function extClass(ext) {
  const value = cleanExt(ext).toLowerCase()
  if (['doc', 'docx'].includes(value)) return 'doc'
  if (['ppt', 'pptx'].includes(value)) return 'ppt'
  if (['xls', 'xlsx', 'csv'].includes(value)) return 'xls'
  if (['zip', '7z', 'rar', 'tar', 'gz'].includes(value)) return value === 'rar' ? 'rar' : 'zip'
  if (['txt', 'md'].includes(value)) return 'txt'
  return value || 'file'
}
</script>
