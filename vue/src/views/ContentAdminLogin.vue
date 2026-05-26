<template>
  <section class="auth-page">
    <div class="auth-shell">
      <div class="auth-card">
        <h1>内容管理员登录</h1>
        <p v-if="error" class="auth-message error">{{ error }}</p>
        <div class="auth-form">
          <label>
            <span>账号</span>
            <input v-model.trim="username" autocomplete="username" placeholder="输入内容管理员账号" />
          </label>
          <label>
            <span>密码</span>
            <input v-model="password" autocomplete="current-password" type="password" placeholder="输入密码" @keydown.enter="submit" />
          </label>
          <button class="primary-btn" :disabled="loading || !canSubmit" @click="submit">{{ loading ? '登录中...' : '进入内容后台' }}</button>
        </div>
        <div class="auth-links">
          <router-link to="/login">普通用户登录</router-link>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { postJson, api } from '../api/http'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const canSubmit = computed(() => username.value.length >= 2 && password.value.length >= 6)

async function submit() {
  if (!canSubmit.value || loading.value) return
  loading.value = true
  error.value = ''
  try {
    await postJson('/api/auth/login', { username: username.value, password: password.value })
    await api('/api/content-admin/me')
    location.href = '/content-admin'
  } catch (e) {
    error.value = e.message || '内容管理员登录失败'
  } finally {
    loading.value = false
  }
}
</script>
