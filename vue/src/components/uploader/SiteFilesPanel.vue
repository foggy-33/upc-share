<template>
  <div class="site-panel">
    <div class="site-head">
      <b>站内已有</b>
      <span v-if="store.s.site.loading">读取中…</span>
      <span v-else-if="store.s.site.error">{{ store.s.site.error }}</span>
      <span v-else>{{ (store.s.site.items || []).length }} 个文件 · 当前子目录「{{ dir && dir.name ? dir.name : '根目录' }}」</span>
    </div>

    <div v-if="!store.s.site.loading && !(store.s.site.items || []).length" class="site-empty">
      这个子目录里站内还没有文件。
    </div>

    <ul v-else class="site-list">
      <li v-for="item in store.s.site.items" :key="item.id" class="site-row">
        <span class="site-name">{{ item.original_name }}</span>
        <span class="site-meta">{{ item.file_size }} · {{ item.status === 'approved' ? '已通过' : '待审核' }}</span>
        <button
          class="site-del"
          type="button"
          :class="{ armed: armed === item.id }"
          :disabled="store.s.running"
          @click="del(item)"
        >{{ armed === item.id ? '确认删除？' : '删除' }}</button>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  store: { type: Object, required: true },
  dir: { type: Object, default: null }
})

// 删除是这里唯一不可逆的操作，所以做成两段式；4 秒不点第二下自动取消
const armed = ref('')
let timer = null

function del(item) {
  if (armed.value !== item.id) {
    armed.value = item.id
    clearTimeout(timer)
    timer = setTimeout(() => { armed.value = '' }, 4000)
    return
  }
  clearTimeout(timer)
  armed.value = ''
  props.store.deleteSiteFile(item, props.dir)
}
</script>

<style scoped>
.site-panel {
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg); padding: 10px 12px; max-height: 240px; overflow: auto;
}
.site-head { display: flex; align-items: baseline; gap: 8px; font-size: 13px; }
.site-head span { color: var(--text-secondary); font-size: 12px; }
.site-empty { padding: 8px 0; color: var(--text-secondary); font-size: 13px; }
.site-list { list-style: none; margin: 6px 0 0; padding: 0; }
.site-row { display: flex; align-items: center; gap: 10px; padding: 7px 0; border-top: 1px solid var(--border); font-size: 13px; }
.site-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.site-meta { color: var(--text-secondary); font-size: 12px; flex: none; }
.site-del {
  border: 1px solid var(--border); background: var(--surface); color: var(--danger);
  font: inherit; font-size: 12px; border-radius: var(--radius-sm); padding: 3px 9px; cursor: pointer; flex: none;
}
.site-del.armed { background: var(--danger); color: #fff; border-color: var(--danger); }
.site-del[disabled] { opacity: .5; cursor: not-allowed; }
</style>
