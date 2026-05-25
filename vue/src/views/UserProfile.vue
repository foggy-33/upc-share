<template>
  <NavBar />
  <section class="profile-section">
    <div class="container profile-shell">
      <div v-if="loading" class="profile-loading">加载中...</div>
      <div v-else-if="error" class="profile-error">{{ error }}</div>
      <template v-else>
        <header class="profile-head">
          <div class="profile-identity">
            <div class="profile-avatar">
              <img v-if="profile.avatar_url" :src="profile.avatar_url" alt="" />
              <span v-else>{{ avatarText }}</span>
            </div>
            <div>
              <h1 class="level-username" :class="`level-${profile.user_level || 'gray'}`">{{ profile.username }}</h1>
              <div class="profile-role-row">
                <div class="profile-role">{{ profile.is_admin ? '管理员' : '注册用户' }}</div>
                <span class="user-level-badge" :class="`level-${profile.user_level || 'gray'}`">{{ levelLabel(profile.user_level) }}</span>
              </div>
            </div>
          </div>
        </header>

        <div class="profile-facts">
          <div class="profile-fact">
            <span>UID</span>
            <strong>{{ profile.uid }}</strong>
          </div>
          <div class="profile-fact">
            <span>注册时间</span>
            <strong>{{ formatTime(profile.created_at) }}</strong>
          </div>
          <div class="profile-fact">
            <span>发帖数</span>
            <strong>{{ profile.post_count ?? 0 }}</strong>
          </div>
          <div class="profile-fact">
            <span>下载次数</span>
            <strong>{{ profile.download_count ?? 0 }}</strong>
          </div>
          <div class="profile-fact">
            <span>下载总量</span>
            <strong>{{ profile.download_size || '0 B' }}</strong>
          </div>
        </div>

        <section class="profile-posts">
          <div class="profile-posts-head">
            <h2>TA 发过的帖子</h2>
          </div>
          <div class="profile-post-list">
            <article v-for="post in posts" :key="post.id" class="profile-post">
              <router-link class="profile-post-title" :to="`/forum/posts/${post.id}`">{{ post.title || '无标题帖子' }}</router-link>
            </article>
            <div v-if="posts.length === 0" class="profile-empty">还没有发过帖子。</div>
          </div>

          <div class="pagination" v-if="pages > 1">
            <button class="page-btn" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
            <button class="page-btn active">{{ page }} / {{ pages }}</button>
            <button class="page-btn" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
          </div>
        </section>
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import NavBar from '../components/NavBar.vue'
import { api } from '../api/http'

const route = useRoute()
const profile = reactive({})
const posts = ref([])
const page = ref(1)
const pages = ref(0)
const loading = ref(true)
const error = ref('')
const avatarText = computed(() => String(profile.username || 'U').trim().slice(0, 1).toUpperCase())

onMounted(load)
watch(() => route.params.uid, () => {
  page.value = 1
  load()
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    Object.assign(profile, await api(`/api/auth/users/${route.params.uid}`))
    await loadPosts()
  } catch (e) {
    error.value = e.message || '个人主页加载失败'
  } finally {
    loading.value = false
  }
}

async function loadPosts() {
  const data = await api(`/api/forum/users/${route.params.uid}/posts?page=${page.value}&size=10`)
  posts.value = data.items || []
  pages.value = Number(data.pages || 0)
}

async function go(next) {
  if (next < 1 || next > pages.value) return
  page.value = next
  await loadPosts()
}

function formatTime(value) {
  const text = String(value || '').trim()
  return text ? text.replace('T', ' ').slice(0, 19) : '-'
}

function levelLabel(level) {
  const labels = {
    gray: '刚注册',
    blue: '正式用户',
    green: '贡献者',
    yellow: '待定',
    orange: '待定',
    admin: '管理员'
  }
  return labels[level] || labels.gray
}
</script>
