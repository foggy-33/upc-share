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
            <small>上传文件每被下载一次 +0.1</small>
            <button type="button" class="profile-points-link" :aria-expanded="showUploadPoints" aria-controls="upload-point-details" @click="toggleUploadPoints">{{ showUploadPoints ? '收起积分明细' : '查看积分明细 →' }}</button>
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

        <section v-if="showUploadPoints" id="upload-point-details" class="profile-posts upload-point-details">
          <div class="profile-posts-head">
            <div><h2>积分明细 · 上传文件</h2><p>已审核文件每被下载一次，上传者获得 0.1 积分。</p></div>
            <button class="action-btn" :disabled="uploadPointsLoading" @click="loadUploadPoints(uploadPage)">刷新</button>
          </div>
          <p class="upload-points-summary">文件 {{ uploadSummary.file_count || 0 }} 个 · 被下载 {{ uploadSummary.download_count || 0 }} 次 · 已计分 {{ uploadSummary.rewarded_download_count || 0 }} 次 · 获得 {{ formatPoints(uploadSummary.earned_points) }} 积分</p>
          <p class="upload-points-note">奖励从本功能上线后开始累计，历史下载不补发。下载次数按系统记录的下载请求统计；此处汇总当前仍保留的上传文件。</p>
          <p v-if="uploadPointsError" class="profile-avatar-error" role="alert">{{ uploadPointsError }} <button class="action-btn" :disabled="uploadPointsLoading" @click="loadUploadPoints(uploadRequestedPage)">重试</button></p>
          <p v-if="uploadPointsLoading" class="profile-empty" role="status">加载中...</p>
          <template v-if="uploadPointsLoaded">
            <div class="file-table-wrap">
              <table class="file-table upload-points-table">
                <thead><tr><th>文件</th><th>状态</th><th>下载次数</th><th>已计分次数</th><th>获得积分</th></tr></thead>
                <tbody>
                  <tr v-for="file in uploadedFiles" :key="file.id">
                    <td>{{ file.original_name }}</td>
                    <td>{{ {approved: '已通过', pending: '待审核', rejected: '未通过'}[file.status] || file.status }}</td>
                    <td>{{ file.download_count || 0 }}</td><td>{{ file.rewarded_download_count || 0 }}</td>
                    <td>+{{ formatPoints(file.earned_points) }}</td>
                  </tr>
                  <tr v-if="!uploadedFiles.length"><td colspan="5">还没有上传文件。</td></tr>
                </tbody>
              </table>
            </div>
            <div v-if="uploadPages > 1" class="pagination">
              <button class="page-btn" :disabled="uploadPointsLoading || uploadPage <= 1" @click="goUploadPage(uploadPage - 1)">上一页</button>
              <span>{{ uploadPage }} / {{ uploadPages }}</span>
              <button class="page-btn" :disabled="uploadPointsLoading || uploadPage >= uploadPages" @click="goUploadPage(uploadPage + 1)">下一页</button>
            </div>
          </template>
        </section>

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
        <ProfileImageAlbum :uid="profile.uid" title="我的相册" can-delete can-upload />
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
const uploadedFiles = ref([])
const showUploadPoints = ref(false)
const uploadPointsLoaded = ref(false)
const uploadRequestedPage = ref(1)
const uploadSummary = ref({})
const uploadPage = ref(1)
const uploadPages = ref(0)
const uploadPointsLoading = ref(false)
const uploadPointsError = ref('')
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

async function toggleUploadPoints() {
  showUploadPoints.value = !showUploadPoints.value
  if (showUploadPoints.value && !uploadPointsLoaded.value) await loadUploadPoints(1)
}

async function loadUploadPoints(next = uploadPage.value) {
  if (uploadPointsLoading.value) return
  uploadRequestedPage.value = next
  uploadPointsLoading.value = true
  uploadPointsError.value = ''
  try {
    let data = await api(`/api/auth/profile/upload-points?page=${next}&size=10`)
    const lastPage = Math.max(1, Number(data.pages || 0))
    if (next > lastPage) {
      next = lastPage
      data = await api(`/api/auth/profile/upload-points?page=${next}&size=10`)
    }
    uploadedFiles.value = data.items || []
    uploadSummary.value = data.summary || {}
    uploadPages.value = Number(data.pages || 0)
    uploadPage.value = Number(data.page || next)
    uploadPointsLoaded.value = true
  } catch (e) {
    uploadPointsError.value = e.message || '积分明细加载失败'
  } finally {
    uploadPointsLoading.value = false
  }
}

async function goUploadPage(next) {
  if (uploadPointsLoading.value || next < 1 || next > uploadPages.value) return
  await loadUploadPoints(next)
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
