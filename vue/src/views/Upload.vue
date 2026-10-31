<template>
  <NavBar />
  <section class="admin-section batch-page">
    <div class="container batch">
      <div class="page-heading">
        <h1 class="admin-title">上传资料</h1>
        <p class="admin-subtitle">支持 PDF、Word、PPT、Excel、ZIP、RAR 等格式，单文件最大 200MB。上传后等待管理员审核。</p>
      </div>

      <!-- 未登录时先说清楚：否则只会在日志里看到一行 401 -->
      <div v-if="store.s.me && !store.s.me.logged_in" class="batch-login">
        <b>请先登录</b>：这个页面要读到你的学科分类才能整理和上传。
        <router-link to="/login">去登录</router-link>
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
/* 这一页是工作台，不是落地页：站点默认给 .admin-section 留 64px 上 / 80px 下，
   .admin-subtitle 又带 36px 下边距，叠起来近 190px 的空白把三栏挤到屏幕下半截。 */
.batch-page { padding: 20px 0 32px; }
/* 按视口铺满：导航 52 + 上 20 + 下 32 扣掉，三栏吃掉剩余高度，队列条贴底 */
.batch-page .container.batch { min-height: calc(100vh - 104px); }
.batch-page .page-heading { margin-bottom: 14px; }
.batch-page .admin-title { font-size: 26px; }
.batch-page .admin-subtitle { margin-top: 4px; margin-bottom: 0; font-size: 13px; }
.batch-main { display: flex; gap: 14px; align-items: stretch; min-height: 380px; flex: 1 1 auto; }
/* 未登录提示：和站点其它提示同一档字号，不抢戏 */
.batch-login {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 10px 14px; border: 1px solid #f0c36d; background: #fdf6e3; color: #8a5a00;
  border-radius: var(--radius-sm); font-size: 13px;
}
.batch-login b { color: #6b4700; }
.batch-login a { color: var(--accent); }
.batch-mask {
  position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, .35); padding: 20px;
}
.batch-card {
  width: min(460px, 100%); background: var(--surface); color: var(--text-primary);
  border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg); padding: 20px;
}
.batch-card h3 { margin: 0 0 8px; font-size: 15px; font-weight: 600; }
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
