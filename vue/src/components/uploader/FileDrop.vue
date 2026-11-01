<template>
  <section class="work">
    <div v-if="!dir" class="work-empty">
      <div class="steps">
        <span><b>1</b>选择学科分类</span>
        <span><b>2</b>添加子目录</span>
        <span><b>3</b>上传文件</span>
      </div>
      <h3>还没有子目录</h3>
      <p>
        先在上方选择或输入学科分类，再新建子目录，然后把文件拖进来；<br />
        也可以直接「从文件夹导入」，按文件夹层级自动生成学科分类与子目录。
      </p>
      <button class="btn-upload small" type="button" :disabled="!store.courseValid.value" @click="store.addDir()">＋ 新建子目录</button>
      <p class="req">支持 PDF、Word、PPT、Excel、ZIP、RAR 等格式，单文件最大 200MB，上传后等待审核。</p>
    </div>

    <div v-else class="work-body">
      <div class="work-head">
        <div class="head-main">
          <input
            ref="titleEl"
            v-model="dirName"
            class="dir-title"
            type="text"
            :readonly="!renameable"
            placeholder="子目录名称（可选，留空 = 直接挂在学科分类下）"
            @keydown.enter="$event.target.blur()"
            @keydown.esc="revert"
            @blur="commit"
          />
          <div class="dir-meta">{{ meta }}</div>
          <div v-if="renameable" class="rename-tip">改这里即时生效，不需要保存：它只决定本次上传落到哪个子目录，站内已有的文件不会被移动。</div>
        </div>
        <div class="head-actions">
          <button
            v-if="store.isAdmin.value"
            class="btn-upload small"
            type="button"
            :disabled="store.s.running"
            @click="store.toggleSite()"
          >{{ store.s.site.show ? '收起已有资源' : '已有资源' }}</button>
        </div>
      </div>

      <div
        class="upload-zone drop"
        :class="{ dragover }"
        @dragover.prevent="dragover = true"
        @dragleave.prevent="dragover = false"
        @drop.prevent="onDrop"
        @click="picker?.click()"
      >
        <input ref="picker" data-role="file-picker" type="file" multiple class="sr-only" @change="onPick" />
        <div class="upload-text">将文件拖放到此处，或点击选择</div>
      </div>
      <p class="req">支持 PDF、Word、PPT、Excel、ZIP、RAR 等格式，单文件最大 200MB，上传后等待审核。</p>

      <!-- 顺序跟插件版一致：拖放区在最上面，站内文件在它下面 -->
      <SiteFilesPanel v-if="store.s.site.show" :store="store" :dir="dir" />

      <ul class="file-list">
        <li v-for="entry in dir.files" :key="entry.relPath + entry.size" class="file-row">
          <span class="file-ext-badge" :class="extClass(entry.name)">{{ extOf(entry.name) }}</span>
          <span class="file-info">
            <b>{{ entry.name }}</b>
            <span>{{ formatSize(entry.size) }}<template v-if="entry.error"> · {{ entry.error }}</template></span>
          </span>
          <span class="chip" :class="'chip-' + entry.status">{{ chipText(entry) }}</span>
          <button v-if="entry.status === 'failed'" class="chip-btn" type="button" @click="store.retryEntry(entry)">重试</button>
          <button class="rm" type="button" aria-label="从本次列表移除" :disabled="store.s.running" @click="store.removeEntry(dir, entry)">×</button>
        </li>
      </ul>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { extOf, formatSize } from '../../uploader/logic'
import SiteFilesPanel from './SiteFilesPanel.vue'

const props = defineProps({ store: { type: Object, required: true } })

const picker = ref(null)
const titleEl = ref(null)
const dragover = ref(false)
const dirName = ref('')
let renameFrom = ''

const dir = computed(() => props.store.s.activeDir)
const renameable = computed(() => props.store.canRenameDir(dir.value))

const meta = computed(() => {
  if (!dir.value) return ''
  const bytes = dir.value.files.reduce((sum, f) => sum + (Number(f.size) || 0), 0)
  return `${dir.value.files.length} 个文件 · ${formatSize(bytes)}`
})

watch(dir, (value) => { dirName.value = value ? value.name : '' }, { immediate: true })

// 左栏菜单点了「重命名」：把焦点和整行选中交给名字输入框
watch(() => props.store.s.renameTick, () => {
  if (!renameable.value) return
  titleEl.value?.focus()
  titleEl.value?.select()
})

function chipText(entry) {
  return ({
    ready: '待上传', dup: '已存在', dupname: '同名', unsupported: '格式不支持',
    toolarge: '超过 200MB', badname: '文件名非法', running: '上传中', done: '已完成', failed: '失败'
  })[entry.status] || entry.status
}

function extClass(name) {
  const ext = extOf(name).replace('.', '')
  if (['doc', 'docx'].includes(ext)) return 'word'
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return 'archive'
  if (['ppt', 'pptx'].includes(ext)) return 'ppt'
  if (['xls', 'xlsx', 'csv'].includes(ext)) return 'xls'
  return ext
}

function commit() {
  if (!dir.value || !renameable.value) { dirName.value = dir.value ? dir.value.name : ''; return }
  props.store.renameDir(dir.value, dirName.value)
  dirName.value = dir.value.name
}

function revert() {
  dirName.value = dir.value ? dir.value.name : renameFrom
  titleEl.value?.blur()
}

function addFiles(fileList) {
  const files = Array.prototype.slice.call(fileList || [])
  if (files.length) props.store.addFiles(files)
}

function onPick(event) {
  addFiles(event.target.files)
  event.target.value = ''
}

function onDrop(event) {
  dragover.value = false
  // 交给 store：只有文件就塞进当前子目录，含文件夹就按层级识别学科分类与子目录
  props.store.receiveDrop(event.dataTransfer)
}
</script>

<style scoped>
.work {
  flex: 1; min-width: 0; display: flex; flex-direction: column;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 14px 18px;
}
.work-empty { text-align: center; padding: 40px 16px; color: var(--text-secondary); }
.work-empty h3 { margin: 0 0 8px; color: var(--text-primary); font-size: 15px; font-weight: 600; }
.work-empty p { margin: 0 0 14px; line-height: 1.8; font-size: 13px; }
.steps { display: flex; gap: 18px; justify-content: center; margin-bottom: 18px; font-size: 13px; }
.steps b {
  display: inline-flex; width: 18px; height: 18px; border-radius: 50%;
  background: var(--accent-light); color: var(--accent); font-size: 12px;
  align-items: center; justify-content: center; margin-right: 6px;
}
.req { color: var(--text-secondary); font-size: 12px; margin: 10px 0 0; }
.work-body { display: flex; flex-direction: column; gap: 10px; min-height: 0; flex: 1; }
.work-head { display: flex; align-items: flex-start; gap: 12px; }
.head-main { flex: 1; min-width: 0; }
.dir-title {
  width: 100%; border: 1px solid transparent; background: transparent; color: var(--text-primary);
  font: inherit; font-weight: 600; font-size: 15px; padding: 5px 10px; border-radius: var(--radius-sm);
  /* 高度写死成次级档 32px：只给内边距会因 line-height 差几像素，和右边的「已有资源」按钮对不齐 */
  box-sizing: border-box; height: 34px;
}
.dir-title:hover { border-color: var(--border); }
.dir-title:focus { outline: none; border-color: var(--accent); background: var(--bg); }
.dir-title[readonly] { cursor: default; border-color: transparent; background: transparent; }
.dir-meta { color: var(--text-secondary); font-size: 12px; padding: 2px 4px; }
.rename-tip { color: var(--text-secondary); font-size: 12px; line-height: 1.6; padding: 2px 4px; }
.drop { padding: 12px; min-height: 0; margin-top: 0; text-align: center; color: var(--text-secondary); font-size: 13px; cursor: pointer; border: 1px dashed var(--border); border-radius: var(--radius-sm); }
.drop.dragover { border-color: var(--accent); background: var(--accent-light); }
/* 上游 .btn-upload 是整宽提交键；这里三处都是行内小按钮 */
.head-actions .btn-upload, .work-empty .btn-upload { width: auto; margin-top: 0; padding: 8px 16px; display: inline-flex; }
.upload-text b, .upload-text { font-weight: 400; }
.file-list { list-style: none; margin: 0; padding: 0; overflow: auto; flex: 1; min-height: 0; }
.file-row { display: flex; align-items: center; gap: 10px; padding: 9px 4px; border-top: 1px solid var(--border); }
.file-info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.file-info b { font-weight: 500; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-info span { color: var(--text-secondary); font-size: 12px; }
.chip {
  flex: none; padding: 2px 8px; border-radius: 999px; font-size: 12px;
  background: var(--bg); color: var(--text-secondary);
}
.chip-ready { background: var(--accent-light); color: var(--accent); }
.chip-running { background: var(--accent-light); color: var(--accent); }
.chip-done { background: rgba(52, 199, 89, .12); color: var(--green); }
.chip-failed, .chip-toolarge, .chip-badname, .chip-unsupported { background: rgba(255, 59, 48, .1); color: var(--danger); }
.chip-dupname { background: rgba(255, 204, 0, .16); color: #997317; }
.chip-btn { border: none; background: transparent; color: var(--accent); font: inherit; font-size: 13px; cursor: pointer; }
/* × 也要有 28px 以上的点击区，并且 hover 底用同一个圆角 */
.rm { border: none; background: transparent; color: var(--text-secondary); cursor: pointer; font-size: 15px; padding: 6px 8px; border-radius: var(--radius-sm); }
.rm[disabled] { opacity: .4; cursor: not-allowed; }
/* 次级档：软底 + 1px 实线 + 8px 圆角 + 13px/500（上游 .btn-upload 是实心蓝底主按钮，这里要的是行内次级） */
.btn-upload.small {
  box-sizing: border-box; height: 34px; padding: 0 12px; font-size: 13px; font-weight: 500;
  background: var(--bg); color: var(--text-primary); border: 1px solid var(--border);
  border-radius: var(--radius-sm); margin-top: 0; width: auto;
}
.btn-upload.small:hover { background: var(--bg); border-color: var(--accent); color: var(--text-primary); }
.btn-upload.small:disabled { opacity: .45; }
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
}
</style>
