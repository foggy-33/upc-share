<template>
  <div ref="viewerEl" class="forum-content-viewer"></div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Editor from '@toast-ui/editor'
import '@toast-ui/editor/dist/toastui-editor-viewer.css'
import PhotoSwipeLightbox from 'photoswipe/lightbox'
import PhotoSwipe from 'photoswipe'
import 'photoswipe/style.css'

const props = defineProps({
  content: { type: String, default: '' }
})

const viewerEl = ref(null)
let viewer = null
let lightbox = null

onMounted(async () => {
  await nextTick()
  await render()
})

onBeforeUnmount(() => {
  viewer?.destroy()
  lightbox?.destroy()
})

watch(() => props.content, render)

async function render() {
  if (!viewerEl.value) return
  if (!Editor) {
    viewerEl.value.textContent = props.content || ''
    return
  }
  viewer?.destroy()
  lightbox?.destroy()
  viewer = Editor.factory({
    el: viewerEl.value,
    viewer: true,
    initialValue: props.content || ''
  })
  await nextTick()
  prepareGallery()
}

async function prepareGallery() {
  if (!viewerEl.value) return
  const images = Array.from(viewerEl.value.querySelectorAll('img'))
  if (!images.length) return
  images.forEach((img, index) => {
    const link = document.createElement('a')
    link.href = img.currentSrc || img.src
    link.dataset.pswpWidth = img.naturalWidth || 1200
    link.dataset.pswpHeight = img.naturalHeight || 800
    link.dataset.index = String(index)
    img.parentNode.insertBefore(link, img)
    link.appendChild(img)
  })
  lightbox = new PhotoSwipeLightbox({
    gallery: viewerEl.value,
    children: 'a',
    pswpModule: PhotoSwipe
  })
  lightbox.init()
}
</script>
