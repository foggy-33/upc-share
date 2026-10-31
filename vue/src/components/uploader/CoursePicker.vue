<template>
  <div class="batch-course-bar">
    <!-- 不放「学科分类」文字标签：placeholder 已经说明这一栏是什么；无障碍名字挂在输入的 aria-label 上 -->
    <div class="course-box">
      <input
        id="batch-course"
        ref="inputEl"
        v-model="text"
        class="form-input course-input"
        type="text"
        placeholder="选择已有学科分类，或输入新建名称"
        aria-label="学科分类"
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

    <!-- 「查看文件夹导入规范」不占第三个盒子：做成贴在导入按钮右上角的「?」徽标，
         悬停/键盘聚焦浮出 tooltip，点开还是那份可复制的整理规范。 -->
    <div class="import-box">
      <button class="btn-upload browse" type="button" :disabled="store.s.running" @click="picker?.click()">
        从文件夹导入
      </button>
      <button class="help-badge" type="button" aria-label="查看文件夹导入规范" title="查看文件夹导入规范"
        @click="store.s.helpOpen = true">?</button>
      <span class="help-tip" role="tooltip">查看文件夹导入规范</span>
    </div>
    <input ref="picker" type="file" data-role="folder-picker" webkitdirectory directory multiple class="sr-only" @change="onPickFolder" />


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
  padding: 13px 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}
.course-box { position: relative; display: inline-flex; }
/* 这一排的三个盒子（输入框 / 从文件夹导入 / 不知道怎么整理）走同一套样式：
   同一个软底 var(--bg)、同一条 1px 实线描边、同一个 8px 圆角、同一档 15px 字号 +
   10px 上下内边距 + 20px 行高（= 42px 高）。行高必须写死：按钮元素不吃继承的 line-height。
   主次只靠字色和字重区分，不再靠"实线 / 虚线 / 无边框"三种描边区分。 */
.course-input {
  width: 260px; font-weight: 600; font-size: 15px; line-height: 20px;
  /* 和插件同规格：35px 高、8px 圆角；右侧留够 32px，清除用的红 × 不压字 */
  box-sizing: border-box; height: 35px; padding: 0 32px 0 10px; border-radius: var(--radius-sm);
}
.course-clear {
  position: absolute; right: 2px; top: 50%; transform: translateY(-50%);
  border: none; background: transparent; color: var(--danger);
  /* 红 × 加大到 28px 见方并贴紧输入框外沿：原来只有 16px 见方，要点很准才点得到 */
  width: 28px; height: 28px; display: inline-flex; align-items: center; justify-content: center;
  /* 字号仍是符号档的 15px：好点靠的是 28px 见方的点击区，不是把字形放大到第五种字号 */
  font-size: 15px; line-height: 1; padding: 0; cursor: pointer; opacity: .75;
  border-radius: var(--radius-sm);
}
.course-clear:hover { opacity: 1; }
.browse {
  /* 上游 .btn-upload 是整宽的蓝色提交键，这里要的是和输入框同款的行内盒子，
     连 hover 一起接管：不接管的话鼠标一碰就变成实心蓝底 + 深色字。 */
  width: auto; margin-top: 0; box-sizing: border-box; height: 35px; padding: 0 14px;
  font-size: 15px; font-weight: 600;
  background: var(--bg); color: var(--text-primary);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
}
.browse:hover { background: var(--bg); border-color: var(--accent); color: var(--text-primary); }
.course-summary { margin-left: auto; color: var(--text-secondary); font-size: 12px; }
.import-box { position: relative; display: inline-flex; flex: none; }
/* 「?」徽标：贴在导入按钮右上角，hover / 键盘聚焦时浮出 tooltip */
.help-badge {
  position: absolute; top: -8px; right: -8px; width: 20px; height: 20px; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--accent); border-radius: 50%;
  background: var(--surface); color: var(--accent);
  font-family: inherit; font-size: 12px; font-weight: 600; line-height: 1; cursor: pointer;
}
.help-badge:hover, .help-badge:focus-visible { background: var(--accent); color: var(--surface); }
.help-tip {
  position: absolute; bottom: calc(100% + 6px); right: -2px; white-space: nowrap;
  background: var(--text-primary); color: var(--surface);
  font-size: 12px; font-weight: 500; padding: 5px 9px; border-radius: 6px;
  opacity: 0; pointer-events: none; transition: opacity .12s ease;
}
.help-badge:hover + .help-tip, .help-badge:focus-visible + .help-tip { opacity: 1; }
.course-menu {
  /* 左边没有标签了，下拉贴着输入框左沿（卡片内边距 18px）*/
  position: absolute; left: 18px; top: 62px; z-index: 30;
  width: 300px; max-height: 220px; overflow: auto;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-md); box-shadow: var(--shadow-md);
  padding: 6px;
}
.course-menu-item {
  padding: 8px 10px; border-radius: var(--radius-sm); cursor: pointer; font-size: 13px;
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
