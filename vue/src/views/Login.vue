<template>
  <div class="auth-page">
    <div class="auth-card">
      <h1 class="auth-title">欢迎回来</h1>
      <p class="auth-subtitle">登录以上传和下载学科资料</p>
      <div v-if="error" class="auth-error" style="display:block">{{ error }}</div>
      <form class="auth-form" @submit.prevent="submit">
        <div class="form-group"><label class="form-label">用户名</label><input v-model="username" class="form-input" autocomplete="username" /></div>
        <div class="form-group"><label class="form-label">密码</label><input v-model="password" class="form-input" type="password" autocomplete="current-password" /></div>
        <button class="auth-btn" :disabled="loading">{{ loading ? '登录中...' : '登录' }}</button>
      </form>
      <div class="auth-footer">还没有账号？<router-link to="/register">立即注册</router-link></div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { postJson } from '../api/http'

const route = useRoute()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function submit() {
  loading.value = true
  error.value = ''
  try {
    await postJson('/api/auth/login', { username: username.value, password: password.value })
    location.href = route.query.next || '/'
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
