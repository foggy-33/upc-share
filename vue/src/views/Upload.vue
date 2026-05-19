<template>
  <NavBar />
  <section class="admin-section">
    <div class="container">
      <div class="page-heading">
        <h1 class="admin-title">上传资料</h1>
        <p class="admin-subtitle">支持 PDF、Word、PPT、Excel、ZIP、RAR 等格式，单文件最大 200MB。上传后等待管理员审核。</p>
      </div>

      <div class="upload-layout">
        <div
          class="upload-zone"
          :class="{ dragover }"
          @dragover.prevent="dragover = true"
          @dragleave.prevent="dragover = false"
          @drop.prevent="onDrop"
          @click="picker?.click()"
        >
          <input ref="picker" type="file" class="sr-only" @change="onPick" />
          <div class="upload-zone-icon">↑</div>
          <div class="upload-text">拖拽文件到这里，或点击选择文件</div>
          <div class="upload-hint">资料会保存在对应学科目录中</div>
        </div>

        <div class="upload-card">
          <div v-if="file" class="file-preview">
            <span class="file-preview-icon" :class="previewClass">{{ fileExt }}</span>
            <div class="file-preview-info">
              <span class="file-preview-name">{{ file.name }}</span>
              <span class="file-preview-size">{{ fileSize }}</span>
            </div>
            <button class="file-preview-remove" @click="file = null">×</button>
          </div>

          <div class="form-group">
            <label class="form-label">学科分类</label>
            <input v-model="category" class="form-input" placeholder="例如：大学物理" />
          </div>
          <div class="form-group">
            <label class="form-label">子目录</label>
            <input v-model="subCategory" class="form-input" placeholder="可选，例如：期末试卷" />
          </div>

          <button class="btn-upload" @click="submit" :disabled="loading || !file">
            <span v-if="loading" class="btn-loader"></span>
            {{ loading ? '上传中...' : '上传文件' }}
          </button>
          <p v-if="message" class="form-message" :class="{ error: failed }">{{ message }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import { api } from '../api/http'

const picker = ref(null)
const file = ref(null)
const category = ref('')
const subCategory = ref('')
const message = ref('')
const loading = ref(false)
const failed = ref(false)
const dragover = ref(false)

const fileExt = computed(() => {
  if (!file.value?.name?.includes('.')) return 'FILE'
  return file.value.name.split('.').pop().toUpperCase()
})
const fileSize = computed(() => {
  if (!file.value) return ''
  const size = file.value.size
  if (size > 1024 ** 2) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024).toFixed(1)} KB`
})
const previewClass = computed(() => {
  const ext = fileExt.value.toLowerCase()
  if (['doc', 'docx'].includes(ext)) return 'word'
  if (['zip', 'rar', '7z'].includes(ext)) return 'archive'
  if (['ppt', 'pptx'].includes(ext)) return 'ppt'
  if (['xls', 'xlsx', 'csv'].includes(ext)) return 'xls'
  return ext
})

function onPick(event) {
  file.value = event.target.files?.[0] || null
}

function onDrop(event) {
  dragover.value = false
  file.value = event.dataTransfer.files?.[0] || null
}

async function submit() {
  if (!file.value) return
  loading.value = true
  failed.value = false
  message.value = ''
  const form = new FormData()
  form.append('file', file.value)
  form.append('category', category.value)
  form.append('sub_category', subCategory.value)
  form.append('description', '')
  try {
    const data = await api('/api/upload', { method: 'POST', body: form })
    message.value = data.message || '上传成功，等待审核'
    file.value = null
  } catch (e) {
    failed.value = true
    message.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
