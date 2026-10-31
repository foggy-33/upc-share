<template>
  <div class="batch-mask" @click.self="close">
    <div class="help-card" role="dialog" aria-modal="true" aria-labelledby="help-title">
      <h3 id="help-title">怎么整理文件夹</h3>
      <p class="help-sub">
        按下面的结构摆文件，导入时就能自动对上学科分类和子目录。
        这段文字可以整段复制走，发给同学或存进备忘录。
      </p>

      <pre ref="preEl" class="help-body">{{ prompt }}</pre>

      <p v-if="copyFailed" class="help-warn">
        这个浏览器环境不允许脚本写剪贴板。可以点进上面那块文字，全选后手动复制。
      </p>

      <div class="help-actions">
        <button class="btn-upload ghost" type="button" @click="close">我知道了</button>
        <button ref="copyEl" class="btn-upload" type="button" :class="{ copied }" @click="copy">
          {{ copied ? '已复制到剪贴板' : '复制 prompt' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ORGANIZE_PROMPT as prompt } from '../../uploader/organizePrompt.js'

const emit = defineEmits(['close'])
const preEl = ref(null)
const copyEl = ref(null)
const copied = ref(false)
const copyFailed = ref(false)
let timer = null

function close() {
  emit('close')
}

async function copy() {
  copyFailed.value = false
  if (await writeClipboard(prompt)) {
    copied.value = true
    clearTimeout(timer)
    timer = setTimeout(() => { copied.value = false }, 2500)
    return
  }
  copyFailed.value = true
  // 复制不出去时，至少把文字选中，让用户能直接按 Ctrl+C
  await nextTick()
  selectBody()
}

/** 优先用剪贴板 API；非安全上下文或没权限时退回临时 textarea + execCommand */
async function writeClipboard(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch (e) { /* 走兜底 */ }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch (e) {
    return false
  }
}

function selectBody() {
  const node = preEl.value
  if (!node || !window.getSelection) return
  const range = document.createRange()
  range.selectNodeContents(node)
  const sel = window.getSelection()
  sel.removeAllRanges()
  sel.addRange(range)
}

function onKey(event) {
  if (event.key === 'Escape') close()
}

onMounted(() => {
  document.addEventListener('keydown', onKey)
  nextTick(() => copyEl.value?.focus())
})
onUnmounted(() => {
  document.removeEventListener('keydown', onKey)
  clearTimeout(timer)
})
</script>

<style scoped>
.batch-mask {
  position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, .35); padding: 20px;
}
.help-card {
  width: min(720px, 100%); max-height: 86vh; display: flex; flex-direction: column;
  background: var(--surface); color: var(--text-primary);
  border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg); padding: 22px 24px;
}
.help-card h3 { margin: 0 0 6px; font-size: 15px; font-weight: 600; }
.help-sub { margin: 0 0 12px; color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
.help-body {
  flex: 1; min-height: 0; overflow: auto; margin: 0; padding: 14px 16px;
  background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md);
  font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace;
  font-size: 12px; line-height: 1.75; white-space: pre-wrap; word-break: break-word;
  color: var(--text-primary); tab-size: 2;
}
.help-warn { margin: 10px 0 0; color: #b45309; font-size: 12px; }
.help-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; flex: none; }
/* 弹层底部两颗按钮也走主操作档，和页面里的盒子同一套 */
.help-actions .btn-upload {
  width: auto; margin-top: 0; padding: 10px 20px; display: inline-flex;
  font-size: 15px; font-weight: 600; line-height: 20px; border-radius: var(--radius-sm);
}
.help-actions .btn-upload.copied { background: var(--green); }
.btn-upload.ghost { background: var(--bg); color: var(--text-primary); border: 1px solid var(--border); }
</style>
