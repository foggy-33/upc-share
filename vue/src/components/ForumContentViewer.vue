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

const rendered = computed(() => renderContent(props.content || ''))
const previewUrl = ref('')

function renderContent(content) {
  const source = normalizeCenterTags(String(content || ''))
  const centerPattern = /<center>([\s\S]*?)<\/center>/gi
  let html = ''
  let lastIndex = 0
  let match
  while ((match = centerPattern.exec(source)) !== null) {
    html += markdown.render(source.slice(lastIndex, match.index))
    html += `<div class="forum-align-center">${markdown.render(normalizeCenteredMarkdown(match[1]))}</div>`
    lastIndex = centerPattern.lastIndex
  }
  html += markdown.render(source.slice(lastIndex))
  return html
}

function normalizeCenterTags(value) {
  return value
    .replace(/&lt;\s*center\s*&gt;/gi, '<center>')
    .replace(/&lt;\s*\/\s*center\s*&gt;/gi, '</center>')
    .replace(/\\<\s*center\s*\\?>/gi, '<center>')
    .replace(/\\<\s*\/\s*center\s*\\?>/gi, '</center>')
}

function normalizeCenteredMarkdown(value) {
  return String(value || '')
    .trim()
    .replace(/^\\(#{1,6}\s+)/gm, '$1')
    .replace(/^\\([*+-]\s+)/gm, '$1')
    .replace(/^\\(\d+\.\s+)/gm, '$1')
}

function previewImage(event) {
  const image = event.target.closest?.('img')
  if (!image || !event.currentTarget.contains(image)) return
  previewUrl.value = image.currentSrc || image.src
}
</script>
