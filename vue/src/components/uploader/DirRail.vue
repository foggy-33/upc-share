<template>
  <aside class="dir-rail">
    <div class="rail-head">
      <b>子目录</b>
      <span>{{ store.s.course.dirs.length }} 个</span>
    </div>

    <ul class="dir-list">
      <li
        v-for="dir in store.s.course.dirs"
        :key="dirKey(dir)"
        class="dir-row"
        :class="{ on: dir === store.s.activeDir }"
        role="button"
        tabindex="0"
        :aria-pressed="dir === store.s.activeDir ? 'true' : 'false'"
        @click="select(dir)"
        @keydown.enter.prevent="select(dir)"
        @keydown.space.prevent="select(dir)"
      >
        <span class="dir-name">{{ dir.name || '根目录' }}</span>
        <span class="dir-count">{{ pendingOf(dir) }}</span>
        <button
          v-if="dir === store.s.activeDir"
          class="dir-upload"
          type="button"
          :disabled="!store.canUploadDir.value"
          :title="store.courseValid.value ? '只上传这个子目录' : '先选择或输入一个学科分类'"
          @click.stop="store.uploadDir(dir)"
        >上传{{ pendingOf(dir) ? '（' + pendingOf(dir) + '）' : '' }}</button>
        <!-- 行右侧不再放「⋯ 更多操作」：能删的只有"删除这个子目录"一件事，
             直接给一颗两段式垃圾桶——第一次点只武装（摇一下 + 加深），第二次才删。 -->
        <button
          class="dir-del"
          :class="{ armed: armed === dir }"
          type="button"
          aria-label="删除这个子目录"
          :disabled="!store.canRemoveDir(dir) || store.s.running"
          :title="delTitle(dir)"
          @click.stop="armOrDelete(dir)"
        ><svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path fill="currentColor" d="M9 3h6l1 2h4v2H4V5h4l1-2Zm-3 6h12l-1 11a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2L6 9Zm4 2v9h2v-9h-2Zm4 0v9h2v-9h-2Z"/></svg></button>
      </li>
    </ul>

    <button
      class="add-dir"
      type="button"
      :disabled="!store.courseValid.value || store.s.running"
      :title="store.courseValid.value ? '' : '先选择或输入一个学科分类，再建子目录'"
      @click="store.addDir()"
    >＋ 新建子目录</button>
  </aside>
</template>

<script setup>
import { ref } from 'vue'
import { collectDirTargets } from '../../uploader/logic.js'

const props = defineProps({ store: { type: Object, required: true } })

const armed = ref(null)
let armedTimer = null

const dirKey = (dir) => `${dir.name}::${dir.files.length}`
const pendingOf = (dir) => collectDirTargets(props.store.s.course, dir, props.store.s.options.skipDup).length

function select(dir) {
  props.store.s.activeDir = dir
  if (props.store.s.site.show) {
    props.store.s.site.items = null
    props.store.s.site.key = ''
  }
}

function delTitle(dir) {
  if (dir.isRoot) return '根目录就是学科分类本身，删不掉；要清文件去「已有资源」'
  if (!props.store.canRemoveDir(dir)) return '站内已有的子目录删不掉：站点没有「目录」这个实体，去「已有资源」把里面的文件删完它就消失了'
  if (armed.value === dir) return dir.files.length ? `再点一次确认删除（会丢掉这 ${dir.files.length} 个待传文件）` : '再点一次确认删除这个空子目录'
  return '删除子目录（要点两次确认）'
}

/* 第一次点只"武装"（摇一下 + 底色加深），4 秒内再点一次才真删；超时自动退回 */
function armOrDelete(dir) {
  if (!props.store.canRemoveDir(dir) || props.store.s.running) return
  if (armed.value === dir) {
    clearTimeout(armedTimer)
    armed.value = null
    props.store.removeDir(dir)
    return
  }
  armed.value = dir
  clearTimeout(armedTimer)
  armedTimer = setTimeout(() => { armed.value = null }, 4000)
}
</script>

<style scoped>
.dir-rail {
  width: 240px; flex: none; display: flex; flex-direction: column;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-md); box-shadow: var(--shadow-sm);
  min-height: 0;
}
.rail-head { display: flex; align-items: baseline; gap: 8px; padding: 14px 14px 8px; }
.rail-head b { font-size: 15px; font-weight: 600; }
.rail-head span { color: var(--text-secondary); font-size: 12px; }
.dir-list { list-style: none; margin: 0; padding: 0 8px 10px; overflow: auto; flex: 1; }
.dir-row {
  display: flex; align-items: center; gap: 8px; padding: 9px 10px;
  border-radius: var(--radius-sm); cursor: pointer;
}
.dir-row:hover { background: var(--bg); }
.dir-row.on { background: var(--accent-light); color: var(--accent); font-weight: 600; }
.dir-row:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--accent-light); }
.dir-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.dir-count { color: var(--text-secondary); font-size: 12px; }
.dir-upload {
  border: none; background: transparent; color: var(--accent);
  font: inherit; font-size: 13px; cursor: pointer; padding: 3px 7px; border-radius: 6px; flex: none;
}
.dir-upload[disabled] { opacity: .45; cursor: not-allowed; }
/* 行右侧的删除按钮：浅红底 + 红色垃圾桶；第一次点击加深并摇一下，第二次才真删 */
.dir-del {
  border: none; border-radius: 6px; background: var(--danger-light, rgba(255, 59, 48, .1));
  color: var(--danger); width: 26px; height: 26px; flex: none;
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
}
.dir-del:hover { background: var(--danger); color: var(--surface); }
.dir-del:disabled { background: transparent; color: var(--text-secondary); opacity: .4; cursor: not-allowed; }
.dir-del:disabled:hover { background: transparent; color: var(--text-secondary); }
.dir-del.armed { background: var(--danger); color: var(--surface); animation: shake .32s ease; }
@keyframes shake {
  0%, 100% { transform: translateX(0) }
  20% { transform: translateX(-3px) }
  40% { transform: translateX(3px) }
  60% { transform: translateX(-2px) }
  80% { transform: translateX(2px) }
}
.add-dir {
  /* 搬到列表上方了：外边距跟着改，高度写成次级档 32px，和左栏标题、右侧按钮同一套 */
  margin: 0 10px 10px; padding: 5px 10px; text-align: left;
  box-sizing: border-box; height: 32px; font-weight: 500;
  border: 1px dashed var(--border); border-radius: var(--radius-sm);
  background: transparent; color: var(--accent); font: inherit; font-size: 13px; cursor: pointer;
}
.add-dir:hover { border-color: var(--accent); background: var(--accent-light); }
.add-dir[disabled] { opacity: .45; cursor: not-allowed; }
.add-dir[disabled]:hover { border-color: var(--border); background: transparent; }
</style>
