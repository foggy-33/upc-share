<template>
  <NavBar />
  <section class="forum-section">
    <div class="container">
      <div class="forum-toolbar">
        <div>
          <h1 class="section-title">资料论坛</h1>
          <p class="forum-subtitle">提问、补充资料说明，或者给同学留一点使用经验。</p>
        </div>
        <div class="forum-search">
          <span class="forum-search-icon">⌕</span>
          <input v-model="q" placeholder="搜索帖子或用户" @input="search" />
        </div>
      </div>

      <div v-if="me.logged_in" class="forum-composer">
        <textarea
          v-model="postContent"
          class="form-input"
          maxlength="1000"
          placeholder="写点什么..."
        ></textarea>
        <div class="forum-composer-bar">
          <span class="forum-counter">{{ postContent.length }}/1000</span>
          <button class="action-btn approve" :disabled="posting || !postContent.trim()" @click="createPost">
            发布
          </button>
        </div>
      </div>
      <div v-else class="forum-login-note">
        <router-link to="/login">登录</router-link> 后可以发帖和评论。
      </div>

      <div class="forum-list">
        <article v-for="post in posts" :key="post.id" class="forum-post-card">
          <div class="forum-post-top">
            <div>
              <strong>{{ post.username || '匿名用户' }}</strong>
              <div class="forum-post-meta">{{ formatTime(post.created_at) }}</div>
            </div>
            <button v-if="post.can_delete" class="action-btn delete" @click="deletePost(post.id)">删除</button>
          </div>
          <div class="forum-post-content">{{ post.content }}</div>

          <div class="forum-comments" v-if="post.comments?.length || me.logged_in">
            <div v-for="comment in post.comments" :key="comment.id" class="forum-comment-item">
              <div class="forum-comment-meta">
                <span>{{ comment.username || '匿名用户' }} · {{ formatTime(comment.created_at) }}</span>
                <button v-if="comment.can_delete" class="forum-comment-delete" @click="deleteComment(comment.id)">删除</button>
              </div>
              <div class="forum-comment-content">{{ comment.content }}</div>
            </div>

            <div v-if="me.logged_in" class="forum-comment-form">
              <input
                v-model="commentDrafts[post.id]"
                maxlength="500"
                placeholder="回复..."
                @keydown.enter.prevent="createComment(post.id)"
              />
              <button :disabled="!String(commentDrafts[post.id] || '').trim()" @click="createComment(post.id)">
                回复
              </button>
            </div>
          </div>
        </article>

        <div v-if="posts.length === 0" class="forum-empty">暂无帖子</div>
      </div>

      <div class="pagination" v-if="pages > 1">
        <button class="page-btn" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
        <button class="page-btn active">{{ page }} / {{ pages }}</button>
        <button class="page-btn" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import { api, postJson } from '../api/http'

const me = reactive({ logged_in: false, username: '', is_admin: false })
const posts = ref([])
const q = ref('')
const page = ref(1)
const pages = ref(0)
const postContent = ref('')
const posting = ref(false)
const commentDrafts = reactive({})
let timer = 0

onMounted(async () => {
  Object.assign(me, await api('/api/auth/me'))
  await load()
})

async function load() {
  const params = new URLSearchParams({ page: page.value, size: 20 })
  if (q.value.trim()) params.set('q', q.value.trim())
  const data = await api(`/api/forum/posts?${params}`)
  posts.value = data.items || []
  pages.value = data.pages || 0
}

function search() {
  clearTimeout(timer)
  timer = setTimeout(() => {
    page.value = 1
    load()
  }, 250)
}

async function createPost() {
  if (!postContent.value.trim()) return
  posting.value = true
  try {
    await postJson('/api/forum/posts', { content: postContent.value.trim() })
    postContent.value = ''
    page.value = 1
    await load()
  } finally {
    posting.value = false
  }
}

async function createComment(id) {
  const content = String(commentDrafts[id] || '').trim()
  if (!content) return
  await postJson(`/api/forum/posts/${id}/comments`, { content })
  commentDrafts[id] = ''
  await load()
}

async function deletePost(id) {
  await api(`/api/forum/posts/${id}`, { method: 'DELETE' })
  await load()
}

async function deleteComment(id) {
  await api(`/api/forum/comments/${id}`, { method: 'DELETE' })
  await load()
}

function go(next) {
  page.value = next
  load()
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}
</script>
