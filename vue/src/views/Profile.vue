<template>
  <NavBar />
  <section class="profile-section">
    <div class="container profile-shell">
      <div v-if="loading" class="profile-loading">加载中...</div>
      <div v-else-if="error" class="profile-error">
        <strong>{{ error }}</strong>
        <router-link v-if="!profile.uid" to="/login" class="action-btn approve">去登录</router-link>
      </div>
      <template v-else>
        <header class="profile-head">
          <div class="profile-identity">
            <button
              class="profile-avatar"
              type="button"
              :disabled="uploadingAvatar"
              title="更换头像"
              aria-label="更换头像"
              @click="avatarInput?.click()"
            >
              <img v-if="avatarUrl" :src="avatarUrl" alt="" @error="profile.avatar_url = ''" />
              <span v-else>{{ avatarText }}</span>
            </button>
            <input
              ref="avatarInput"
              class="profile-avatar-input"
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif"
              @change="uploadAvatar"
            />
            <div>
              <h1 class="level-username" :class="`level-${profile.user_level || 'gray'}`">{{ profile.username }}</h1>
              <div v-if="avatarError" class="profile-avatar-error">{{ avatarError }}</div>
            </div>
          </div>
          <button class="profile-logout" :disabled="loggingOut" @click="logout">
            {{ loggingOut ? '退出中...' : '退出登录' }}
          </button>
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
          <div class="profile-fact profile-points">
            <span>积分</span>
            <strong>{{ formatPoints(profile.points) }}</strong>
            <small>上传/发帖 +1，回复 +0.5，下载 +0.1</small>
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
            <div>
              <h2>我发过的帖子</h2>
              <p>论坛里的发言会在这里归档。</p>
            </div>
            <router-link to="/forum" class="action-btn">去论坛</router-link>
          </div>

          <div class="profile-post-list">
            <article v-for="post in posts" :key="post.id" class="profile-post">
              <router-link class="profile-post-title" :to="`/forum/posts/${post.id}`">{{ post.title || postTitle(post.content) }}</router-link>
              <div class="profile-post-stats">
                <span>{{ post.view_count || 0 }} 浏览</span>
                <span>{{ post.comment_count || 0 }} 回复</span>
              </div>
            </article>
            <div v-if="posts.length === 0" class="profile-empty">还没有发过帖子。</div>
          </div>

          <div class="pagination" v-if="pages > 1">
            <button class="page-btn" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
            <button class="page-btn active">{{ page }} / {{ pages }}</button>
            <button class="page-btn" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
          </div>
        </section>
        <ProfileImageAlbum :uid="profile.uid" title="我的相册" />
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import ProfileImageAlbum from '../components/ProfileImageAlbum.vue'
import { api } from '../api/http'

const profile = reactive({})
const posts = ref([])
const page = ref(1)
const pages = ref(0)
const loading = ref(true)
const loggingOut = ref(false)
const uploadingAvatar = ref(false)
const error = ref('')
const avatarError = ref('')
const avatarInput = ref(null)
const avatarText = computed(() => String(profile.username || 'U').trim().slice(0, 1).toUpperCase())
const avatarUrl = computed(() => profile.avatar_url || '')

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    Object.assign(profile, await api('/api/auth/profile'))
    await loadPosts()
  } catch (e) {
    error.value = e.message || '个人空间加载失败'
  } finally {
    loading.value = false
  }
}

async function loadPosts() {
  const data = await api(`/api/forum/mine?page=${page.value}&size=10`)
  posts.value = data.items || []
  pages.value = Number(data.pages || 0)
}

async function go(next) {
  if (next < 1 || next > pages.value) return
  page.value = next
  await loadPosts()
}

async function logout() {
  loggingOut.value = true
  try {
    await api('/api/auth/logout', { method: 'POST' })
    location.href = '/'
  } finally {
    loggingOut.value = false
  }
}

async function uploadAvatar(event) {
  const file = event.target.files?.[0]
  if (!file) return
  uploadingAvatar.value = true
  avatarError.value = ''
  try {
    const body = new FormData()
    body.append('avatar', file)
    const data = await api('/api/auth/avatar', { method: 'POST', body })
    Object.assign(profile, data)
  } catch (e) {
    avatarError.value = e.message || '头像上传失败'
  } finally {
    uploadingAvatar.value = false
    event.target.value = ''
  }
}

function postTitle(content) {
  const text = String(content || '').trim()
  if (!text) return '无标题帖子'
  return text.split(/\r?\n/).find(Boolean) || text
}

function formatTime(value) {
  const text = String(value || '').trim()
  return text ? text.replace('T', ' ').slice(0, 19) : '-'
}

function formatPoints(value) {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? n.toFixed(1) : '0.0'
}

</script>
