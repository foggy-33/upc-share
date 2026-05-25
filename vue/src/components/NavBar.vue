<template>
  <nav class="navbar">
    <div class="nav-container">
      <router-link to="/" class="nav-logo">upcshare</router-link>
      <div class="nav-links">
        <router-link to="/" class="nav-link">资料库</router-link>
        <router-link to="/forum" class="nav-link">论坛</router-link>
        <router-link to="/admin" class="nav-link">上传资料</router-link>
        <router-link v-if="me.is_admin" to="/dashboard" class="nav-link admin-link">管理后台</router-link>
        <router-link v-if="!me.logged_in" to="/login" class="nav-link">登录</router-link>
        <router-link v-if="!me.logged_in" to="/register" class="nav-link">注册</router-link>
      </div>
    </div>
    <router-link
      v-if="me.logged_in"
      to="/profile"
      class="nav-avatar-link"
      aria-label="进入个人空间"
      :title="`${me.username} 的个人空间`"
    >
      <span class="nav-avatar">
        <img v-if="me.avatar_url" :src="me.avatar_url" alt="" />
        <span v-else>{{ avatarText }}</span>
      </span>
    </router-link>
  </nav>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue'
import { api } from '../api/http'

const me = reactive({ logged_in: false, username: '', is_admin: false, avatar_url: '' })
const avatarText = computed(() => String(me.username || 'U').trim().slice(0, 1).toUpperCase())

onMounted(async () => {
  Object.assign(me, await api('/api/auth/me'))
})
</script>
