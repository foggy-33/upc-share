<template>
  <nav class="navbar">
    <div class="nav-container">
      <router-link to="/" class="nav-logo">upcshare</router-link>
      <div class="nav-links">
        <router-link to="/admin" class="nav-link">上传资料</router-link>
        <router-link v-if="me.is_admin" to="/dashboard" class="nav-link" style="color:var(--accent)">管理后台</router-link>
        <span v-if="me.logged_in" class="nav-link" style="color:var(--text-tertiary)">{{ me.username }}</span>
        <a v-if="me.logged_in" href="#" class="nav-link" @click.prevent="logout">退出</a>
        <router-link v-if="!me.logged_in" to="/login" class="nav-link">登录</router-link>
        <router-link v-if="!me.logged_in" to="/register" class="nav-link">注册</router-link>
      </div>
    </div>
  </nav>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { api } from '../api/http'

const me = reactive({ logged_in: false, username: '', is_admin: false })

onMounted(async () => {
  Object.assign(me, await api('/api/auth/me'))
})

async function logout() {
  await api('/api/auth/logout', { method: 'POST' })
  location.href = '/'
}
</script>
