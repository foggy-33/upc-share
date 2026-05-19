<template>
  <NavBar />
  <section class="dash-section">
    <h1 class="dash-title">管理后台</h1>
    <div class="dash-main-tabs">
      <button class="dash-main-tab" :class="{active: view === 'files'}" @click="view = 'files'; load()">文件管理</button>
      <button class="dash-main-tab" :class="{active: view === 'users'}" @click="view = 'users'; load()">用户管理</button>
    </div>
    <div class="dash-tabs" v-if="view === 'files'">
      <button class="dash-tab" :class="{active: status === 'pending'}" @click="status = 'pending'; load()">待审核</button>
      <button class="dash-tab" :class="{active: status === ''}" @click="status = ''; load()">全部文件</button>
    </div>
    <input v-model="q" class="form-input" style="max-width:400px;margin-bottom:1rem" placeholder="搜索..." @input="search" />
    <table class="file-table">
      <thead>
        <tr v-if="view === 'files'"><th>文件名</th><th>学科</th><th>上传者</th><th>大小</th><th>状态</th><th>操作</th></tr>
        <tr v-else><th>用户名</th><th>角色</th><th>状态</th><th>下载次数</th><th>下载总量</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <template v-if="view === 'files'">
            <td>{{ item.original_name }}</td><td>{{ item.category }}<span v-if="item.sub_category">/{{ item.sub_category }}</span></td><td>{{ item.uploader || 'system' }}</td><td>{{ item.file_size }}</td><td>{{ item.status }}</td>
            <td>
              <a class="action-btn download" :href="`/api/download/${item.id}`">下载</a>
              <button v-if="item.status === 'pending'" class="action-btn approve" @click="fileAction('approve', item.id)">通过</button>
              <button v-if="item.status === 'pending'" class="action-btn reject" @click="fileAction('reject', item.id)">拒绝</button>
              <button v-if="item.status !== 'pending'" class="action-btn delete" @click="fileAction('delete', item.id)">删除</button>
            </td>
          </template>
          <template v-else>
            <td>{{ item.username }}</td><td>{{ item.is_admin ? '管理员' : '普通用户' }}</td><td>{{ item.is_active ? '正常' : '封禁' }}</td><td>{{ item.download_count }}</td><td>{{ item.download_size }}</td>
            <td><button v-if="!item.is_admin" class="action-btn" @click="userAction(item)">{{ item.is_active ? '封禁' : '解封' }}</button></td>
          </template>
        </tr>
      </tbody>
    </table>
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
  items.value = data.items
  pages.value = data.pages
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
</script>
