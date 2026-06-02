<template>
  <section class="forum-image-album">
    <div class="forum-image-album-head">
      <span>Image album</span>
      <label class="action-btn approve">
        Upload
        <input type="file" accept="image/jpeg,image/png,image/gif,image/webp" multiple @change="upload" />
      </label>
    </div>
    <div v-if="error" class="forum-editor-error">{{ error }}</div>
    <div v-if="items.length" class="forum-image-grid">
      <figure v-for="item in items" :key="item.id" class="forum-image-item">
        <img :src="item.url" :alt="item.original_name" loading="lazy" />
        <figcaption>
          <span :title="item.original_name">{{ item.original_name }}</span>
          <div>
            <button class="action-btn" type="button" @click="$emit('insert', item)">Insert</button>
            <button class="action-btn delete" type="button" @click="remove(item)">Delete</button>
          </div>
        </figcaption>
      </figure>
    </div>
    <div v-else class="forum-image-empty">No images yet.</div>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api/http'

defineEmits(['insert'])

const items = ref([])
const error = ref('')
const uploading = ref(false)

onMounted(load)

async function load() {
  error.value = ''
  try {
    const data = await api('/api/forum/images/mine')
    items.value = data.items || []
  } catch (e) {
    error.value = e.message || 'Album failed to load'
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
  } catch (e) {
    error.value = e.message || 'Image upload failed'
  } finally {
    uploading.value = false
  }
}

async function remove(item) {
  await api(`/api/forum/images/${item.id}`, { method: 'DELETE' })
  items.value = items.value.filter((current) => current.id !== item.id)
}
</script>
