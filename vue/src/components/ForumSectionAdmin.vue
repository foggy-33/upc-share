<template>
  <div class="content-admin-panel">
    <section class="content-admin-box forum-section-admin">
      <div class="content-box-heading">
        <div>
          <span class="content-step">1</span>
          <h2>{{ form.id ? '编辑论坛板块' : '新增论坛板块' }}</h2>
          <p>设置板块名称、显示顺序和最低访问等级。</p>
        </div>
        <button v-if="form.id" class="action-btn" type="button" @click="resetForm">取消编辑</button>
      </div>

      <div class="forum-section-form">
        <label class="content-field">
          <span>板块名称</span>
          <input v-model="form.name" class="form-input" maxlength="64" placeholder="例如：高阶资料讨论" />
        </label>
        <label class="content-field">
          <span>最低访问等级</span>
          <select v-model="form.min_level" class="form-input">
            <option v-for="level in levelOptions" :key="level.value" :value="level.value">{{ level.label }}</option>
          </select>
        </label>
        <label class="content-field">
          <span>排序</span>
          <input v-model.number="form.sort_order" class="form-input" type="number" step="1" />
        </label>
        <label class="forum-section-active">
          <input v-model="form.is_active" type="checkbox" />
          <span>启用板块</span>
        </label>
      </div>

      <div class="content-form-actions">
        <span v-if="message" class="notice-admin-message">{{ message }}</span>
        <button class="action-btn" type="button" @click="resetForm">重置</button>
        <button class="action-btn approve" :disabled="loading || !form.name.trim()" @click="saveSection">
          {{ loading ? '保存中...' : form.id ? '保存修改' : '新增板块' }}
        </button>
      </div>
    </section>

    <section class="content-admin-box">
      <div class="content-box-heading">
        <div>
          <span class="content-step">2</span>
          <h2>已有板块</h2>
          <p>有帖子归属的板块删除时会自动停用，历史帖子不会被移除。</p>
        </div>
        <button class="action-btn" :disabled="loading" @click="loadSections">刷新</button>
      </div>

      <div v-if="error" class="dash-error">{{ error }}</div>
      <div class="forum-section-list">
        <article v-for="section in sections" :key="section.id" class="forum-section-row">
          <div>
            <strong>{{ section.name }}</strong>
            <small>{{ levelLabel(section.min_level) }} · 排序 {{ section.sort_order ?? 0 }} · {{ section.post_count || 0 }} 篇帖子</small>
          </div>
          <span class="status-pill" :class="boolValue(section.is_active) ? 'approved' : 'rejected'">
            {{ boolValue(section.is_active) ? '启用' : '停用' }}
          </span>
          <button class="action-btn" type="button" @click="editSection(section)">编辑</button>
          <button class="action-btn delete" type="button" @click="deleteSection(section)">删除/停用</button>
        </article>
        <div v-if="!sections.length && !loading" class="empty-state compact">暂无论坛板块</div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api/http'

const levelOptions = [
  { value: 'gray', label: '新人' },
  { value: 'blue', label: '正式用户' },
  { value: 'green', label: '贡献者' },
  { value: 'yellow', label: '活跃达人' },
  { value: 'orange', label: '社区之星' },
  { value: 'admin', label: '管理员' }
]

const sections = ref([])
const loading = ref(false)
const error = ref('')
const message = ref('')
const form = ref(emptyForm())

onMounted(loadSections)

async function loadSections() {
  loading.value = true
  error.value = ''
  try {
    const data = await api('/api/admin/forum/sections')
    sections.value = data.items || []
  } catch (e) {
    error.value = e.message || '论坛板块加载失败'
  } finally {
    loading.value = false
  }
}

async function saveSection() {
  if (!form.value.name.trim()) return
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    await api('/api/admin/forum/sections', {
      method: 'POST',
      body: JSON.stringify({
        ...form.value,
        name: form.value.name.trim(),
        sort_order: Number(form.value.sort_order || 0)
      })
    })
    message.value = '论坛板块已保存'
    resetForm()
    await loadSections()
  } catch (e) {
    error.value = e.message || '论坛板块保存失败'
  } finally {
    loading.value = false
  }
}

function editSection(section) {
  form.value = {
    id: Number(section.id || 0),
    name: section.name || '',
    min_level: section.min_level || 'gray',
    sort_order: Number(section.sort_order || 0),
    is_active: boolValue(section.is_active)
  }
}

async function deleteSection(section) {
  if (!window.confirm(`确认删除或停用板块「${section.name}」？`)) return
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    const data = await api(`/api/admin/forum/sections/${section.id}`, { method: 'DELETE' })
    message.value = data.disabled ? '该板块已有帖子，已停用' : '论坛板块已删除'
    if (form.value.id === section.id) resetForm()
    await loadSections()
  } catch (e) {
    error.value = e.message || '论坛板块删除失败'
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.value = emptyForm()
}

function emptyForm() {
  return {
    id: 0,
    name: '',
    min_level: 'gray',
    sort_order: 100,
    is_active: true
  }
}

function boolValue(value) {
  if (value === undefined || value === null || value === '') return false
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  return !['0', 'false', 'no'].includes(String(value).toLowerCase())
}

function levelLabel(level) {
  return levelOptions.find(item => item.value === level)?.label || '新人'
}
</script>
