<template>
  <div class="forum-rich-editor" :class="{ compact, fullscreen: expanded }">
    <div class="forum-editor-tip">
      <button class="forum-editor-expand" type="button" @click="toggleExpanded">
        {{ expanded ? '退出全屏' : '全屏编辑' }}
      </button>
    </div>
    <div ref="editorEl" class="forum-rich-editor-box"></div>
    <div class="forum-editor-mobile-tools">
      <button class="forum-editor-tool-btn" type="button" :disabled="uploadingImage" @click="chooseImage">
        <span aria-hidden="true">⊕</span>
        {{ uploadingImage ? '上传中' : '图片' }}
      </button>
    </div>
    <input
      ref="imageInput"
      class="forum-editor-file-input"
      type="file"
      accept="image/*"
      @change="handleImagePicked"
    />
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Editor from '@toast-ui/editor'
import '@toast-ui/editor/dist/toastui-editor.css'
import '@toast-ui/editor/dist/i18n/zh-cn'
import { api } from '../api/http'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  height: { type: String, default: '420px' },
  compact: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])
const editorEl = ref(null)
const imageInput = ref(null)
const error = ref('')
const loadFailed = ref(false)
const expanded = ref(false)
const uploadingImage = ref(false)
const effectiveHeight = computed(() => {
  if (expanded.value) return 'calc(100dvh - 92px)'
  return props.height
})
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
    height: effectiveHeight.value,
    minHeight: props.compact ? '220px' : '320px',
    initialEditType: 'wysiwyg',
    previewStyle: 'tab',
    hideModeSwitch: true,
    usageStatistics: false,
    language: 'zh-CN',
    placeholder: props.placeholder,
    initialValue: props.modelValue,
    toolbarItems: toolbarItems(),
    hooks: {
      addImageBlobHook: uploadImage
    },
    events: {
      change: () => emit('update:modelValue', editor.getMarkdown())
    }
  })
})

onBeforeUnmount(() => {
  document.body.classList.remove('forum-editor-fullscreen-open')
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

watch(effectiveHeight, (height) => {
  editor?.setHeight(height)
})

function toggleExpanded() {
  expanded.value = !expanded.value
  document.body.classList.toggle('forum-editor-fullscreen-open', expanded.value)
  nextTick(() => editor?.focus())
}

function toolbarItems() {
  const centerItem = centerToolbarItem()
  return props.compact
    ? [
        ['bold', 'italic', 'strike', centerItem],
        ['quote', 'ul', 'ol'],
        ['link']
      ]
    : [
        ['heading', 'bold', 'italic', 'strike', centerItem],
        ['quote'],
        ['ul', 'ol', 'task'],
        ['link'],
        ['code', 'codeblock']
      ]
}

function centerToolbarItem() {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'forum-editor-center-btn'
  button.textContent = '居中'
  button.addEventListener('click', wrapCenter)
  return {
    name: 'alignCenter',
    tooltip: '居中',
    el: button
  }
}

function wrapCenter() {
  if (!editor) return
  const selected = editor.getSelectedText()
  const text = selected?.trim() ? selected : '居中内容'
  editor.replaceSelection(`<center>${text}</center>`)
  emit('update:modelValue', editor.getMarkdown())
}

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

function chooseImage() {
  imageInput.value?.click()
}

async function handleImagePicked(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || uploadingImage.value) return
  uploadingImage.value = true
  await uploadImage(file, (url, altText) => {
    editor?.exec('addImage', { imageUrl: url, altText: altText || file.name || 'image' })
    emit('update:modelValue', editor?.getMarkdown() || props.modelValue)
  })
  uploadingImage.value = false
}
</script>
