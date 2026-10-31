<template>
  <aside class="dir-rail">
    <div class="rail-head">
      <b>子目录</b>
      <span>{{ store.s.course.dirs.length }} 个</span>
    </div>

    <button
      class="add-dir"
      type="button"
      :disabled="!store.courseValid.value || store.s.running"
      :title="store.courseValid.value ? '' : '先选择或输入一个学科分类，再建子目录'"
      @click="store.addDir()"
    >＋ 新建子目录</button>
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
        <div class="dir-more-wrap" @click.stop>
          <button class="dir-more" type="button" aria-label="子目录操作" :aria-expanded="menuFor === dir ? 'true' : 'false'" @click="toggleMenu(dir)">⋯</button>
          <div v-if="menuFor === dir" class="dir-menu" role="menu">
            <button class="dir-menu-item" type="button" role="menuitem" :disabled="!store.canUploadDir.value" @click="act('upload', dir)">上传本子目录</button>
            <button v-if="store.canRenameDir(dir)" class="dir-menu-item" type="button" role="menuitem" @click="act('rename', dir)">重命名</button>
            <button v-if="store.canRenameDir(dir)" class="dir-menu-item danger" type="button" role="menuitem" @click="act('remove', dir)">从本次列表移除</button>
            <div v-if="!store.canRenameDir(dir)" class="dir-menu-note">站内已有的子目录不能改名或删除</div>
          </div>
        </div>
      </li>
    </ul>

  </aside>
</template>

<script setup>
import { ref } from 'vue'
import { collectDirTargets } from '../../uploader/logic.js'

const props = defineProps({ store: { type: Object, required: true } })

const menuFor = ref(null)

const dirKey = (dir) => `${dir.name}::${dir.files.length}`
const pendingOf = (dir) => collectDirTargets(props.store.s.course, dir, props.store.s.options.skipDup).length

function select(dir) {
  props.store.s.activeDir = dir
  if (props.store.s.site.show) {
    props.store.s.site.items = null
    props.store.s.site.key = ''
  }
}

function toggleMenu(dir) {
  menuFor.value = menuFor.value === dir ? null : dir
}

function act(kind, dir) {
  menuFor.value = null
  if (kind === 'upload') props.store.uploadDir(dir)
  else if (kind === 'rename') props.store.requestRename(dir)
  else if (kind === 'remove') props.store.removeDir(dir)
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
.dir-more-wrap { position: relative; flex: none; }
.dir-more {
  border: none; background: transparent; color: var(--text-secondary);
  font: inherit; font-size: 13px; cursor: pointer; padding: 3px 7px; border-radius: 6px;
}
.dir-more:hover { background: var(--bg); color: var(--text-primary); }
.dir-menu {
  position: absolute; right: 0; top: 24px; z-index: 40; width: 168px;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-md); box-shadow: var(--shadow-md); padding: 6px;
}
.dir-menu-item {
  display: block; width: 100%; text-align: left; border: none; background: transparent;
  color: var(--text-primary); padding: 8px 9px; border-radius: var(--radius-sm);
  cursor: pointer; font: inherit; font-size: 13px;
}
.dir-menu-item:hover { background: var(--bg); }
.dir-menu-item[disabled] { opacity: .5; cursor: not-allowed; }
.dir-menu-item.danger { color: var(--danger); }
.dir-menu-note { padding: 6px 9px; color: var(--text-secondary); font-size: 12px; line-height: 1.5; }
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
