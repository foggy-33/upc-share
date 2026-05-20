<template>
  <NavBar />
  <main class="auth-page">
    <section class="auth-shell">
      <div class="auth-panel auth-card">
        <div class="auth-card-head">
          <h2>创建账号</h2>
          <p>用户名 2-20 位，密码至少 6 位</p>
        </div>

        <p v-if="error" class="auth-message error">{{ error }}</p>

        <form class="auth-form" @submit.prevent="submit">
          <label class="auth-field">
            <span>用户名</span>
            <input v-model.trim="username" autocomplete="username" placeholder="设置用户名" />
          </label>
          <label class="auth-field">
            <span>密码</span>
            <input v-model="password" type="password" autocomplete="new-password" placeholder="设置密码" />
          </label>
          <label class="auth-field">
            <span>确认密码</span>
            <input v-model="confirm" type="password" autocomplete="new-password" placeholder="再次输入密码" />
          </label>
          <button class="auth-btn" :disabled="loading || !canSubmit">
            {{ loading ? '注册中...' : '注册' }}
          </button>
        </form>

        <div class="auth-footer">
          已有账号？
          <router-link to="/login">返回登录</router-link>
        </div>
      </div>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import { postJson } from '../api/http'

const username = ref('')
const password = ref('')
const confirm = ref('')
const error = ref('')
const loading = ref(false)
const canSubmit = computed(() => username.value.length >= 2 && username.value.length <= 20 && password.value.length >= 6 && confirm.value.length >= 6)

async function submit() {
  if (username.value.length < 2 || username.value.length > 20) {
    error.value = '用户名长度需要在 2-20 位之间'
    return
  }
  if (password.value.length < 6) {
    error.value = '密码至少需要 6 位'
    return
  }
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
