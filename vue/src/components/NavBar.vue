<template>
  <nav class="navbar">
    <div class="nav-container">
      <button ref="menuButton" type="button" class="mobile-menu-toggle" aria-label="展开网站导航" aria-controls="mobile-navigation" :aria-expanded="sidebarOpen" @click="openSidebar">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
      </button>
      <router-link to="/" class="nav-logo" @click="goHome">upcshare</router-link>
      <div class="nav-links">
        <router-link to="/" class="nav-link">资料库</router-link>
        <router-link to="/forum" class="nav-link">论坛</router-link>
        <router-link to="/courses" class="nav-link">公选课</router-link>
        <router-link to="/admin" class="nav-link">上传资料</router-link>
        <router-link v-if="me.is_admin" to="/dashboard" class="nav-link admin-link">管理后台</router-link>
        <router-link v-if="!me.logged_in" to="/login" class="nav-link">登录</router-link>
        <router-link v-if="!me.logged_in" to="/register" class="nav-link">注册</router-link>
      </div>
      <form class="mobile-top-search" role="search" @submit.prevent="submitSearch">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.5 4.5"/></svg>
        <input v-model="mobileSearch" type="search" :placeholder="searchPlaceholder" :aria-label="searchPlaceholder" />
        <button type="submit">搜索</button>
      </form>
    </div>
    <router-link
      v-if="me.logged_in"
      to="/profile"
      class="nav-avatar-link"
      aria-label="进入个人空间"
      :title="`${me.username} 的个人空间`"
    >
      <span class="nav-avatar">
        <img v-if="me.avatar_url" :src="me.avatar_url" alt="" @error="me.avatar_url = ''" />
        <span v-else>{{ avatarText }}</span>
      </span>
    </router-link>
  </nav>
  <button v-if="sidebarOpen" class="mobile-sidebar-backdrop" type="button" aria-label="关闭网站导航" tabindex="-1" @click="closeSidebar" />
  <aside v-show="sidebarOpen" id="mobile-navigation" ref="sidebar" class="mobile-site-sidebar" role="dialog" aria-modal="true" aria-label="网站导航" @keydown="handleSidebarKeydown" @click="handleSidebarClick">
    <button ref="closeButton" class="mobile-side-link mobile-sidebar-close" type="button" aria-label="收起网站导航" @click="closeSidebar">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg><span>收起</span>
    </button>
    <router-link to="/" class="mobile-sidebar-brand" aria-label="upcshare 首页" @click="goHome">U</router-link>
    <span class="mobile-route-indicator" role="status" :title="accessRoute.label" :aria-label="accessRoute.label">
      {{ accessRoute.mode === 'campus' ? '校' : accessRoute.mode === 'public' ? '公' : '···' }}
    </span>
    <div class="mobile-sidebar-links">
      <router-link to="/" class="mobile-side-link" aria-label="资料库">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6.5 12 4l8 2.5v11L12 20l-8-2.5z"/><path d="M12 4v16M4 6.5l8 2.5 8-2.5"/></svg><span>资料库</span>
      </router-link>
      <router-link to="/forum" class="mobile-side-link" aria-label="论坛">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v12H9l-5 3z"/><path d="M8 9h8M8 13h5"/></svg><span>论坛</span>
      </router-link>
      <router-link to="/courses" class="mobile-side-link" aria-label="公选课">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5c-2.5-1.7-5.2-1.8-8-1v14c2.8-.8 5.5-.7 8 1 2.5-1.7 5.2-1.8 8-1V4c-2.8-.8-5.5-.7-8 1z"/><path d="M12 5v14"/></svg><span>公选课</span>
      </router-link>
      <router-link to="/admin" class="mobile-side-link" aria-label="上传资料">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5"/><path d="M4 15v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/></svg><span>上传</span>
      </router-link>
      <router-link v-if="me.is_admin" to="/dashboard" class="mobile-side-link" aria-label="管理后台">
        <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="7" height="7" rx="1"/><rect x="13" y="4" width="7" height="7" rx="1"/><rect x="4" y="13" width="7" height="7" rx="1"/><rect x="13" y="13" width="7" height="7" rx="1"/></svg><span>后台</span>
      </router-link>
    </div>
    <div class="mobile-sidebar-account">
      <button type="button" class="mobile-side-link theme-toggle" :aria-pressed="isDark" :aria-label="isDark ? '切换浅色模式' : '切换深色模式'" @click="toggleTheme">
        <svg v-if="!isDark" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.5 13A8.5 8.5 0 0 1 11 3.5 8.5 8.5 0 1 0 20.5 13Z" /></svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></svg>
        <span>{{ isDark ? '浅色模式' : '深色模式' }}</span>
      </button>
      <router-link v-if="me.logged_in" to="/profile" class="mobile-side-link" aria-label="个人空间">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/></svg><span>我的</span>
      </router-link>
      <template v-else>
        <router-link to="/login" class="mobile-side-link" aria-label="登录">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 4H5v16h5M13 8l4 4-4 4M7 12h10"/></svg><span>登录</span>
        </router-link>
        <router-link to="/register" class="mobile-side-link" aria-label="注册">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3"/><path d="M3.5 20a5.5 5.5 0 0 1 11 0M18 8v8M14 12h8"/></svg><span>注册</span>
        </router-link>
      </template>
    </div>
  </aside>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentUser as me, loadCurrentUser } from '../authState'
import { accessRouteState as accessRoute } from '../accessRoute'
import { isDark, toggleTheme } from '../theme'

const route = useRoute()
const router = useRouter()
const emit = defineEmits(['home'])
const sidebarOpen = ref(false)
const menuButton = ref(null)
const closeButton = ref(null)
const sidebar = ref(null)
const avatarText = computed(() => String(me.username || 'U').trim().slice(0, 1).toUpperCase())
const mobileSearch = ref(String(route.query.q || ''))
const searchPath = computed(() => route.path.startsWith('/forum') ? '/forum' : route.path.startsWith('/courses') ? '/courses' : '/')
const searchPlaceholder = computed(() => searchPath.value === '/forum' ? '搜索论坛话题' : searchPath.value === '/courses' ? '搜索公选课' : '搜索资料')

watch(() => [route.path, route.query.q], () => { mobileSearch.value = String(route.query.q || '') })
watch(() => route.fullPath, () => closeSidebar(false))

async function openSidebar() {
  sidebarOpen.value = true
  document.documentElement.classList.add('mobile-nav-open')
  await nextTick()
  closeButton.value?.focus()
}

function closeSidebar(restoreFocus = true) {
  sidebarOpen.value = false
  document.documentElement.classList.remove('mobile-nav-open')
  if (restoreFocus) menuButton.value?.focus()
}

function handleSidebarClick(event) {
  if (event.target.closest('a')) closeSidebar()
}

function handleSidebarKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeSidebar()
  }
  if (event.key !== 'Tab') return
  const items = [...sidebar.value.querySelectorAll('a[href], button:not([disabled])')]
  const first = items[0]
  const last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}

function handleResize() {
  if (window.innerWidth > 768) closeSidebar(false)
}

function submitSearch() {
  const q = mobileSearch.value.trim()
  router.push({ path: searchPath.value, query: q ? { q } : {} })
}

function goHome() {
  mobileSearch.value = ''
  emit('home')
  window.scrollTo({ top: 0, behavior: 'auto' })
}

onMounted(loadCurrentUser)
onMounted(() => window.addEventListener('resize', handleResize))
onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  document.documentElement.classList.remove('mobile-nav-open')
})
</script>
