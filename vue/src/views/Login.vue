<template>
  <NavBar />
  <main class="auth-page">
    <section class="auth-shell">
      <div class="auth-panel auth-card">
        <div class="auth-card-head">
          <h2>登录账号</h2>
          <p>使用你的 upcshare 账号进入</p>
        </div>

        <p v-if="notice" class="auth-message success">{{ notice }}</p>
        <p v-if="error" class="auth-message error">{{ error }}</p>

        <form class="auth-form" @submit.prevent="submit">
          <label class="auth-field">
            <span>用户名</span>
            <input v-model.trim="username" autocomplete="username" placeholder="输入用户名" />
          </label>
          <label class="auth-field">
            <span>密码</span>
            <input v-model="password" type="password" autocomplete="current-password" placeholder="输入密码" />
          </label>
          <button class="auth-btn" :disabled="loading || !canSubmit">
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </form>

        <div class="auth-footer">
          还没有账号？
          <router-link to="/register">立即注册</router-link>
        </div>
      </div>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import NavBar from '../components/NavBar.vue'
import { postJson } from '../api/http'

const route = useRoute()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const notice = computed(() => route.query.registered ? '注册成功，请登录。' : '')
const canSubmit = computed(() => username.value.length >= 2 && password.value.length >= 6)

async function submit() {
  if (!canSubmit.value) {
    error.value = '请输入正确的用户名和密码'
    return
  }
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
