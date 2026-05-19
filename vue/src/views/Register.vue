<template>
  <div class="auth-page">
    <div class="auth-card">
      <h1 class="auth-title">创建账号</h1>
      <p class="auth-subtitle">注册后可上传和下载学科资料</p>
      <div v-if="error" class="auth-error" style="display:block">{{ error }}</div>
      <form class="auth-form" @submit.prevent="submit">
        <div class="form-group"><label class="form-label">用户名</label><input v-model="username" class="form-input" autocomplete="username" /></div>
        <div class="form-group"><label class="form-label">密码</label><input v-model="password" class="form-input" type="password" autocomplete="new-password" /></div>
        <div class="form-group"><label class="form-label">确认密码</label><input v-model="confirm" class="form-input" type="password" autocomplete="new-password" /></div>
        <button class="auth-btn" :disabled="loading">{{ loading ? '注册中...' : '注册' }}</button>
      </form>
      <div class="auth-footer">已有账号？<router-link to="/login">返回登录</router-link></div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { postJson } from '../api/http'

const username = ref('')
const password = ref('')
const confirm = ref('')
const error = ref('')
const loading = ref(false)

async function submit() {
  if (password.value !== confirm.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await postJson('/api/auth/register', { username: username.value, password: password.value })
    location.href = '/login?registered=1'
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
