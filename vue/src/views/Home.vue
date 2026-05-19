<template>
  <NavBar />
  <main>
    <section class="hero">
      <div class="hero-content">
        <div class="notice-card">
          <div class="notice-head">
            <span class="notice-badge">公告</span>
            <span class="notice-date">{{ notice.updated_at || '-' }}</span>
          </div>
          <p class="notice-text">{{ notice.text || '欢迎大家使用 upcshare！' }}</p>
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

    <section class="subjects-section" v-if="!selectedSubject && !query">
      <div class="container">
        <div class="section-bar">
          <h2 class="section-title">全部学科</h2>
          <div class="nav-search subject-search">
            <input v-model="query" placeholder="搜索课件、资料、文档..." @input="search" />
          </div>
        </div>

        <div class="subjects-grid">
          <button v-for="s in subjects" :key="s.name" class="subject-card" @click="openSubject(s.name)">
            <div class="subject-card-top">
              <div>
                <div class="subject-card-name">{{ s.name }}</div>
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
      </div>
    </section>

    <section class="files-section" v-else>
      <div class="container">
        <div class="breadcrumb">
          <a href="#" class="breadcrumb-home" @click.prevent="reset">全部学科</a>
          <span class="breadcrumb-sep">/</span>
          <span class="breadcrumb-current">{{ query ? `搜索：${query}` : selectedSubject }}</span>
        </div>

        <div class="section-bar">
          <h2 class="section-title">{{ query ? '搜索结果' : selectedSubject }}</h2>
          <div class="nav-search subject-search">
            <input v-model="query" placeholder="搜索课件、资料、文档..." @input="search" />
          </div>
        </div>

        <div class="folders-row" v-if="folders.length">
          <button class="folder-chip" :class="{active: !subCategory}" @click="setFolder('')">全部</button>
          <button v-for="f in folders" :key="f.path" class="folder-chip" :class="{active: subCategory === f.path}" @click="setFolder(f.path)">
            {{ f.path }} <span class="folder-chip-count">{{ f.file_count }}</span>
          </button>
        </div>

        <div class="file-table-wrap">
          <table class="file-table">
            <thead>
              <tr><th class="col-name">文件名</th><th>学科</th><th>大小</th><th>日期</th><th></th></tr>
            </thead>
            <tbody>
              <tr v-for="f in files" :key="f.id">
                <td>
                  <div class="file-name-cell">
                    <span class="file-ext-badge" :class="extClass(f.extension)">{{ cleanExt(f.extension) }}</span>
                    <div class="file-name-wrap">
                      <span class="file-name-text">{{ f.original_name }}</span>
                      <span v-if="f.sub_category" class="file-sub-path">{{ f.sub_category }}</span>
                    </div>
                  </div>
                </td>
                <td>{{ f.category }}</td>
                <td>{{ f.file_size }}</td>
                <td>{{ String(f.created_at).slice(0, 10) }}</td>
                <td style="text-align:right"><a class="dl-btn-sm" :href="`/api/download/${f.id}`">下载</a></td>
              </tr>
              <tr v-if="files.length === 0">
                <td colspan="5"><div class="empty-state compact">暂无文件</div></td>
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
import { onMounted, reactive, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import { api } from '../api/http'

const stats = reactive({})
const notice = reactive({ text: '欢迎大家使用 upcshare！', updated_at: '' })
const subjects = ref([])
const folders = ref([])
const files = ref([])
const selectedSubject = ref('')
const subCategory = ref('')
const query = ref('')
const page = ref(1)
const pages = ref(0)
let timer = 0

onMounted(async () => {
  Object.assign(stats, await api('/api/stats'))
  Object.assign(notice, await api('/api/notice'))
  subjects.value = await api('/api/subjects')
})

async function openSubject(name) {
  selectedSubject.value = name
  subCategory.value = ''
  query.value = ''
  page.value = 1
  const data = await api(`/api/subjects/${encodeURIComponent(name)}/folders`)
  folders.value = data.folders || []
  await loadFiles()
}

async function loadFiles() {
  const params = new URLSearchParams({ page: page.value, size: 30 })
  if (query.value) params.set('q', query.value)
  if (selectedSubject.value && !query.value) params.set('category', selectedSubject.value)
  if (subCategory.value && !query.value) params.set('sub_category', subCategory.value)
  const data = await api(`/api/files?${params}`)
  files.value = data.items
  pages.value = data.pages
}

function setFolder(path) {
  subCategory.value = path
  page.value = 1
  loadFiles()
}

function search() {
  clearTimeout(timer)
  timer = setTimeout(() => {
    page.value = 1
    if (query.value.trim()) loadFiles()
  }, 250)
}

function go(p) {
  page.value = p
  loadFiles()
}

function reset() {
  selectedSubject.value = ''
  subCategory.value = ''
  query.value = ''
  files.value = []
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
