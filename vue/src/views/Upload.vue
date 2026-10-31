<template>
  <NavBar />
  <section class="admin-section">
    <div class="container batch">
      <div class="page-heading">
        <h1 class="admin-title">上传资料</h1>
        <p class="admin-subtitle">支持 PDF、Word、PPT、Excel、ZIP、RAR 等格式，单文件最大 200MB。上传后等待管理员审核。</p>
      </div>

      <CoursePicker :store="store" />

      <div class="batch-main">
        <DirRail :store="store" />
        <FileDrop :store="store" />
      </div>

      <QueueBar :store="store" />

      <!-- 整理规范：界面上说不清的规则，收进这份可复制的指令里 -->
      <OrganizeHelp v-if="store.s.helpOpen" @close="store.s.helpOpen = false" />

      <!-- 选完文件夹之后才确认：这样报出来的文件数、子目录数是真实的 -->
      <div v-if="store.s.pendingImport && !store.s.pendingImport.multi" class="batch-mask">
        <div class="batch-card">
          <h3>确认导入</h3>
          <p v-html="confirmText" />
          <div class="batch-actions">
            <button class="btn-upload ghost" type="button" @click="store.cancelImport()">取消</button>
            <button class="btn-upload" type="button" @click="store.confirmImport()">继续导入</button>
          </div>
          <p class="batch-note">请确认文件夹已完成分类整理：导入会替换当前整理的结果。</p>
        </div>
      </div>

      <!-- 一个文件夹里有多门课时，一次只导一门 -->
      <div v-else-if="store.s.pendingImport" class="batch-mask">
        <div class="batch-card">
          <h3>检测到多个学科分类</h3>
          <p>一次导入一个，其余可以再选一次文件夹。</p>
          <button
            v-for="cat in importCategories"
            :key="cat"
            class="batch-choice"
            type="button"
            @click="store.confirmImport(cat)"
          >{{ cat }}<span>{{ countOf(cat) }} 个文件</span></button>
          <button class="btn-upload ghost" type="button" @click="store.cancelImport()">取消</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import NavBar from '../components/NavBar.vue'
import CoursePicker from '../components/uploader/CoursePicker.vue'
import DirRail from '../components/uploader/DirRail.vue'
import FileDrop from '../components/uploader/FileDrop.vue'
import QueueBar from '../components/uploader/QueueBar.vue'
import OrganizeHelp from '../components/uploader/OrganizeHelp.vue'
import { useUploader } from '../uploader/useUploader.js'

const store = useUploader()
onMounted(() => store.init())

const confirmText = computed(() => {
  const p = store.s.pendingImport
  if (!p) return ''
  return `检测到 <b>${p.fileCount}</b> 个文件、<b>${p.dirCount}</b> 个子目录，` +
    `将生成学科分类「<b>${p.category || '未指定'}</b>」。`
})

const importCategories = computed(() => {
  const p = store.s.pendingImport
  if (!p) return []
  return [...new Set(p.groups.map((g) => g.category).filter(Boolean))]
})

const countOf = (category) => {
  const p = store.s.pendingImport
  if (!p) return 0
  return p.groups.filter((g) => g.category === category).reduce((n, g) => n + g.files.length, 0)
}
</script>

<style scoped>
.batch { display: flex; flex-direction: column; gap: 14px; padding-top: 8px; padding-bottom: 28px; }
.batch-main { display: flex; gap: 14px; align-items: stretch; min-height: 380px; }
.batch-mask {
  position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, .35); padding: 20px;
}
.batch-card {
  width: min(460px, 100%); background: var(--surface); color: var(--text-primary);
  border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg); padding: 20px;
}
.batch-card h3 { margin: 0 0 8px; font-size: 16px; }
.batch-card p { margin: 0 0 14px; color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
.batch-card p b { color: var(--text-primary); }
.batch-actions { display: flex; gap: 10px; justify-content: flex-end; }
.batch-note { margin: 12px 0 0; font-size: 12px; }
.batch-choice {
  display: flex; justify-content: space-between; gap: 10px; width: 100%; margin-bottom: 8px;
  background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-sm);
  padding: 10px 12px; font: inherit; font-size: 13px; cursor: pointer; text-align: left;
}
.batch-choice:hover { border-color: var(--accent); background: var(--accent-light); }
.batch-choice span { color: var(--text-secondary); }
.btn-upload.ghost { background: var(--surface); color: var(--text-primary); border: 1px solid var(--border); }
</style>
