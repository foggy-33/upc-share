<template>
  <section class="profile-album">
    <div class="profile-posts-head">
      <div>
        <h2>{{ title }}</h2>
        <p>在论坛帖子和回复中上传过的图片会展示在这里。</p>
      </div>
      <span v-if="total" class="profile-album-count">{{ total }} 张</span>
    </div>

    <div v-if="loading" class="profile-empty">正在加载图片...</div>
    <div v-else-if="error" class="profile-album-error">{{ error }}</div>
    <div v-else-if="images.length" class="profile-album-grid">
      <article v-for="image in images" :key="image.id" class="profile-album-item">
        <button
          class="profile-album-open"
          type="button"
          :aria-label="`预览图片 ${image.original_name || ''}`"
          @click="preview = image"
        >
          <img :src="image.url" :alt="image.original_name || ''" loading="lazy" />
        </button>
        <button
          v-if="canDelete"
          class="profile-album-delete"
          type="button"
          :disabled="deletingId === image.id"
          aria-label="删除照片"
          @click="deleteImage(image)"
        >
          ×
        </button>
        <span class="profile-album-meta">
          <strong>{{ image.original_name || '图片' }}</strong>
          <small>{{ formatTime(image.created_at) }}</small>
        </span>
      </article>
    </div>
    <div v-else class="profile-empty">还没有发过图片。</div>

    <div class="pagination" v-if="pages > 1">
      <button class="page-btn" :disabled="page <= 1 || loading" @click="go(page - 1)">上一页</button>
      <button class="page-btn active">{{ page }} / {{ pages }}</button>
      <button class="page-btn" :disabled="page >= pages || loading" @click="go(page + 1)">下一页</button>
    </div>

    <button v-if="preview" class="forum-image-preview profile-album-preview" type="button" aria-label="关闭图片预览" @click="preview = null">
      <span>
        <em>点击空白处关闭</em>
        <img :src="preview.url" :alt="preview.original_name || ''" @click.stop />
        <strong>{{ preview.original_name || '图片' }}</strong>
      </span>
    </button>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { api } from '../api/http'

const props = defineProps({
  uid: { type: String, default: '' },
  title: { type: String, default: '个人相册' },
  canDelete: { type: Boolean, default: false }
})

const images = ref([])
const page = ref(1)
const pages = ref(0)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const preview = ref(null)
const deletingId = ref('')

watch(() => props.uid, () => {
  page.value = 1
  preview.value = null
  load()
}, { immediate: true })

async function load() {
  if (!props.uid) {
    images.value = []
    pages.value = 0
    total.value = 0
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await api(`/api/forum/images/users/${encodeURIComponent(props.uid)}?page=${page.value}&size=12`)
    images.value = data.items || []
    pages.value = Number(data.pages || 0)
    total.value = Number(data.total || 0)
  } catch (e) {
    images.value = []
    error.value = e.message || '相册加载失败'
  } finally {
    loading.value = false
  }
}

async function go(next) {
  if (next < 1 || next > pages.value || loading.value) return
  page.value = next
  await load()
}

async function deleteImage(image) {
  if (!image?.id || deletingId.value) return
  if (!window.confirm(`确认删除照片「${image.original_name || '图片'}」？`)) return
  deletingId.value = image.id
  error.value = ''
  try {
    await api(`/api/forum/images/${image.id}`, { method: 'DELETE' })
    if (preview.value?.id === image.id) preview.value = null
    if (images.value.length === 1 && page.value > 1) page.value -= 1
    await load()
  } catch (e) {
    error.value = e.message || '照片删除失败'
  } finally {
    deletingId.value = ''
  }
}

function formatTime(value) {
  const text = String(value || '').trim()
  return text ? text.replace('T', ' ').slice(0, 10) : '-'
}
</script>
