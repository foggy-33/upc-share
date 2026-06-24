<template>
  <NavBar />
  <section class="forum-detail-page">
    <div class="container forum-detail-shell">
      <router-link class="forum-back-link" to="/forum">返回论坛</router-link>

      <div v-if="loading" class="forum-loading">加载中...</div>
      <div v-else-if="error" class="forum-error">{{ error }}</div>
      <template v-else>
        <article class="forum-thread">
          <header class="forum-thread-head">
            <div>
              <div class="forum-topic-meta">
                <span>{{ post.section || '灌水区' }}</span>
                <span>{{ post.view_count || 0 }} 浏览</span>
                <span>{{ post.comments?.length || 0 }} 回复</span>
                <span>{{ post.like_count || 0 }} 点赞</span>
              </div>
              <h1>{{ post.title || '无标题帖子' }}</h1>
            </div>
            <div class="forum-detail-actions">
              <button class="forum-like-btn" :class="{ liked: post.liked_by_me }" :disabled="likeBusy === `post-${post.id}`" @click="togglePostLike">
                <span>♥</span>{{ post.liked_by_me ? '已赞' : '点赞' }} {{ post.like_count || 0 }}
              </button>
              <span v-if="post.is_pinned" class="forum-pin-badge">置顶</span>
              <button v-if="post.can_pin" class="action-btn" :class="post.is_pinned ? 'reject' : 'approve'" @click="setPinned">
                {{ post.is_pinned ? '取消置顶' : '置顶' }}
              </button>
              <button v-if="post.can_delete" class="action-btn delete" @click="deletePost">删除</button>
            </div>
          </header>
          <div v-if="likeError" class="forum-action-error">{{ likeError }}</div>

          <section class="forum-thread-post">
            <router-link class="forum-thread-avatar" :to="`/users/${post.user_id}`" aria-label="查看个人主页">
              <img v-if="post.avatar_url" :src="post.avatar_url" alt="" @error="post.avatar_url = ''" />
              <span v-else>{{ avatarText(post.username) }}</span>
            </router-link>
            <div class="forum-thread-body">
              <div class="forum-thread-author">
                <router-link class="level-username" :class="`level-${post.user_level || 'gray'}`" :to="`/users/${post.user_id}`">{{ post.username || '匿名用户' }}</router-link>
                <span class="user-level-badge" :class="`level-${post.user_level || 'gray'}`">{{ levelLabel(post.user_level) }}</span>
                <span>{{ formatTime(post.created_at) }}</span>
              </div>
              <ForumContentViewer class="forum-detail-content" :content="post.content" />
            </div>
          </section>

          <div class="forum-comment-sort">
            <strong>全部回复</strong>
            <div>
              <button :class="{ active: commentSort === 'latest' }" @click="setCommentSort('latest')">最新</button>
              <button :class="{ active: commentSort === 'hot' }" @click="setCommentSort('hot')">热门</button>
            </div>
          </div>

          <section v-for="comment in threadedComments" :key="comment.id" class="forum-comment-thread">
            <div class="forum-thread-post forum-comment-row">
              <router-link class="forum-thread-avatar" :to="`/users/${comment.user_id}`" aria-label="查看个人主页">
                <img v-if="comment.avatar_url" :src="comment.avatar_url" alt="" @error="comment.avatar_url = ''" />
                <span v-else>{{ avatarText(comment.username) }}</span>
              </router-link>
              <div class="forum-thread-body">
                <div class="forum-thread-author">
                  <router-link class="level-username" :class="`level-${comment.user_level || 'gray'}`" :to="`/users/${comment.user_id}`">{{ comment.username || '匿名用户' }}</router-link>
                  <span class="user-level-badge" :class="`level-${comment.user_level || 'gray'}`">{{ levelLabel(comment.user_level) }}</span>
                  <span>{{ formatTime(comment.created_at) }}</span>
                  <button v-if="comment.can_delete" class="forum-comment-delete" @click="deleteComment(comment.id)">删除</button>
                </div>
                <ForumContentViewer class="forum-detail-content forum-comment-content-viewer" :content="comment.content" />
                <div class="forum-comment-actions">
                  <button
                    class="forum-comment-like"
                    :class="{ liked: comment.liked_by_me }"
                    :disabled="likeBusy === `comment-${comment.id}`"
                    @click="toggleCommentLike(comment)"
                  >♥ {{ comment.like_count || 0 }}</button>
                  <button class="forum-comment-reply-btn" @click="replyTo(comment)">回复</button>
                </div>
              </div>
            </div>
            <div v-if="comment.replies?.length" class="forum-nested-replies">
              <div v-for="reply in comment.replies" :key="reply.id" class="forum-thread-post forum-comment-row forum-reply-row">
                <router-link class="forum-thread-avatar" :to="`/users/${reply.user_id}`" aria-label="查看个人主页">
                  <img v-if="reply.avatar_url" :src="reply.avatar_url" alt="" @error="reply.avatar_url = ''" />
                  <span v-else>{{ avatarText(reply.username) }}</span>
                </router-link>
                <div class="forum-thread-body">
                  <div class="forum-thread-author">
                    <router-link class="level-username" :class="`level-${reply.user_level || 'gray'}`" :to="`/users/${reply.user_id}`">{{ reply.username || '匿名用户' }}</router-link>
                    <span class="user-level-badge" :class="`level-${reply.user_level || 'gray'}`">{{ levelLabel(reply.user_level) }}</span>
                    <span v-if="reply.parent_username" class="forum-reply-context">回复 @{{ reply.parent_username }}</span>
                    <span>{{ formatTime(reply.created_at) }}</span>
                    <button v-if="reply.can_delete" class="forum-comment-delete" @click="deleteComment(reply.id)">删除</button>
                  </div>
                  <ForumContentViewer class="forum-detail-content forum-comment-content-viewer" :content="reply.content" />
                  <div class="forum-comment-actions">
                    <button
                      class="forum-comment-like"
                      :class="{ liked: reply.liked_by_me }"
                      :disabled="likeBusy === `comment-${reply.id}`"
                      @click="toggleCommentLike(reply)"
                    >♥ {{ reply.like_count || 0 }}</button>
                    <button class="forum-comment-reply-btn" @click="replyTo(reply)">回复</button>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section class="forum-thread-reply">
            <div v-if="!post.comments?.length" class="forum-comment-empty">还没有回复。</div>
            <div v-if="me.logged_in" class="forum-comment-composer">
              <div v-if="replyTarget" class="forum-reply-target">
                正在回复 @{{ replyTarget.username || '匿名用户' }}
                <button type="button" @click="cancelReply">取消</button>
              </div>
              <ForumRichEditor
                v-model="commentDraft"
                compact
                height="300px"
                :placeholder="commentPlaceholder"
              />
              <div class="forum-comment-submit">
                <span>{{ commentDraft.length }}/5000</span>
                <button :disabled="commentSubmitting || !commentDraft.trim() || commentDraft.length > 5000" @click="createComment">
                  {{ commentSubmitting ? '回复中...' : '回复' }}
                </button>
              </div>
            </div>
            <div v-else class="forum-inline-login"><router-link to="/login">登录</router-link> 后回复</div>
          </section>
        </article>
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavBar from '../components/NavBar.vue'
import ForumContentViewer from '../components/ForumContentViewer.vue'
import { api, postJson } from '../api/http'
import { currentUser as me, loadCurrentUser } from '../authState'

const ForumRichEditor = defineAsyncComponent(() => import('../components/ForumRichEditor.vue'))
const route = useRoute()
const router = useRouter()
const post = reactive({ comments: [] })
const loading = ref(true)
const error = ref('')
const commentDraft = ref('')
const commentSubmitting = ref(false)
const commentSort = ref('latest')
const likeBusy = ref('')
const likeError = ref('')
const replyTarget = ref(null)

const threadedComments = computed(() => {
  const comments = Array.isArray(post.comments) ? post.comments : []
  const copies = new Map()
  comments.forEach((comment) => {
    copies.set(Number(comment.id), { ...comment, replies: [] })
  })
  const roots = []
  comments.forEach((comment) => {
    const item = copies.get(Number(comment.id))
    const parentId = Number(comment.parent_comment_id || 0)
    const parent = copies.get(parentId)
    const threadParent = parent?.parent_comment_id ? copies.get(Number(parent.parent_comment_id)) : parent
    if (threadParent) {
      threadParent.replies.push(item)
    } else {
      roots.push(item)
    }
  })
  return roots
})

const commentPlaceholder = computed(() => {
  if (replyTarget.value) return `回复 @${replyTarget.value.username || '匿名用户'}，可粘贴或点击图片按钮插入图片...`
  return '回复这个话题，可粘贴或点击图片按钮插入图片...'
})

onMounted(async () => {
  await Promise.allSettled([loadCurrentUser(), load()])
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    Object.assign(post, await api(`/api/forum/posts/${route.params.id}?comment_sort=${commentSort.value}`))
  } catch (e) {
    error.value = e.message || '帖子加载失败'
  } finally {
    loading.value = false
  }
}

async function createComment() {
  const content = commentDraft.value.trim()
  if (!content || content.length > 5000 || commentSubmitting.value) return
  commentSubmitting.value = true
  try {
    await postJson(`/api/forum/posts/${route.params.id}/comments`, {
      content,
      parent_id: replyTarget.value?.id || null
    })
    commentDraft.value = ''
    replyTarget.value = null
    await load()
  } finally {
    commentSubmitting.value = false
  }
}

async function deletePost() {
  await api(`/api/forum/posts/${route.params.id}`, { method: 'DELETE' })
  router.push('/forum')
}

async function setPinned() {
  await api(`/api/forum/posts/${route.params.id}/pin?pinned=${!post.is_pinned}`, { method: 'POST' })
  await load()
}

async function setCommentSort(sort) {
  if (sort === commentSort.value) return
  commentSort.value = sort
  await load()
}

async function togglePostLike() {
  if (!post.id || likeBusy.value) return
  likeBusy.value = `post-${post.id}`
  likeError.value = ''
  try {
    const data = await api(`/api/forum/posts/${post.id}/like`, { method: 'POST' })
    post.liked_by_me = data.liked
    post.like_count = data.like_count
  } catch (e) {
    likeError.value = e.message || '点赞失败'
  } finally {
    likeBusy.value = ''
  }
}

async function toggleCommentLike(comment) {
  if (!comment?.id || likeBusy.value) return
  likeBusy.value = `comment-${comment.id}`
  likeError.value = ''
  try {
    const data = await api(`/api/forum/comments/${comment.id}/like`, { method: 'POST' })
    updateCommentLike(comment.id, data)
    if (commentSort.value === 'hot') {
      post.comments.sort((a, b) => (b.like_count || 0) - (a.like_count || 0) || b.id - a.id)
    }
  } catch (e) {
    likeError.value = e.message || '点赞失败'
  } finally {
    likeBusy.value = ''
  }
}

function updateCommentLike(commentId, data) {
  const target = post.comments.find((item) => Number(item.id) === Number(commentId))
  if (target) {
    target.liked_by_me = data.liked
    target.like_count = data.like_count
  }
}

async function deleteComment(id) {
  await api(`/api/forum/comments/${id}`, { method: 'DELETE' })
  await load()
}

async function replyTo(comment) {
  replyTarget.value = comment
  await nextTick()
  document.querySelector('.forum-comment-composer')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function cancelReply() {
  replyTarget.value = null
}

function formatTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 16)
}

function avatarText(username) {
  return String(username || '?').trim().slice(0, 1).toUpperCase()
}

function levelLabel(level) {
  const labels = {
    gray: '刚注册',
    blue: '正式用户',
    green: '贡献者',
    yellow: '活跃达人',
    orange: '社区之星',
    admin: '管理员'
  }
  return labels[level] || labels.gray
}
</script>
