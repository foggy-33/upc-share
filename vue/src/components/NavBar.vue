<template>
  <nav class="navbar">
    <div class="nav-container">
      <router-link to="/" class="nav-logo">upcshare</router-link>
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
  <aside class="mobile-site-sidebar" aria-label="网站导航">
    <router-link to="/" class="mobile-sidebar-brand" aria-label="upcshare 首页">U</router-link>
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentUser as me, loadCurrentUser } from '../authState'
import { accessRouteState as accessRoute } from '../accessRoute'

const route = useRoute()
const router = useRouter()
const avatarText = computed(() => String(me.username || 'U').trim().slice(0, 1).toUpperCase())
const mobileSearch = ref(String(route.query.q || ''))
const searchPath = computed(() => route.path.startsWith('/forum') ? '/forum' : route.path.startsWith('/courses') ? '/courses' : '/')
const searchPlaceholder = computed(() => searchPath.value === '/forum' ? '搜索论坛话题' : searchPath.value === '/courses' ? '搜索公选课' : '搜索资料')

watch(() => [route.path, route.query.q], () => { mobileSearch.value = String(route.query.q || '') })

function submitSearch() {
  const q = mobileSearch.value.trim()
  router.push({ path: searchPath.value, query: q ? { q } : {} })
}

onMounted(loadCurrentUser)
</script>
