<template>
  <div class="forum-content-viewer" v-html="rendered" @click="previewImage"></div>
  <button v-if="previewUrl" class="forum-image-preview" type="button" aria-label="关闭图片预览" @click="previewUrl = ''">
    <img :src="previewUrl" alt="" @click.stop />
  </button>
</template>

<script setup>
import { computed, ref } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps({
  content: { type: String, default: '' }
})

const markdown = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  typographer: false
})
const defaultLinkOpen = markdown.renderer.rules.link_open
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen ? defaultLinkOpen(tokens, index, options, env, self) : self.renderToken(tokens, index, options)
}

const rendered = computed(() => markdown.render(props.content || ''))
const previewUrl = ref('')

function previewImage(event) {
  const image = event.target.closest?.('img')
  if (!image || !event.currentTarget.contains(image)) return
  previewUrl.value = image.currentSrc || image.src
}
</script>
