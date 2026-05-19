<template>
  <NavBar />
  <section class="admin-section">
    <div class="container">
      <h1 class="admin-title">上传资料</h1>
      <p class="admin-subtitle">支持 PDF、Word、PPT、Excel、ZIP、RAR 等格式，单文件最大 200MB。上传后需等待管理员审核。</p>
      <div class="upload-card">
        <input type="file" class="form-input" @change="file = $event.target.files[0]" />
        <div class="form-group"><label class="form-label">学科分类</label><input v-model="category" class="form-input" /></div>
        <div class="form-group"><label class="form-label">子目录（可选）</label><input v-model="subCategory" class="form-input" /></div>
        <button class="btn-upload" @click="submit" :disabled="loading">{{ loading ? '上传中...' : '上传文件' }}</button>
        <p v-if="message" class="admin-subtitle" style="margin-top:12px">{{ message }}</p>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import { api } from '../api/http'

const file = ref(null)
const category = ref('')
const subCategory = ref('')
const message = ref('')
const loading = ref(false)

async function submit() {
  if (!file.value) return
  loading.value = true
  const form = new FormData()
  form.append('file', file.value)
  form.append('category', category.value)
  form.append('sub_category', subCategory.value)
  form.append('description', '')
  try {
    const data = await api('/api/upload', { method: 'POST', body: form })
    message.value = data.message || '上传成功'
  } catch (e) {
    message.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
