<template>
  <NavBar />
  <section class="dash-section">
    <div class="dash-header">
      <div>
        <h1 class="dash-title">内容管理后台</h1>
        <p class="dash-subtitle">{{ me.username }} · {{ me.group_name }}</p>
      </div>
      <div class="dash-main-tabs">
        <button class="dash-main-tab" :class="{active: view === 'posts'}" @click="switchView('posts')">日志/帖子</button>
        <button class="dash-main-tab" :class="{active: view === 'files'}" @click="switchView('files')">资料分类</button>
        <button v-if="me.can_enter_user_backend" class="dash-main-tab" :class="{active: view === 'users'}" @click="switchView('users')">用户后台</button>
        <button v-if="me.can_manage_forum_sections" class="dash-main-tab" :class="{active: view === 'forumSections'}" @click="switchView('forumSections')">论坛板块</button>
        <button v-if="me.can_publish_site_notice" class="dash-main-tab" :class="{active: view === 'notice'}" @click="switchView('notice')">公告管理</button>
      </div>
    </div>

    <div class="dash-toolbar" v-if="view !== 'notice' && view !== 'forumSections'">
      <div class="dash-search-row">
        <input v-model="q" class="form-input dash-search" placeholder="搜索..." @input="search" />
        <button class="action-btn" :disabled="loading" @click="load">{{ loading ? '加载中' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="error" class="dash-error">{{ error }}</div>

    <div v-if="view === 'notice'" class="content-admin-panel">
      <section v-if="me.can_publish_site_notice" class="content-admin-box">
        <h2>发布站点公告</h2>
        <textarea v-model="siteNotice" class="form-input" rows="4" placeholder="公告内容"></textarea>
        <button class="action-btn approve" @click="publish('site-notice', siteNotice)">发布公告</button>
      </section>
    </div>
    <ForumSectionAdmin v-else-if="view === 'forumSections' && me.can_manage_forum_sections" endpoint-base="/api/content-admin/forum/sections" />

    <div v-else class="file-table-wrap dash-table">
      <table class="file-table">
        <thead>
          <tr v-if="view === 'posts'"><th>板块</th><th>作者</th><th>标题</th><th>内容摘取</th><th>IP</th><th>时间</th><th>操作</th></tr>
          <tr v-else-if="view === 'files'"><th>资料名</th><th>分类</th><th>上传者</th><th>大小</th><th>状态</th><th>操作</th></tr>
          <tr v-else><th>UID</th><th>用户名</th><th>用户组</th><th>状态</th><th>IP</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id || item.uid">
            <template v-if="view === 'posts'">
              <td>{{ item.section }}</td>
              <td>{{ item.username }}</td>
              <td>{{ item.title || '-' }}</td>
              <td class="audit-snippet">{{ item.content_snippet || '-' }}</td>
              <td>{{ item.ip_address || '-' }}</td>
              <td>{{ formatTime(item.created_at) }}</td>
              <td><button class="action-btn delete" @click="deletePost(item)">删除</button></td>
            </template>
            <template v-else-if="view === 'files'">
              <td>{{ item.original_name }}</td>
              <td>{{ item.category }}<span v-if="item.sub_category">/{{ item.sub_category }}</span></td>
              <td>{{ item.uploader || 'system' }}</td>
              <td>{{ item.file_size }}</td>
              <td><span class="status-pill" :class="item.status">{{ item.status }}</span></td>
              <td>
                <button v-if="item.status !== 'approved'" class="action-btn approve" @click="approveFile(item)">通过</button>
                <button v-if="item.status !== 'approved'" class="action-btn reject" @click="rejectFile(item)">驳回</button>
                <button v-else class="action-btn delete" @click="deleteFile(item)">删除</button>
              </td>
            </template>
            <template v-else>
              <td>{{ item.uid }}</td>
              <td>{{ item.username }}</td>
              <td>{{ item.user_level }}</td>
              <td>{{ item.is_active ? '正常' : '已封禁' }}</td>
              <td>{{ item.last_ip || '-' }}</td>
              <td><button v-if="me.can_modify_user" class="action-btn" @click="setUserStatus(item)">{{ item.is_active ? '锁定' : '解锁' }}</button></td>
            </template>
          </tr>
          <tr v-if="items.length === 0">
            <td :colspan="view === 'users' ? 6 : view === 'posts' ? 7 : 6"><div class="empty-state compact">暂无数据</div></td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import ForumSectionAdmin from '../components/ForumSectionAdmin.vue'
import { api } from '../api/http'

const me = reactive({})
const view = ref('posts')
const q = ref('')
const items = ref([])
const loading = ref(false)
const error = ref('')
const siteNotice = ref('')
let timer = 0

onMounted(async () => {
  try {
    Object.assign(me, await api('/api/content-admin/me'))
    await load()
  } catch (e) {
    error.value = e.message || '无权进入内容管理后台'
  }
})

async function load() {
  if (view.value === 'notice' || view.value === 'forumSections') return
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ q: q.value, t: Date.now() })
    const data = await api(`/api/content-admin/${view.value}?${params}`)
    items.value = data.items || []
  } catch (e) {
    items.value = []
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function switchView(next) {
  view.value = next
  q.value = ''
  if (next === 'notice' && me.can_publish_site_notice) loadSiteNotice()
  load()
}

function search() {
  clearTimeout(timer)
  timer = setTimeout(load, 250)
}

async function setUserStatus(item) {
  await api(`/api/content-admin/users/${item.uid}/status?active=${!item.is_active}`, { method: 'POST' })
  await load()
}

async function deletePost(item) {
  if (!confirm(`确认删除帖子「${item.title || item.id}」？`)) return
  await api(`/api/content-admin/posts/${item.id}`, { method: 'DELETE' })
  await load()
}

async function approveFile(item) {
  await api(`/api/content-admin/files/${item.id}/approve`, { method: 'POST' })
  await load()
}

async function rejectFile(item) {
  if (!confirm(`确认驳回并删除资料「${item.original_name || item.id}」？`)) return
  await api(`/api/content-admin/files/${item.id}/reject`, { method: 'POST' })
  await load()
}

async function deleteFile(item) {
  if (!confirm(`确认删除资料「${item.original_name || item.id}」？`)) return
  await api(`/api/content-admin/files/${item.id}`, { method: 'DELETE' })
  await load()
}

async function publish(type, value) {
  await api(`/api/content-admin/settings/${type}`, { method: 'POST', body: JSON.stringify({ value }) })
}

async function loadSiteNotice() {
  try {
    const data = await api('/api/content-admin/settings/site-notice')
    siteNotice.value = data.value || ''
  } catch (e) {
    error.value = e.message || '公告加载失败'
  }
}

function formatTime(value) {
  const text = String(value || '').trim()
  return text ? text.replace('T', ' ').slice(0, 19) : '-'
}
</script>
