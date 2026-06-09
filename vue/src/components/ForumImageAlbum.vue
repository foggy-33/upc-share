<template>
  <section class="forum-image-album">
    <div class="forum-image-album-head">
      <button type="button" class="forum-album-toggle" @click="open = !open">
        <span>我的图片相册</span>
        <small>{{ items.length }} 张 · 点击图片可插入正文</small>
      </button>
      <div class="forum-album-actions">
        <label class="action-btn approve">
          {{ uploading ? '上传中...' : '上传图片' }}
          <input type="file" accept="image/jpeg,image/png,image/gif,image/webp" multiple :disabled="uploading" @change="upload" />
        </label>
        <button type="button" class="action-btn" @click="open = !open">{{ open ? '收起' : '展开' }}</button>
      </div>
    </div>
    <div v-if="error" class="forum-editor-error">{{ error }}</div>
    <div v-if="open && items.length" class="forum-image-grid">
      <figure v-for="item in items" :key="item.id" class="forum-image-item">
        <img :src="item.url" :alt="item.original_name" loading="lazy" />
        <figcaption>
          <span :title="item.original_name">{{ item.original_name }}</span>
          <div>
            <button class="action-btn approve" type="button" @click="$emit('insert', item)">插入正文</button>
            <button class="action-btn delete" type="button" @click="remove(item)">删除</button>
          </div>
        </figcaption>
      </figure>
    </div>
    <div v-else-if="open" class="forum-image-empty">相册中还没有图片，可上传后插入正文。</div>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api/http'

defineEmits(['insert'])

const items = ref([])
const error = ref('')
const uploading = ref(false)
const open = ref(false)

onMounted(load)

async function load() {
  error.value = ''
  try {
    const data = await api('/api/forum/images/mine')
    items.value = data.items || []
  } catch (e) {
    error.value = e.message || '图片相册加载失败'
  }
}

async function upload(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length || uploading.value) return
  uploading.value = true
  error.value = ''
  try {
    for (const file of files) {
      const form = new FormData()
      form.append('file', file)
      await api('/api/forum/images', { method: 'POST', body: form })
    }
    await load()
    open.value = true
  } catch (e) {
    error.value = e.message || '图片上传失败'
  } finally {
    uploading.value = false
  }
}

async function remove(item) {
  if (!confirm(`确认删除图片“${item.original_name}”？`)) return
  await api(`/api/forum/images/${item.id}`, { method: 'DELETE' })
  items.value = items.value.filter((current) => current.id !== item.id)
}
</script>
