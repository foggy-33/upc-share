<template>
  <NavBar />
  <section class="forum-board">
    <button v-if="sidebarOpen" class="forum-drawer-shade" aria-label="关闭论坛导航" @click="sidebarOpen = false"></button>
    <aside class="forum-sidebar" :class="{ open: sidebarOpen }">
      <div class="forum-side-group">
        <button class="forum-side-item" :class="{ active: !activeSection }" @click="setSection('')">
          <span class="forum-side-icon">#</span><span>全部话题</span>
        </button>
        <button
          v-for="sectionName in sectionNames"
          :key="sectionName"
          class="forum-side-item"
          :class="{ active: activeSection === sectionName }"
          @click="setSection(sectionName)"
        >
          <span class="forum-side-icon">#</span><span>{{ sectionName }}</span>
        </button>
      </div>
    </aside>

    <div class="forum-main">
      <div class="forum-mobile-head">
        <button class="forum-menu-btn" aria-label="打开论坛导航" @click="sidebarOpen = true">☰</button>
        <span>论坛</span>
      </div>
      <header class="forum-hero">
        <h1>{{ activeSection || '论坛' }}</h1>
        <div class="forum-search forum-search-wide">
          <span class="forum-search-icon">⌕</span>
          <input v-model="q" placeholder="搜索标题、内容或用户名" @input="search" />
        </div>
      </header>

      <div class="forum-topic-bar">
        <div class="forum-topic-tabs">
          <button :class="{ active: mode === 'latest' }" @click="setMode('latest')">最新</button>
          <button :class="{ active: mode === 'hot' }" @click="setMode('hot')">热门</button>
        </div>
        <button class="forum-new-btn" @click="openComposer"><span>+</span><span>新建话题</span></button>
      </div>

      <div v-if="composerOpen && me.logged_in" class="forum-composer">
        <header class="forum-composer-head">
          <div>
            <strong>发布新话题</strong>
          </div>
          <button class="forum-composer-close" type="button" aria-label="收起发帖框" @click="composerOpen = false">×</button>
        </header>
        <div class="forum-composer-fields">
          <select v-model="postSection" class="form-input">
            <option value="" disabled>选择板块</option>
            <option v-for="sectionName in sectionNames" :key="sectionName" :value="sectionName">{{ sectionName }}</option>
          </select>
          <input v-model="postTitle" class="form-input" maxlength="80" placeholder="请输入帖子标题" />
        </div>
        <ForumRichEditor v-model="postContent" placeholder="分享你的想法，可点击图片按钮上传图片..." />
        <div class="forum-composer-bar">
          <span class="forum-draft-status">{{ draftStatus }}</span>
          <button class="action-btn" type="button" @click="clearDraft">清空</button>
          <span class="forum-counter">{{ postTitle.length }}/80 · {{ postContent.length }}/20000</span>
          <button class="action-btn approve" :disabled="posting || !postSection || !postTitle.trim() || !postContent.trim()" @click="createPost">发布</button>
        </div>
      </div>
      <div v-if="!me.logged_in && showLoginNote" class="forum-login-note">
        <router-link to="/login">登录</router-link> 后可以发帖和回复。
      </div>

      <section class="forum-topics">
        <div class="forum-list-head">
          <span>话题</span>
          <span>浏览</span>
          <span>回复</span>
          <span>点赞</span>
        </div>
        <div v-if="listLoading" class="forum-empty">正在加载帖子...</div>
        <div v-else-if="listError" class="forum-error">{{ listError }}</div>
        <template v-else>
          <article v-for="post in posts" :key="post.id" class="forum-topic-row">
            <div class="forum-topic-body">
              <div class="forum-topic-top">
                <span v-if="post.is_pinned" class="forum-pin-badge">置顶</span>
                <router-link class="forum-topic-title" :to="`/forum/posts/${post.id}`">{{ post.title || '无标题帖子' }}</router-link>
                <div v-if="post.can_pin || post.can_delete" class="forum-topic-admin-actions">
                  <button v-if="post.can_pin" class="action-btn" :class="post.is_pinned ? 'reject' : 'approve'" @click="setPinned(post)">
                    {{ post.is_pinned ? '取消置顶' : '置顶' }}
                  </button>
                  <button v-if="post.can_delete" class="action-btn delete" @click="deletePost(post.id)">删除</button>
                </div>
              </div>
              <div class="forum-topic-meta">
                <span>{{ post.section }}</span>
                <span class="level-username" :class="`level-${post.user_level || 'gray'}`">{{ post.username || '匿名用户' }}</span>
                <span class="user-level-badge" :class="`level-${post.user_level || 'gray'}`">{{ levelLabel(post.user_level) }}</span>
              </div>
            </div>
            <span class="forum-topic-stat" data-label="浏览">{{ post.view_count || 0 }}</span>
            <span class="forum-topic-stat" data-label="回复">{{ post.comment_count || 0 }}</span>
            <span class="forum-topic-stat forum-topic-likes" data-label="点赞">♥ {{ post.like_count || 0 }}</span>
          </article>
        </template>
        <div v-if="!listLoading && !listError && posts.length === 0" class="forum-empty">
          暂无帖子。
        </div>
      </section>

      <div class="pagination" v-if="pages > 1">
        <button class="page-btn" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
        <button class="page-btn active">{{ page }} / {{ pages }}</button>
        <button class="page-btn" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import NavBar from '../components/NavBar.vue'
import { api, postJson } from '../api/http'
import { currentUser as me, loadCurrentUser } from '../authState'

const ForumRichEditor = defineAsyncComponent(() => import('../components/ForumRichEditor.vue'))
const posts = ref([])
const mode = ref('latest')
const sections = ref([])
const sectionNames = computed(() => sections.value.map(item => item.name || item))
const activeSection = ref('')
const q = ref('')
const page = ref(1)
const pages = ref(0)
const postSection = ref('')
const postTitle = ref('')
const postContent = ref('')
const posting = ref(false)
const composerOpen = ref(false)
const sidebarOpen = ref(false)
const showLoginNote = computed(() => composerOpen.value)
const draftStatus = ref('')
const listLoading = ref(false)
const listError = ref('')
const DRAFT_KEY = 'upcshare-forum-post-draft'
let timer = 0
let draftTimer = 0
let loadSequence = 0

onMounted(async () => {
  restoreDraft()
  await loadCurrentUser()
  await loadSections()
  await load()
})

onBeforeUnmount(() => {
  clearTimeout(timer)
  clearTimeout(draftTimer)
})

watch([postSection, postTitle, postContent], saveDraft, { flush: 'post' })

async function loadSections() {
  try {
    sections.value = await api('/api/forum/sections')
    const names = sectionNames.value
    if (activeSection.value && !names.includes(activeSection.value)) activeSection.value = ''
    if (postSection.value && !names.includes(postSection.value)) postSection.value = ''
  } catch {
    sections.value = []
  }
}

async function load() {
  const sequence = ++loadSequence
  listLoading.value = true
  listError.value = ''
  const params = new URLSearchParams({ page: page.value, size: 20 })
  if (q.value.trim()) params.set('q', q.value.trim())
  if (activeSection.value) params.set('section', activeSection.value)
  if (mode.value === 'hot') params.set('sort', 'hot')
  try {
    const data = await api(`/api/forum/posts?${params}`)
    if (sequence !== loadSequence) return
    posts.value = data.items || []
    pages.value = data.pages || 0
  } catch (e) {
    if (sequence !== loadSequence) return
    listError.value = e.message || '帖子加载失败，请稍后重试'
  } finally {
    if (sequence === loadSequence) listLoading.value = false
  }
}

function setMode(next) {
  mode.value = next
  page.value = 1
  sidebarOpen.value = false
  load()
}

function setSection(next) {
  activeSection.value = next
  mode.value = 'latest'
  page.value = 1
  sidebarOpen.value = false
  load()
}

function openComposer() {
  sidebarOpen.value = false
  composerOpen.value = true
  if (!me.logged_in) return
  setTimeout(() => document.querySelector('.forum-composer input')?.focus(), 0)
}

function restoreDraft() {
  try {
    const draft = JSON.parse(localStorage.getItem(DRAFT_KEY) || '{}')
    postSection.value = draft.section || ''
    postTitle.value = draft.title || ''
    postContent.value = draft.content || ''
    if (postTitle.value || postContent.value) draftStatus.value = '已恢复上次草稿'
  } catch {
    localStorage.removeItem(DRAFT_KEY)
  }
}

function saveDraft() {
  clearTimeout(draftTimer)
  draftTimer = setTimeout(() => {
    const hasDraft = postSection.value || postTitle.value.trim() || postContent.value.trim()
    if (!hasDraft) {
      localStorage.removeItem(DRAFT_KEY)
      draftStatus.value = ''
      return
    }
    localStorage.setItem(DRAFT_KEY, JSON.stringify({
      section: postSection.value,
      title: postTitle.value,
      content: postContent.value
    }))
    draftStatus.value = '草稿已自动保存'
  }, 500)
}

function clearDraft() {
  if ((postTitle.value || postContent.value) && !confirm('确认清空当前帖子草稿？')) return
  postSection.value = ''
  postTitle.value = ''
  postContent.value = ''
  localStorage.removeItem(DRAFT_KEY)
  draftStatus.value = ''
}

function search() {
  clearTimeout(timer)
  timer = setTimeout(() => {
    mode.value = 'latest'
    page.value = 1
    load()
  }, 250)
}

async function createPost() {
  if (!postSection.value || !postTitle.value.trim() || !postContent.value.trim()) return
  posting.value = true
  try {
    const createdSection = postSection.value
    await postJson('/api/forum/posts', {
      section: createdSection,
      title: postTitle.value.trim(),
      content: postContent.value.trim()
    })
    postSection.value = ''
    postTitle.value = ''
    postContent.value = ''
    localStorage.removeItem(DRAFT_KEY)
    draftStatus.value = ''
    composerOpen.value = false
    activeSection.value = createdSection
    mode.value = 'latest'
    page.value = 1
    await loadSections()
    await load()
  } finally {
    posting.value = false
  }
}

async function deletePost(id) {
  await api(`/api/forum/posts/${id}`, { method: 'DELETE' })
  await load()
}

async function setPinned(post) {
  await api(`/api/forum/posts/${post.id}/pin?pinned=${!post.is_pinned}`, { method: 'POST' })
  await load()
}

function go(next) {
  page.value = next
  load()
}

function levelLabel(level) {
  const labels = {
    gray: '新人',
    blue: '正式用户',
    green: '贡献者',
    yellow: '活跃达人',
    orange: '社区之星',
    admin: '管理员'
  }
  return labels[level] || labels.gray
}
</script>
