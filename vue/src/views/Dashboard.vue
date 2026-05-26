<template>
  <NavBar />
  <section class="dash-section">
    <div class="dash-header">
      <div>
        <h1 class="dash-title">管理后台</h1>
        <p class="dash-subtitle">审核上传、维护资料和管理用户状态</p>
      </div>
      <div class="dash-main-tabs">
        <button class="dash-main-tab" :class="{active: view === 'files'}" @click="switchView('files')">文件管理</button>
        <button class="dash-main-tab" :class="{active: view === 'users'}" @click="switchView('users')">用户管理</button>
        <button class="dash-main-tab" :class="{active: view === 'audit'}" @click="switchView('audit')">风控日志</button>
      </div>
    </div>

    <div class="dash-toolbar">
      <div class="dash-tabs" v-if="view === 'files'">
        <button class="dash-tab" :class="{active: status === 'pending'}" @click="status = 'pending'; load()">待审核</button>
        <button class="dash-tab" :class="{active: status === ''}" @click="status = ''; load()">全部文件</button>
      </div>
      <div class="dash-tabs" v-if="view === 'audit'">
        <button class="dash-tab" :class="{active: auditMode === 'content'}" @click="switchAudit('content')">内容审计</button>
        <button class="dash-tab" :class="{active: auditMode === 'events'}" @click="switchAudit('events')">系统日志</button>
        <button class="dash-tab" :class="{active: auditMode === 'sensitive-users'}" @click="switchAudit('sensitive-users')">敏感用户</button>
        <button class="dash-tab" :class="{active: auditMode === 'blacklist'}" @click="switchAudit('blacklist')">IP 黑名单</button>
      </div>
      <div class="dash-search-row">
        <input v-model="q" class="form-input dash-search" placeholder="搜索..." @input="search" />
        <button class="action-btn" :disabled="loading" @click="load">{{ loading ? '加载中' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="error" class="dash-error">{{ error }}</div>

    <div class="file-table-wrap dash-table">
      <table class="file-table">
        <thead>
          <tr v-if="view === 'files'"><th>文件名</th><th>学科</th><th>上传者</th><th>大小</th><th>状态</th><th>操作</th></tr>
          <tr v-else-if="view === 'users'"><th>UID</th><th>用户名</th><th>级别</th><th>注册时间</th><th>角色</th><th>下载次数</th><th>下载总量</th><th>状态</th></tr>
          <tr v-else-if="auditMode === 'content'"><th>类型</th><th>发表者</th><th>IP</th><th>标题</th><th>内容摘取</th><th>时间</th><th>操作</th></tr>
          <tr v-else-if="auditMode === 'events'"><th>事件</th><th>用户</th><th>IP</th><th>标题</th><th>内容摘取</th><th>时间</th></tr>
          <tr v-else-if="auditMode === 'sensitive-users'"><th>用户</th><th>IP</th><th>命中词</th><th>来源</th><th>时间</th><th>操作</th></tr>
          <tr v-else><th>IP</th><th>原因</th><th>加入时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="`${view}-${auditMode}-${item.id || item.uid || item.ip_address}`">
            <template v-if="view === 'files'">
              <td>
                <div class="file-name-cell">
                  <span class="file-ext-badge" :class="extClass(item.extension)">{{ cleanExt(item.extension) }}</span>
                  <span class="file-name-text">{{ item.original_name }}</span>
                </div>
              </td>
              <td>{{ item.category }}<span v-if="item.sub_category">/{{ item.sub_category }}</span></td>
              <td>{{ item.uploader || 'system' }}</td>
              <td>{{ item.file_size }}</td>
              <td><span class="status-pill" :class="item.status">{{ statusLabel(item.status) }}</span></td>
              <td>
                <div class="action-row">
                  <a class="action-btn download" :href="`/api/download/${item.id}`">下载</a>
                  <button v-if="item.status === 'pending'" class="action-btn approve" @click="fileAction('approve', item.id)">通过</button>
                  <button v-if="item.status === 'pending'" class="action-btn reject" @click="fileAction('reject', item.id)">拒绝</button>
                  <button v-if="item.status !== 'pending'" class="action-btn delete" @click="fileAction('delete', item.id)">删除</button>
                </div>
              </td>
            </template>
            <template v-else-if="view === 'users'">
              <td><span class="user-uid">{{ item.uid }}</span></td>
              <td>
                <div class="user-name-cell">
                  <span class="user-name-text level-username" :class="`level-${item.effective_level || 'gray'}`">{{ displayUser(item) }}</span>
                </div>
              </td>
              <td>
                <select
                  v-if="canManageAdmins && item.username !== 'foggy'"
                  class="level-select"
                  :value="item.is_admin ? 'admin' : item.user_level"
                  :disabled="busyUid === item.uid"
                  @change="levelAction(item, $event.target.value)"
                >
                  <option value="auto">自动</option>
                  <option value="gray">灰色 · 刚注册</option>
                  <option value="blue">蓝色 · 正式用户</option>
                  <option value="green">绿色 · 贡献者</option>
                  <option value="yellow">黄色 · 待定</option>
                  <option value="orange">橙色 · 待定</option>
                  <option value="admin">紫色 · 管理员</option>
                </select>
                <span v-else class="user-level-badge" :class="`level-${item.effective_level || 'gray'}`">{{ levelLabel(item.effective_level) }}</span>
              </td>
              <td>{{ formatUserTime(item.created_at) }}</td>
              <td>{{ item.is_admin ? '管理员' : '普通用户' }}</td>
              <td>{{ item.download_count ?? 0 }}</td>
              <td>{{ item.download_size || '0 B' }}</td>
              <td>
                <span class="status-pill" :class="item.is_active ? 'approved' : 'rejected'">
                  {{ item.is_active ? '正常' : '已封禁' }}
                </span>
              </td>
            </template>
            <template v-else-if="auditMode === 'content'">
              <td>{{ sourceLabel(item.source_type) }}</td>
              <td>{{ item.username || '-' }}</td>
              <td><button class="text-link" @click="filterIp(item.ip_address)">{{ item.ip_address || '-' }}</button></td>
              <td>{{ item.title || '-' }}</td>
              <td class="audit-snippet">{{ item.content_snippet || '-' }}</td>
              <td>{{ formatUserTime(item.created_at) }}</td>
              <td><button class="action-btn delete" :disabled="!item.ip_address || loading" @click="clearIp(item.ip_address)">清理并拉黑</button></td>
            </template>
            <template v-else-if="auditMode === 'events'">
              <td>{{ eventLabel(item.event_type) }}</td>
              <td>{{ item.username || '-' }}</td>
              <td><button class="text-link" @click="filterIp(item.ip_address)">{{ item.ip_address || '-' }}</button></td>
              <td>{{ item.title || '-' }}</td>
              <td class="audit-snippet">{{ item.content_snippet || '-' }}</td>
              <td>{{ formatUserTime(item.created_at) }}</td>
            </template>
            <template v-else-if="auditMode === 'sensitive-users'">
              <td>{{ item.username || item.user_id || '-' }}</td>
              <td><button class="text-link" @click="filterIp(item.ip_address)">{{ item.ip_address || '-' }}</button></td>
              <td>{{ item.matched_words || '-' }}</td>
              <td>{{ sourceLabel(item.source_type) }}</td>
              <td>{{ formatUserTime(item.created_at) }}</td>
              <td><button class="action-btn delete" :disabled="!item.ip_address || loading" @click="clearIp(item.ip_address)">清理并拉黑</button></td>
            </template>
            <template v-else>
              <td>{{ item.ip_address }}</td>
              <td>{{ item.reason || '-' }}</td>
              <td>{{ formatUserTime(item.created_at) }}</td>
            </template>
          </tr>
          <tr v-if="items.length === 0">
            <td :colspan="emptyColspan"><div class="empty-state compact">暂无数据</div></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="dash-pagination" v-if="pages > 1">
      <button :disabled="loading || page <= 1" @click="goPage(page - 1)">上一页</button>
      <button disabled>第 {{ page }} / {{ pages }} 页，共 {{ total }} 条</button>
      <button :disabled="loading || page >= pages" @click="goPage(page + 1)">下一页</button>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import { api } from '../api/http'

const view = ref('files')
const status = ref('pending')
const auditMode = ref('content')
const q = ref('')
const page = ref(1)
const pages = ref(0)
const total = ref(0)
const items = ref([])
const loading = ref(false)
const error = ref('')
const busyUid = ref('')
const canManageAdmins = ref(false)
const emptyColspan = computed(() => {
  if (view.value === 'files') return 6
  if (view.value === 'users') return 8
  if (auditMode.value === 'content') return 7
  if (auditMode.value === 'events') return 6
  if (auditMode.value === 'sensitive-users') return 6
  return 3
})
let timer = 0

onMounted(async () => {
  try {
    const me = await api('/api/auth/me')
    canManageAdmins.value = me.username === 'foggy'
  } catch {
    canManageAdmins.value = false
  }
  await load()
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ page: page.value, size: 50, t: Date.now() })
    if (q.value) params.set('q', q.value)
    if (view.value === 'files' && status.value) params.set('status', status.value)
    const path = view.value === 'audit' ? `/api/admin/audit/${auditMode.value}` : `/api/admin/${view.value}`
    const data = await api(`${path}?${params}`)
    items.value = view.value === 'users' ? (data.items || []).map(normalizeUser) : data.items
    pages.value = Number(data.pages || 0)
    total.value = Number(data.total || 0)
  } catch (e) {
    items.value = []
    pages.value = 0
    total.value = 0
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function switchView(next) {
  view.value = next
  page.value = 1
  q.value = ''
  load()
}

function switchAudit(next) {
  auditMode.value = next
  page.value = 1
  q.value = ''
  load()
}

function goPage(next) {
  const target = Math.min(Math.max(1, next), pages.value || 1)
  if (target === page.value || loading.value) return
  page.value = target
  load()
}

function search() {
  clearTimeout(timer)
  timer = setTimeout(() => { page.value = 1; load() }, 250)
}

async function fileAction(action, id) {
  const method = action === 'delete' ? 'DELETE' : 'POST'
  const path = action === 'delete' ? `/api/admin/files/${id}` : `/api/admin/${action}/${id}`
  await api(path, { method })
  await load()
}

async function userStatusAction(item) {
  busyUid.value = item.uid
  error.value = ''
  try {
    await api(`/api/admin/users/${item.uid}/${item.is_active ? 'ban' : 'unban'}`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = e.message || '用户状态更新失败'
  } finally {
    busyUid.value = ''
  }
}

async function adminRoleAction(item) {
  busyUid.value = item.uid
  error.value = ''
  try {
    await api(`/api/admin/users/${item.uid}/admin?enabled=${!item.is_admin}`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = e.message || '管理员权限更新失败'
  } finally {
    busyUid.value = ''
  }
}

async function levelAction(item, level) {
  busyUid.value = item.uid
  error.value = ''
  try {
    await api(`/api/admin/users/${item.uid}/level?level=${encodeURIComponent(level)}`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = e.message || '用户级别更新失败'
  } finally {
    busyUid.value = ''
  }
}

async function clearIp(ip) {
  if (!ip) return
  if (!window.confirm(`确认清除 IP ${ip} 发表的帖子、评论，并加入网站黑名单？`)) return
  loading.value = true
  error.value = ''
  try {
    await api(`/api/admin/audit/clear-ip?ip=${encodeURIComponent(ip)}`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = e.message || 'IP 清理失败'
  } finally {
    loading.value = false
  }
}

function filterIp(ip) {
  if (!ip) return
  q.value = ip
  page.value = 1
  load()
}

function sourceLabel(value) {
  const labels = { post: '日志/帖子', comment: '评论/留言' }
  return labels[value] || value || '-'
}

function eventLabel(value) {
  const labels = {
    register: '注册',
    login: '登录',
    post: '发表帖子',
    comment: '发表评论',
    auto_lock: '自动锁定',
    clear_ip: 'IP 清理',
    sensitive_post: '帖子敏感词',
    sensitive_comment: '评论敏感词'
  }
  return labels[value] || value || '-'
}

function statusLabel(value) {
  return value === 'pending' ? '待审核' : value === 'approved' ? '已通过' : '已拒绝'
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

function pick(row, key) {
  return row[key] ?? row[key.toUpperCase()] ?? row[key.toLowerCase()]
}

function boolValue(value, fallback = false) {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  return !['0', 'false', 'no'].includes(String(value).toLowerCase())
}

function normalizeUser(row) {
  const uid = pick(row, 'uid')
  const username = String(pick(row, 'username') || pick(row, 'display_name') || '').trim()
  return {
    ...row,
    uid,
    username,
    is_admin: boolValue(pick(row, 'is_admin')),
    is_active: boolValue(pick(row, 'is_active'), true),
    user_level: pick(row, 'user_level') || 'auto',
    effective_level: pick(row, 'effective_level') || 'gray',
    download_count: pick(row, 'download_count') ?? 0,
    download_size: pick(row, 'download_size') || formatBytes(pick(row, 'download_size_raw')) || '0 B'
  }
}

function displayUser(item) {
  return String(item.username || '').trim() || '用户名为空'
}

function formatUserTime(value) {
  const text = String(value || '').trim()
  return text ? text.replace('T', ' ').slice(0, 19) : '-'
}

function formatBytes(value) {
  const size = Number(value || 0)
  if (!Number.isFinite(size) || size <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let n = size
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i += 1
  }
  return `${n >= 10 || i === 0 ? n.toFixed(0) : n.toFixed(1)} ${units[i]}`
}

function levelLabel(level) {
  const labels = {
    gray: '刚注册',
    blue: '正式用户',
    green: '贡献者',
    yellow: '待定',
    orange: '待定',
    admin: '管理员'
  }
  return labels[level] || labels.gray
}
</script>
