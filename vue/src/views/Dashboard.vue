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
      </div>
    </div>

    <div class="dash-toolbar">
      <div class="dash-tabs" v-if="view === 'files'">
        <button class="dash-tab" :class="{active: status === 'pending'}" @click="status = 'pending'; load()">待审核</button>
        <button class="dash-tab" :class="{active: status === ''}" @click="status = ''; load()">全部文件</button>
      </div>
      <input v-model="q" class="form-input dash-search" placeholder="搜索..." @input="search" />
    </div>

    <div class="file-table-wrap dash-table">
      <table class="file-table">
        <thead>
          <tr v-if="view === 'files'"><th>文件名</th><th>学科</th><th>上传者</th><th>大小</th><th>状态</th><th>操作</th></tr>
          <tr v-else><th>用户名</th><th>角色</th><th>下载次数</th><th>下载总量</th><th>状态</th><th class="col-action">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
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
            <template v-else>
              <td>{{ item.username }}</td>
              <td>{{ item.is_admin ? '管理员' : '普通用户' }}</td>
              <td>{{ item.download_count }}</td>
              <td>{{ item.download_size }}</td>
              <td><span class="status-pill" :class="item.is_active ? 'approved' : 'rejected'">{{ item.is_active ? '正常' : '已封禁' }}</span></td>
              <td class="col-action"><button v-if="!item.is_admin" class="action-btn" :class="item.is_active ? 'delete' : 'approve'" @click="userAction(item)">{{ item.is_active ? '封禁' : '解封' }}</button></td>
            </template>
          </tr>
          <tr v-if="items.length === 0">
            <td colspan="6"><div class="empty-state compact">暂无数据</div></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="dash-pagination" v-if="pages > 1">
      <button :disabled="page <= 1" @click="page--; load()">上一页</button>
      <button disabled>{{ page }} / {{ pages }}</button>
      <button :disabled="page >= pages" @click="page++; load()">下一页</button>
    </div>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import { api } from '../api/http'

const view = ref('files')
const status = ref('pending')
const q = ref('')
const page = ref(1)
const pages = ref(0)
const items = ref([])
let timer = 0

onMounted(load)

async function load() {
  const params = new URLSearchParams({ page: page.value, size: 50 })
  if (q.value) params.set('q', q.value)
  if (view.value === 'files' && status.value) params.set('status', status.value)
  const data = await api(`/api/admin/${view.value}?${params}`)
  items.value = view.value === 'users' ? (data.items || []).map(normalizeUser) : data.items
  pages.value = data.pages
}

function switchView(next) {
  view.value = next
  page.value = 1
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

async function userAction(user) {
  await api(`/api/admin/users/${user.id}/${user.is_active ? 'ban' : 'unban'}`, { method: 'POST' })
  await load()
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
  const id = pick(row, 'id')
  const username = String(pick(row, 'username') || '').trim()
  return {
    ...row,
    id,
    username: username || `user-${id}`,
    is_admin: boolValue(pick(row, 'is_admin')),
    is_active: boolValue(pick(row, 'is_active'), true),
    download_count: pick(row, 'download_count') ?? 0,
    download_size: pick(row, 'download_size') || '0 B'
  }
}
</script>
