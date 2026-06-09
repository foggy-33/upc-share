<template>
  <div class="forum-rich-editor">
    <div class="forum-editor-tip">
      <span>支持所见即所得与 Markdown，可拖拽、粘贴或点击图片按钮上传图片</span>
      <span class="forum-editor-status">{{ loadFailed ? '基础文本模式' : 'Toast UI Editor' }}</span>
    </div>
    <div ref="editorEl" class="forum-rich-editor-box"></div>
    <div v-if="error" class="forum-editor-error">{{ error }}</div>
    <textarea
      v-if="loadFailed"
      class="form-input forum-editor-fallback"
      :value="modelValue"
      :placeholder="placeholder"
      @input="emit('update:modelValue', $event.target.value)"
    ></textarea>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Editor from '@toast-ui/editor'
import '@toast-ui/editor/dist/toastui-editor.css'
import '@toast-ui/editor/dist/i18n/zh-cn'
import { api } from '../api/http'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  height: { type: String, default: '320px' }
})

const emit = defineEmits(['update:modelValue'])
const editorEl = ref(null)
const error = ref('')
const loadFailed = ref(false)
let editor = null
let syncing = false

onMounted(async () => {
  await nextTick()
  if (!Editor || !editorEl.value) {
    loadFailed.value = true
    error.value = 'Editor failed to load. Please check the network and refresh.'
    return
  }
  editor = new Editor({
    el: editorEl.value,
    height: props.height,
    initialEditType: 'wysiwyg',
    previewStyle: 'vertical',
    hideModeSwitch: false,
    usageStatistics: false,
    language: 'zh-CN',
    placeholder: props.placeholder,
    initialValue: props.modelValue,
    toolbarItems: [
      ['heading', 'bold', 'italic', 'strike'],
      ['hr', 'quote'],
      ['ul', 'ol', 'task'],
      ['link', 'image'],
      ['code', 'codeblock']
    ],
    hooks: {
      addImageBlobHook: uploadImage
    },
    events: {
      change: () => emit('update:modelValue', editor.getMarkdown())
    }
  })
})

onBeforeUnmount(() => {
  editor?.destroy()
  editor = null
})

watch(
  () => props.modelValue,
  (value) => {
    if (!editor || syncing) return
    const current = editor.getMarkdown()
    if (value !== current) {
      syncing = true
      editor.setMarkdown(value || '', false)
      syncing = false
    }
  }
)

async function uploadImage(blob, callback) {
  error.value = ''
  const form = new FormData()
  form.append('file', blob, blob.name || 'forum-image.png')
  try {
    const data = await api('/api/forum/images', { method: 'POST', body: form })
    callback(data.url, data.original_name || 'image')
  } catch (e) {
    error.value = e.message || '图片上传失败'
  }
}
</script>
