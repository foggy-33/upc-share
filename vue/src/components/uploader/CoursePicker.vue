<template>
  <div class="batch-course-bar">
    <label class="form-label" for="batch-course">学科分类</label>

    <div class="course-box">
      <input
        id="batch-course"
        ref="inputEl"
        v-model="text"
        class="form-input course-input"
        type="text"
        placeholder="选择已有学科分类，或输入新建名称"
        autocomplete="off"
        role="combobox"
        :aria-expanded="open ? 'true' : 'false'"
        aria-controls="batch-course-menu"
        :disabled="store.s.running"
        @focus="onFocus"
        @input="onInput"
        @blur="onBlur"
        @keydown.down.prevent="move(1)"
        @keydown.up.prevent="move(-1)"
        @keydown.enter.prevent="commit()"
        @keydown.esc="close()"
      />
      <button
        v-show="store.s.course.name && !store.s.running"
        class="course-clear"
        type="button"
        title="清除学科分类选择"
        aria-label="清除学科分类选择"
        @mousedown.prevent="store.clearCourse()"
      >×</button>
    </div>

    <button class="btn-upload browse" type="button" :disabled="store.s.running" @click="picker?.click()">
      从文件夹导入
    </button>
    <input ref="picker" type="file" data-role="folder-picker" webkitdirectory directory multiple class="sr-only" @change="onPickFolder" />

    <button class="help-link" type="button" title="查看文件夹整理规范" @click="store.s.helpOpen = true">
      不知道怎么整理？
    </button>

    <span class="course-summary">{{ summary }}</span>

    <div v-if="open" id="batch-course-menu" class="course-menu" role="listbox">
      <div v-if="!entries.length" class="course-menu-empty">没有匹配的学科分类，直接输入即可新建</div>
      <div
        v-for="(item, i) in entries"
        :key="item.value"
        class="course-menu-item"
        :class="{ active: i === active, new: item.isNew }"
        role="option"
        :aria-selected="i === active ? 'true' : 'false'"
        @mousedown.prevent="choose(item)"
      >
        {{ item.isNew ? '使用「' + item.value + '」（新建）' : item.value }}
      </div>
    </div>

    <p v-if="store.hint.value" class="form-message">{{ store.hint.value }}</p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { fuzzyFilter, normalizeText } from '../../uploader/logic.js'

const props = defineProps({ store: { type: Object, required: true } })

const inputEl = ref(null)
const picker = ref(null)
const text = ref('')
const open = ref(false)
const active = ref(0)
// 失焦时要区分"给当前学科分类改名"和"换一门学科分类"，所以记下进入输入框时的值
const focusValue = ref('')

const entries = computed(() => {
  const query = text.value.trim()
  const list = fuzzyFilter(props.store.categoryCandidates.value, query, 60)
  const exact = list.some((v) => normalizeText(v) === normalizeText(query))
  const out = list.map((value) => ({ value, isNew: false }))
  if (query && !exact) out.push({ value: query, isNew: true })
  return out
})

const summary = computed(() => {
  const s = props.store.s
  if (s.running) return `投递中 ${s.progress.doneFiles}/${s.progress.totalFiles}`
  const files = props.store.allFiles.value.length
  if (!s.course.dirs.length) return ''
  return `${s.course.dirs.length} 个子目录` + (files ? ` · ${files} 个文件` : '')
})

function sync() {
  text.value = props.store.s.course.name || ''
  focusValue.value = text.value
}
sync()

function onFocus() {
  focusValue.value = props.store.s.course.name || ''
  props.store.focusCourse()
  active.value = 0
  open.value = true
}

function onInput() {
  active.value = 0
  open.value = true
  props.store.s.course.name = text.value
}

function onBlur() {
  close()
  commit(true)
}

function close() {
  open.value = false
}

function move(delta) {
  if (!open.value) { open.value = true; return }
  active.value = Math.max(0, Math.min(entries.value.length - 1, active.value + delta))
}

function choose(item) {
  text.value = item.value
  props.store.s.course.name = item.value
  open.value = false
  props.store.pickCourse(item.value)
}

function commit(fromBlur = false) {
  const value = text.value.trim()
  if (!value) { props.store.s.course.name = ''; return }
  const hit = props.store.categoryCandidates.value.find((c) => normalizeText(c) === normalizeText(value))
  if (hit) {
    text.value = hit
    props.store.pickCourse(hit)
    return
  }
  // 站内没有这个名字：命中不了就是新建，改名逻辑负责确认
  if (fromBlur && normalizeText(value) !== normalizeText(focusValue.value)) props.store.renameCourse(value)
  else props.store.s.course.name = value
}

function onPickFolder(event) {
  const files = event.target.files
  if (files && files.length) props.store.importFromFolder(files)
  event.target.value = ''
}
</script>

<style scoped>
.batch-course-bar {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px 18px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.course-box { position: relative; display: inline-flex; }
.course-input { width: 280px; font-weight: 600; }
.course-clear {
  position: absolute; right: 8px; top: 50%; transform: translateY(-50%);
  border: none; background: transparent; color: var(--danger);
  font-size: 16px; line-height: 1; padding: 4px; cursor: pointer; opacity: .7;
}
.course-clear:hover { opacity: 1; }
.browse {
  /* 上游 .btn-upload 是整宽的提交键，这里要的是行内小按钮 */
  width: auto; margin-top: 0; padding: 10px 18px;
  background: var(--surface); color: var(--text-primary); border: 1px solid var(--border);
}
.course-summary { margin-left: auto; color: var(--text-secondary); font-size: 13px; }
.help-link {
  border: 1px dashed var(--border-hover); background: transparent; color: var(--text-secondary);
  font: inherit; font-size: 13px; padding: 9px 14px; border-radius: var(--radius-md); cursor: pointer;
}
.help-link:hover { border-color: var(--accent); background: var(--accent-light); color: var(--accent); }
.course-menu {
  position: absolute; left: 92px; top: 62px; z-index: 30;
  width: 300px; max-height: 220px; overflow: auto;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-md); box-shadow: var(--shadow-md);
  padding: 6px;
}
.course-menu-item {
  padding: 8px 10px; border-radius: var(--radius-sm); cursor: pointer; font-size: 14px;
}
.course-menu-item.active { background: var(--accent-light); color: var(--accent); }
.course-menu-item.new { color: var(--accent); font-weight: 600; }
.course-menu-empty { padding: 10px; color: var(--text-secondary); font-size: 13px; }
.batch-course-bar .form-message { width: 100%; margin: 0; text-align: left; color: #b45309; }
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
}
</style>
