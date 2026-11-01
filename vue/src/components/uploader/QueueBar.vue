<template>
  <section class="queue">
    <div class="queue-left">
      <div class="queue-text" v-html="text" />
      <div v-if="store.s.running" class="progress">
        <div class="progress-bar"><i :style="{ width: percent + '%' }" /></div>
        <div class="progress-meta">
          <span>{{ store.s.progress.current || '准备中…' }}</span>
          <span>{{ store.s.progress.doneFiles }}/{{ store.s.progress.totalFiles }}</span>
        </div>
      </div>
    </div>

    <div class="queue-right">
      <label class="opt"><input v-model="store.s.options.skipDup" type="checkbox" :disabled="store.s.running" /> 跳过已存在</label>
      <label v-if="store.isAdmin.value" class="opt">
        <input v-model="store.s.options.autoApprove" type="checkbox" :disabled="store.s.running" /> 自动通过审核
      </label>
      <label class="opt"><input v-model="store.s.options.dryRun" type="checkbox" :disabled="store.s.running" /> 只试运行</label>

      <button
        class="btn-upload small"
        type="button"
        :disabled="store.s.running || !store.allFiles.value.length"
        title="把这一批文件的落点与结果导成 CSV，传断了好核对"
        @click="store.downloadCsv()"
      >导出投递结果（CSV）</button>
      <button v-if="store.s.running" class="btn-upload stop" type="button" @click="store.stop()">停止</button>
      <button
        class="btn-upload"
        type="button"
        :disabled="!store.canUploadAll.value"
        :title="store.courseValid.value ? '' : '先选择或输入一个学科分类'"
        @click="store.uploadAll()"
      >全部上传（{{ store.pendingTargets.value.length }}）</button>
    </div>

    <div v-if="store.s.log.length" ref="logEl" class="log" role="log" aria-live="polite">
      <div v-for="(line, i) in store.s.log.slice(-60)" :key="i" class="log-line" :class="'log-' + line.kind">
        [{{ line.at }}] {{ line.text }}
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ store: { type: Object, required: true } })

const percent = computed(() => {
  const p = props.store.s.progress
  return p.totalBytes > 0 ? Math.min(100, (p.doneBytes / p.totalBytes) * 100) : 0
})

const text = computed(() => {
  const files = props.store.allFiles.value
  const done = files.filter((f) => f.status === 'done').length
  const pending = props.store.pendingTargets.value.length
  return pending || done
    ? `<b>${pending}</b> 个待上传 · ${done} 个已完成`
    : '暂无文件'
})
</script>

<style scoped>
.queue {
  display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
  padding: 12px 16px; background: var(--surface);
  border: 1px solid var(--border); border-radius: var(--radius-md); box-shadow: var(--shadow-sm);
}
.queue-left { flex: 1; min-width: 220px; }
.queue-text { font-size: 12px; color: var(--text-secondary); }
.progress { margin-top: 6px; }
.progress-bar { height: 6px; border-radius: 999px; background: var(--bg); overflow: hidden; }
.progress-bar i { display: block; height: 100%; background: var(--accent); transition: width .2s ease; }
.progress-meta { display: flex; justify-content: space-between; color: var(--text-secondary); font-size: 12px; margin-top: 4px; }
.queue-right { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
/* 主操作档：和课程栏三个盒子同一套（15px/600、10px 上下内边距、8px 圆角 = 42px 高） */
.queue-right .btn-upload {
  width: auto; margin-top: 0; box-sizing: border-box; height: 35px; padding: 0 14px; border: 1px solid transparent;
  font-size: 15px; font-weight: 600; border-radius: var(--radius-sm);
}
.opt { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-secondary); cursor: pointer; }
.btn-upload.stop { background: var(--bg); color: var(--danger); border: 1px solid var(--border); }
/* 次级档（scoped 样式不跨组件，所以 FileDrop 里那条 .btn-upload.small 这里用不上）：
   软底 + 1px 实线 + 8px 圆角 + 13px/500，和「已有资源」「上传设置」同一档 */
.queue-right .btn-upload.small {
  width: auto; margin-top: 0; box-sizing: border-box; height: 34px; padding: 0 12px;
  font-size: 13px; font-weight: 500;
  background: var(--bg); color: var(--text-secondary);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
}
.queue-right .btn-upload.small:hover { background: var(--bg); border-color: var(--accent); color: var(--text-primary); }
.queue-right .btn-upload.small:disabled { opacity: .45; }
.log {
  width: 100%; max-height: 148px; overflow: auto; margin-top: 4px;
  border-top: 1px solid var(--border); padding-top: 8px;
  font-size: 12px; line-height: 1.7; color: var(--text-secondary);
}
.log-ok { color: var(--green); }
.log-err { color: var(--danger); }
</style>
