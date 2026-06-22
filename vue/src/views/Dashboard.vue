<template>
  <NavBar />
  <section class="dash-section">
    <div class="dash-header">
      <div>
        <h1 class="dash-title">管理后台</h1>
        <p class="dash-subtitle">审核上传、维护资料和管理用户状态</p>
      </div>
      <div class="dash-main-tabs">
        <button class="dash-main-tab" :class="{active: view === 'files'}" @click="switchView('files')">文件管理</button>
        <button class="dash-main-tab" :class="{active: view === 'users'}" @click="switchView('users')">用户管理</button>
        <button class="dash-main-tab" :class="{active: view === 'audit'}" @click="switchView('audit')">风控日志</button>
        <button class="dash-main-tab" :class="{active: view === 'notice'}" @click="switchView('notice')">公告管理</button>
        <button v-if="canManageAdmins" class="dash-main-tab" :class="{active: view === 'contentAdmins'}" @click="switchView('contentAdmins')">内容管理员</button>
      </div>
    </div>

    <div v-if="view !== 'notice' && view !== 'contentAdmins'" class="dash-toolbar">
      <div class="dash-tabs" v-if="view === 'files'">
        <button class="dash-tab" :class="{active: status === 'pending'}" @click="status = 'pending'; load()">待审核</button>
        <button class="dash-tab" :class="{active: status === ''}" @click="status = ''; load()">全部文件</button>
      </div>
      <div class="dash-tabs" v-if="view === 'audit'">
        <button class="dash-tab" :class="{active: auditMode === 'content'}" @click="switchAudit('content')">内容审计</button>
        <button class="dash-tab" :class="{active: auditMode === 'events'}" @click="switchAudit('events')">系统日志</button>
        <button class="dash-tab" :class="{active: auditMode === 'sensitive-users'}" @click="switchAudit('sensitive-users')">敏感用户</button>
        <button class="dash-tab" :class="{active: auditMode === 'blacklist'}" @click="switchAudit('blacklist')">IP 黑名单</button>
      </div>
      <div v-if="view === 'users' && canManageAdmins" class="bulk-level-bar">
        <span>已选择 {{ selectedUids.length }} 人</span>
        <span v-if="bulkMessage" class="bulk-level-message">{{ bulkMessage }}</span>
        <button class="action-btn" type="button" @click="toggleAllCurrentPage">
          {{ allUsersSelected ? '取消全选' : '全选当前页' }}
        </button>
        <select v-model="bulkLevel" class="form-input level-select">
          <option value="">选择目标等级</option>
          <option value="auto">自动等级</option>
          <option value="gray">灰色 · 刚注册</option>
          <option value="blue">蓝色 · 正式用户</option>
          <option value="green">绿色 · 贡献者</option>
          <option value="yellow">黄色 · 活跃达人</option>
          <option value="orange">橙色 · 社区之星</option>
          <option value="admin">紫色 · 管理员</option>
        </select>
        <button class="action-btn approve" :disabled="loading || !selectedUids.length || !bulkLevel" @click="bulkLevelAction">批量设置</button>
      </div>
      <div class="dash-search-row">
        <input v-model="q" class="form-input dash-search" placeholder="搜索..." @input="search" />
        <button class="action-btn" :disabled="loading" @click="load">{{ loading ? '加载中' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="error" class="dash-error">{{ error }}</div>

    <div v-if="view === 'notice'" class="content-admin-panel">
      <section class="content-admin-box notice-admin-box">
        <div class="notice-admin-heading">
          <div>
            <h2>首页公告</h2>
            <p>保存后将立即显示在首页顶部公告卡片中。</p>
          </div>
          <span>{{ siteNotice.length }}/2000</span>
        </div>
        <textarea
          v-model="siteNotice"
          class="form-input notice-admin-textarea"
          rows="8"
          maxlength="2000"
          placeholder="请输入首页公告内容"
        ></textarea>
        <div class="notice-admin-actions">
          <span v-if="noticeMessage" class="notice-admin-message">{{ noticeMessage }}</span>
          <button class="action-btn" type="button" :disabled="loading" @click="loadNotice">恢复当前公告</button>
          <button class="action-btn approve" type="button" :disabled="loading" @click="saveNotice">
            {{ loading ? '保存中...' : '保存公告' }}
          </button>
        </div>
        <div class="notice-admin-preview">
          <strong>首页效果预览</strong>
          <p>{{ siteNotice || '公告内容为空' }}</p>
        </div>
      </section>
    </div>

    <div v-else-if="view === 'contentAdmins'" class="content-admin-panel">
      <div class="content-admin-layout">
        <section class="content-admin-box content-group-editor">
          <div class="content-box-heading">
            <div>
              <span class="content-step">1</span>
              <h2>{{ groupForm.id ? '编辑管理组' : '新建管理组' }}</h2>
              <p>设置这个分组可管理的内容范围与后台权限。</p>
            </div>
            <button v-if="groupForm.id" class="action-btn" type="button" @click="resetGroupForm">取消编辑</button>
          </div>

          <div class="content-scope-grid">
            <label class="content-field">
              <span>分组名称</span>
              <input v-model="groupForm.group_name" class="form-input" placeholder="例如：论坛内容组" />
            </label>
            <label class="content-field">
              <span>论坛板块范围</span>
              <MultiSelectDropdown v-model="groupForm.log_categories" :options="forumSectionOptions" placeholder="选择论坛板块" />
            </label>
            <label class="content-field">
              <span>资料分类范围</span>
              <MultiSelectDropdown v-model="groupForm.album_categories" :options="resourceCategoryOptions" placeholder="选择资料分类" />
            </label>
            <label class="content-field">
              <span>可管理用户等级</span>
              <MultiSelectDropdown v-model="groupForm.user_groups" :options="userLevelOptions" placeholder="选择用户等级" />
            </label>
          </div>

          <div class="content-permission-section">
            <strong>权限设置</strong>
            <div class="content-permission-grid">
              <label><input v-model="groupForm.can_enter_user_backend" type="checkbox" /><span><b>用户后台</b><small>查看授权等级的用户</small></span></label>
              <label><input v-model="groupForm.can_modify_user" type="checkbox" /><span><b>修改用户</b><small>锁定或解锁用户</small></span></label>
              <label><input v-model="groupForm.can_modify_user_group" type="checkbox" /><span><b>修改用户组</b><small>调整用户所属等级</small></span></label>
              <label><input v-model="groupForm.can_manage_user_template" type="checkbox" /><span><b>用户模板</b><small>维护用户权限模板</small></span></label>
              <label><input v-model="groupForm.can_publish_site_notice" type="checkbox" /><span><b>站点公告</b><small>修改首页公告</small></span></label>
              <label><input v-model="groupForm.can_publish_notification" type="checkbox" /><span><b>发布通知</b><small>发送站内通知</small></span></label>
            </div>
          </div>

          <div class="content-form-actions">
            <button class="action-btn" type="button" @click="resetGroupForm">重置</button>
            <button class="action-btn approve" :disabled="!groupForm.group_name.trim()" @click="saveGroup">
              {{ groupForm.id ? '保存修改' : '创建管理组' }}
            </button>
          </div>
        </section>

        <section class="content-admin-box content-member-box">
          <div class="content-box-heading">
            <div>
              <span class="content-step">2</span>
              <h2>分配管理员</h2>
              <p>搜索用户名或 UID，勾选用户后批量分配管理组。</p>
            </div>
          </div>
          <div class="content-member-form">
            <div class="content-user-picker">
              <input v-model="memberSearch" class="form-input" placeholder="搜索用户名或 UID" @input="searchMembers" />
              <div v-if="memberSearchLoading" class="content-user-search-state">正在搜索...</div>
              <div v-else-if="memberCandidates.length" class="content-user-results">
                <label v-for="user in memberCandidates" :key="user.uid">
                  <input type="checkbox" :checked="selectedMemberUids.includes(user.uid)" @change="toggleMemberUser(user.uid)" />
                  <span class="content-member-avatar">{{ String(user.username || '?').slice(0, 1).toUpperCase() }}</span>
                  <span><strong>{{ user.username }}</strong><small>UID {{ user.uid }} · {{ levelLabel(user.effective_level) }}</small></span>
                </label>
              </div>
              <div v-else-if="memberSearch.trim().length >= 1" class="content-user-search-state">没有匹配用户</div>
            </div>
            <select v-model="memberGroupId" class="form-input">
              <option value="">选择管理组</option>
              <option v-for="group in contentGroups" :key="group.id" :value="group.id">{{ group.group_name }}</option>
            </select>
            <button class="action-btn approve" :disabled="!selectedMemberUids.length || !memberGroupId" @click="saveMember">
              分配已选 {{ selectedMemberUids.length }} 人
            </button>
          </div>
          <div class="content-member-list">
            <article v-for="member in contentMembers" :key="member.uid" class="content-member-row">
              <span class="content-member-avatar">{{ String(member.username || '?').slice(0, 1).toUpperCase() }}</span>
              <span><strong>{{ member.username }}</strong><small>UID {{ member.uid }}</small></span>
              <span class="content-group-badge">{{ member.group_name }}</span>
              <button class="action-btn delete" @click="removeMember(member.uid)">移除</button>
            </article>
            <div v-if="contentMembers.length === 0" class="empty-state compact">暂未分配内容管理员</div>
          </div>
        </section>
      </div>

      <section class="content-admin-box">
        <div class="content-box-heading">
          <div>
            <span class="content-step">3</span>
            <h2>已有管理组</h2>
            <p>点击卡片即可载入上方表单进行编辑。</p>
          </div>
          <span class="content-group-count">{{ contentGroups.length }} 个分组</span>
        </div>
        <div class="content-group-cards">
          <button v-for="group in contentGroups" :key="group.id" class="content-group-card" :class="{ active: groupForm.id === group.id }" @click="editGroup(group)">
            <span class="content-group-card-head">
              <strong>{{ group.group_name }}</strong>
              <small>{{ groupMemberCount(group.id) }} 名成员</small>
            </span>
            <span class="content-group-scope"><b>论坛</b>{{ scopeLabel(group.log_categories) }}</span>
            <span class="content-group-scope"><b>资料</b>{{ scopeLabel(group.album_categories) }}</span>
            <span class="content-group-permissions">
              <em v-for="permission in permissionLabels(group)" :key="permission">{{ permission }}</em>
              <em v-if="permissionLabels(group).length === 0" class="muted">仅内容范围权限</em>
            </span>
          </button>
          <div v-if="contentGroups.length === 0" class="empty-state compact">还没有内容管理组</div>
        </div>
      </section>
    </div>

    <div v-else class="file-table-wrap dash-table">
      <table class="file-table">
        <thead>
          <tr v-if="view === 'files'"><th>文件名</th><th>学科</th><th>上传者</th><th>大小</th><th>状态</th><th>操作</th></tr>
          <tr v-else-if="view === 'users'">
            <th v-if="canManageAdmins" class="bulk-check-cell"><input type="checkbox" :checked="allUsersSelected" aria-label="全选当前页用户" @change="toggleAllUsers" /></th>
            <th>UID</th><th>用户名</th><th>积分</th><th>级别</th><th>注册时间</th><th>角色</th><th>下载次数</th><th>下载总量</th><th>状态</th>
          </tr>
          <tr v-else-if="auditMode === 'content'"><th>类型</th><th>发表者</th><th>IP</th><th>标题</th><th>内容摘取</th><th>时间</th><th>操作</th></tr>
          <tr v-else-if="auditMode === 'events'"><th>事件</th><th>用户</th><th>IP</th><th>标题</th><th>内容摘取</th><th>时间</th></tr>
          <tr v-else-if="auditMode === 'sensitive-users'"><th>用户</th><th>IP</th><th>命中词</th><th>来源</th><th>时间</th><th>操作</th></tr>
          <tr v-else><th>IP</th><th>原因</th><th>加入时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="`${view}-${auditMode}-${item.id || item.uid || item.ip_address}`">
            <template v-if="view === 'files'">
              <td>
                <div class="file-name-cell">
                  <span class="file-ext-badge" :class="extClass(item.extension)">{{ cleanExt(item.extension) }}</span>
                  <span class="file-name-text">{{ item.original_name }}</span>
                </div>
              </td>
              <td>{{ item.category }}<span v-if="item.sub_category">/{{ item.sub_category }}</span></td>
              <td>{{ item.uploader || 'system' }}</td>
              <td>{{ item.file_size }}</td>
              <td><span class="status-pill" :class="item.status">{{ statusLabel(item.status) }}</span></td>
              <td>
                <div class="action-row">
                  <a class="action-btn download" :href="`/api/download/${item.id}`">下载</a>
                  <button v-if="item.status === 'pending'" class="action-btn approve" @click="fileAction('approve', item.id)">通过</button>
                  <button v-if="item.status === 'pending'" class="action-btn reject" @click="fileAction('reject', item.id)">拒绝</button>
                  <button v-if="item.status !== 'pending'" class="action-btn delete" @click="fileAction('delete', item.id)">删除</button>
                </div>
              </td>
            </template>
            <template v-else-if="view === 'users'">
              <td v-if="canManageAdmins" class="bulk-check-cell">
                <input
                  v-if="item.username !== 'foggy'"
                  type="checkbox"
                  :value="item.uid"
                  :checked="selectedUids.includes(item.uid)"
                  :aria-label="`选择用户 ${displayUser(item)}`"
                  @change="toggleUser(item.uid)"
                />
              </td>
              <td><span class="user-uid">{{ item.uid }}</span></td>
              <td>
                <div class="user-name-cell">
                  <span class="user-name-text level-username" :class="`level-${item.effective_level || 'gray'}`">{{ displayUser(item) }}</span>
                </div>
              </td>
              <td>{{ formatPoints(item.points) }}</td>
              <td>
                <select
                  v-if="canManageAdmins && item.username !== 'foggy'"
                  class="level-select"
                  :value="item.is_admin ? 'admin' : item.user_level"
                  :disabled="busyUid === item.uid"
                  @change="levelAction(item, $event.target.value)"
                >
                  <option value="auto">自动</option>
                  <option value="gray">灰色 · 刚注册</option>
                  <option value="blue">蓝色 · 正式用户</option>
                  <option value="green">绿色 · 贡献者</option>
                  <option value="yellow">黄色 · 活跃达人（50 积分）</option>
                  <option value="orange">橙色 · 社区之星（200 积分）</option>
                  <option value="admin">紫色 · 管理员</option>
                </select>
                <span v-else class="user-level-badge" :class="`level-${item.effective_level || 'gray'}`">{{ levelLabel(item.effective_level) }}</span>
              </td>
              <td>{{ formatUserTime(item.created_at) }}</td>
              <td>{{ item.is_admin ? '管理员' : '普通用户' }}</td>
              <td>{{ item.download_count ?? 0 }}</td>
              <td>{{ item.download_size || '0 B' }}</td>
              <td>
                <span class="status-pill" :class="item.is_active ? 'approved' : 'rejected'">
                  {{ item.is_active ? '正常' : '已封禁' }}
                </span>
              </td>
            </template>
            <template v-else-if="auditMode === 'content'">
              <td>{{ sourceLabel(item.source_type) }}</td>
              <td>{{ item.username || '-' }}</td>
              <td><button class="text-link" @click="filterIp(item.ip_address)">{{ item.ip_address || '-' }}</button></td>
              <td>{{ item.title || '-' }}</td>
              <td class="audit-snippet">{{ item.content_snippet || '-' }}</td>
              <td>{{ formatUserTime(item.created_at) }}</td>
              <td><button class="action-btn delete" :disabled="!item.ip_address || loading" @click="clearIp(item.ip_address)">清理并拉黑</button></td>
            </template>
            <template v-else-if="auditMode === 'events'">
              <td>{{ eventLabel(item.event_type) }}</td>
              <td>{{ item.username || '-' }}</td>
              <td><button class="text-link" @click="filterIp(item.ip_address)">{{ item.ip_address || '-' }}</button></td>
              <td>{{ item.title || '-' }}</td>
              <td class="audit-snippet">{{ item.content_snippet || '-' }}</td>
              <td>{{ formatUserTime(item.created_at) }}</td>
            </template>
            <template v-else-if="auditMode === 'sensitive-users'">
              <td>{{ item.username || item.user_id || '-' }}</td>
              <td><button class="text-link" @click="filterIp(item.ip_address)">{{ item.ip_address || '-' }}</button></td>
              <td>{{ item.matched_words || '-' }}</td>
              <td>{{ sourceLabel(item.source_type) }}</td>
              <td>{{ formatUserTime(item.created_at) }}</td>
              <td><button class="action-btn delete" :disabled="!item.ip_address || loading" @click="clearIp(item.ip_address)">清理并拉黑</button></td>
            </template>
            <template v-else>
              <td>{{ item.ip_address }}</td>
              <td>{{ item.reason || '-' }}</td>
              <td>{{ formatUserTime(item.created_at) }}</td>
            </template>
          </tr>
          <tr v-if="items.length === 0">
            <td :colspan="emptyColspan"><div class="empty-state compact">暂无数据</div></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="dash-pagination" v-if="pages > 1">
      <button :disabled="loading || page <= 1" @click="goPage(page - 1)">上一页</button>
      <button disabled>第 {{ page }} / {{ pages }} 页，共 {{ total }} 条</button>
      <button :disabled="loading || page >= pages" @click="goPage(page + 1)">下一页</button>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import NavBar from '../components/NavBar.vue'
import MultiSelectDropdown from '../components/MultiSelectDropdown.vue'
import { api } from '../api/http'

const view = ref('files')
const status = ref('pending')
const auditMode = ref('content')
const q = ref('')
const page = ref(1)
const pages = ref(0)
const total = ref(0)
const items = ref([])
const contentGroups = ref([])
const contentMembers = ref([])
const loading = ref(false)
const error = ref('')
const busyUid = ref('')
const canManageAdmins = ref(false)
const siteNotice = ref('')
const noticeMessage = ref('')
const selectedUids = ref([])
const bulkLevel = ref('')
const bulkMessage = ref('')
const memberSearch = ref('')
const memberCandidates = ref([])
const selectedMemberUids = ref([])
const memberSearchLoading = ref(false)
const memberGroupId = ref('')
const resourceCategories = ref([])
const groupForm = ref(emptyGroup())
const emptyColspan = computed(() => {
  if (view.value === 'files') return 6
  if (view.value === 'users') return canManageAdmins.value ? 10 : 9
  if (auditMode.value === 'content') return 7
  if (auditMode.value === 'events') return 6
  if (auditMode.value === 'sensitive-users') return 6
  return 3
})
const selectableUserUids = computed(() => items.value
  .filter(item => view.value === 'users' && item.username !== 'foggy')
  .map(item => item.uid))
const allUsersSelected = computed(() => selectableUserUids.value.length > 0 &&
  selectableUserUids.value.every(uid => selectedUids.value.includes(uid)))
const forumSectionOptions = [
  { value: '前沿快讯', label: '前沿快讯' },
  { value: '资源分享', label: '资源分享' },
  { value: '求助', label: '求助' },
  { value: '灌水区', label: '灌水区' }
]
const userLevelOptions = [
  { value: 'gray', label: '灰色 · 刚注册' },
  { value: 'blue', label: '蓝色 · 正式用户' },
  { value: 'green', label: '绿色 · 贡献者' },
  { value: 'yellow', label: '黄色 · 活跃达人' },
  { value: 'orange', label: '橙色 · 社区之星' }
]
const resourceCategoryOptions = computed(() => resourceCategories.value.map(value => ({ value, label: value })))
let timer = 0
let memberSearchTimer = 0

onMounted(async () => {
  try {
    const me = await api('/api/auth/me')
    canManageAdmins.value = me.username === 'foggy'
  } catch {
    canManageAdmins.value = false
  }
  await load()
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (view.value === 'notice') {
      await loadNotice()
      return
    }
    if (view.value === 'contentAdmins') {
      await loadContentAdmins()
      return
    }
    const params = new URLSearchParams({ page: page.value, size: 50, t: Date.now() })
    if (q.value) params.set('q', q.value)
    if (view.value === 'files' && status.value) params.set('status', status.value)
    const path = view.value === 'audit' ? `/api/admin/audit/${auditMode.value}` : `/api/admin/${view.value}`
    const data = await api(`${path}?${params}`)
    items.value = view.value === 'users' ? (data.items || []).map(normalizeUser) : data.items
    if (view.value === 'users') selectedUids.value = []
    pages.value = Number(data.pages || 0)
    total.value = Number(data.total || 0)
  } catch (e) {
    items.value = []
    pages.value = 0
    total.value = 0
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadContentAdmins() {
  const [groups, members, categories] = await Promise.all([
    api('/api/admin/content-admin/groups'),
    api('/api/admin/content-admin/members'),
    api('/api/categories')
  ])
  contentGroups.value = groups.items || []
  contentMembers.value = members.items || []
  resourceCategories.value = categories || []
  items.value = []
  pages.value = 0
  total.value = contentMembers.value.length
}

function switchView(next) {
  view.value = next
  page.value = 1
  q.value = ''
  noticeMessage.value = ''
  selectedUids.value = []
  bulkLevel.value = ''
  bulkMessage.value = ''
  load()
}

async function loadNotice() {
  loading.value = true
  error.value = ''
  noticeMessage.value = ''
  try {
    const data = await api('/api/admin/settings/site-notice')
    siteNotice.value = data.value || ''
  } catch (e) {
    error.value = e.message || '公告加载失败'
  } finally {
    loading.value = false
  }
}

async function saveNotice() {
  if (siteNotice.value.length > 2000) return
  loading.value = true
  error.value = ''
  noticeMessage.value = ''
  try {
    const data = await api('/api/admin/settings/site-notice', {
      method: 'POST',
      body: JSON.stringify({ value: siteNotice.value })
    })
    siteNotice.value = data.value || ''
    noticeMessage.value = '首页公告已保存'
  } catch (e) {
    error.value = e.message || '公告保存失败'
  } finally {
    loading.value = false
  }
}

async function saveGroup() {
  await api('/api/admin/content-admin/groups', { method: 'POST', body: JSON.stringify(groupForm.value) })
  resetGroupForm()
  await load()
}

function resetGroupForm() {
  groupForm.value = emptyGroup()
}

function editGroup(group) {
  groupForm.value = {
    id: group.id,
    group_name: group.group_name || '',
    log_categories: group.log_categories || '',
    album_categories: group.album_categories || '',
    user_groups: group.user_groups || '',
    can_modify_user: boolValue(group.can_modify_user),
    can_enter_user_backend: boolValue(group.can_enter_user_backend),
    can_modify_user_group: boolValue(group.can_modify_user_group),
    can_manage_user_template: boolValue(group.can_manage_user_template),
    can_publish_site_notice: boolValue(group.can_publish_site_notice),
    can_publish_notification: boolValue(group.can_publish_notification)
  }
}

async function saveMember() {
  await api('/api/admin/content-admin/members/bulk', {
    method: 'POST',
    body: JSON.stringify({ uids: selectedMemberUids.value, group_id: memberGroupId.value })
  })
  memberSearch.value = ''
  memberCandidates.value = []
  selectedMemberUids.value = []
  memberGroupId.value = ''
  await load()
}

function searchMembers() {
  clearTimeout(memberSearchTimer)
  const keyword = memberSearch.value.trim()
  if (!keyword) {
    memberCandidates.value = []
    return
  }
  memberSearchTimer = setTimeout(loadMemberCandidates, 250)
}

async function loadMemberCandidates() {
  const keyword = memberSearch.value.trim()
  if (!keyword) return
  memberSearchLoading.value = true
  try {
    const data = await api(`/api/admin/users?q=${encodeURIComponent(keyword)}&page=1&size=20`)
    memberCandidates.value = (data.items || []).filter(user => user.username !== 'foggy')
  } finally {
    memberSearchLoading.value = false
  }
}

function toggleMemberUser(uid) {
  selectedMemberUids.value = selectedMemberUids.value.includes(uid)
    ? selectedMemberUids.value.filter(value => value !== uid)
    : [...selectedMemberUids.value, uid]
}

async function removeMember(uid) {
  await api(`/api/admin/content-admin/members/${uid}`, { method: 'DELETE' })
  await load()
}

function toggleUser(uid) {
  selectedUids.value = selectedUids.value.includes(uid)
    ? selectedUids.value.filter(value => value !== uid)
    : [...selectedUids.value, uid]
}

function toggleAllUsers(event) {
  selectedUids.value = event.target.checked ? [...selectableUserUids.value] : []
}

function toggleAllCurrentPage() {
  selectedUids.value = allUsersSelected.value ? [] : [...selectableUserUids.value]
}

async function bulkLevelAction() {
  if (!selectedUids.value.length || !bulkLevel.value) return
  loading.value = true
  error.value = ''
  bulkMessage.value = ''
  try {
    const data = await api('/api/admin/users/levels/bulk', {
      method: 'POST',
      body: JSON.stringify({ uids: selectedUids.value, level: bulkLevel.value })
    })
    bulkMessage.value = `已更新 ${data.updated || selectedUids.value.length} 名用户`
    bulkLevel.value = ''
    await load()
  } catch (e) {
    error.value = e.message || '批量修改用户等级失败'
  } finally {
    loading.value = false
  }
}

function switchAudit(next) {
  auditMode.value = next
  page.value = 1
  q.value = ''
  load()
}

function goPage(next) {
  const target = Math.min(Math.max(1, next), pages.value || 1)
  if (target === page.value || loading.value) return
  page.value = target
  load()
}

function search() {
  clearTimeout(timer)
  timer = setTimeout(() => { page.value = 1; load() }, 250)
}

async function fileAction(action, id) {
  const method = action === 'delete' ? 'DELETE' : 'POST'
  const path = action === 'delete' ? `/api/admin/files/${id}` : `/api/admin/${action}/${id}`
  await api(path, { method })
  await load()
}

async function userStatusAction(item) {
  busyUid.value = item.uid
  error.value = ''
  try {
    await api(`/api/admin/users/${item.uid}/${item.is_active ? 'ban' : 'unban'}`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = e.message || '用户状态更新失败'
  } finally {
    busyUid.value = ''
  }
}

async function adminRoleAction(item) {
  busyUid.value = item.uid
  error.value = ''
  try {
    await api(`/api/admin/users/${item.uid}/admin?enabled=${!item.is_admin}`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = e.message || '管理员权限更新失败'
  } finally {
    busyUid.value = ''
  }
}

async function levelAction(item, level) {
  busyUid.value = item.uid
  error.value = ''
  try {
    await api(`/api/admin/users/${item.uid}/level?level=${encodeURIComponent(level)}`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = e.message || '用户级别更新失败'
  } finally {
    busyUid.value = ''
  }
}

async function clearIp(ip) {
  if (!ip) return
  if (!window.confirm(`确认清除 IP ${ip} 发表的帖子、评论，并加入网站黑名单？`)) return
  loading.value = true
  error.value = ''
  try {
    await api(`/api/admin/audit/clear-ip?ip=${encodeURIComponent(ip)}`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = e.message || 'IP 清理失败'
  } finally {
    loading.value = false
  }
}

function filterIp(ip) {
  if (!ip) return
  q.value = ip
  page.value = 1
  load()
}

function sourceLabel(value) {
  const labels = { post: '日志/帖子', comment: '评论/留言' }
  return labels[value] || value || '-'
}

function eventLabel(value) {
  const labels = {
    register: '注册',
    login: '登录',
    post: '发表帖子',
    comment: '发表评论',
    auto_lock: '自动锁定',
    clear_ip: 'IP 清理',
    sensitive_post: '帖子敏感词',
    sensitive_comment: '评论敏感词'
  }
  return labels[value] || value || '-'
}

function statusLabel(value) {
  return value === 'pending' ? '待审核' : value === 'approved' ? '已通过' : '已拒绝'
}

function cleanExt(ext) {
  return String(ext || 'file').replace('.', '').toUpperCase()
}

function extClass(ext) {
  const value = cleanExt(ext).toLowerCase()
  if (['doc', 'docx'].includes(value)) return 'doc'
  if (['ppt', 'pptx'].includes(value)) return 'ppt'
  if (['xls', 'xlsx', 'csv'].includes(value)) return 'xls'
  if (['zip', '7z', 'rar', 'tar', 'gz'].includes(value)) return value === 'rar' ? 'rar' : 'zip'
  if (['txt', 'md'].includes(value)) return 'txt'
  return value || 'file'
}

function pick(row, key) {
  return row[key] ?? row[key.toUpperCase()] ?? row[key.toLowerCase()]
}

function boolValue(value, fallback = false) {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  return !['0', 'false', 'no'].includes(String(value).toLowerCase())
}

function normalizeUser(row) {
  const uid = pick(row, 'uid')
  const username = String(pick(row, 'username') || pick(row, 'display_name') || '').trim()
  return {
    ...row,
    uid,
    username,
    is_admin: boolValue(pick(row, 'is_admin')),
    is_active: boolValue(pick(row, 'is_active'), true),
    user_level: pick(row, 'user_level') || 'auto',
    effective_level: pick(row, 'effective_level') || 'gray',
    download_count: pick(row, 'download_count') ?? 0,
    download_size: pick(row, 'download_size') || formatBytes(pick(row, 'download_size_raw')) || '0 B'
  }
}

function emptyGroup() {
  return {
    id: 0,
    group_name: '',
    log_categories: '前沿快讯,资源分享,求助,灌水区',
    album_categories: '*',
    user_groups: 'gray,blue,green,yellow,orange',
    can_modify_user: false,
    can_enter_user_backend: true,
    can_modify_user_group: false,
    can_manage_user_template: false,
    can_publish_site_notice: false,
    can_publish_notification: false
  }
}

function groupMemberCount(groupId) {
  return contentMembers.value.filter(member => Number(member.group_id) === Number(groupId)).length
}

function scopeLabel(value) {
  const text = String(value || '').trim()
  if (!text) return '未配置'
  return text === '*' ? '全部' : text
}

function permissionLabels(group) {
  const labels = [
    ['can_enter_user_backend', '用户后台'],
    ['can_modify_user', '修改用户'],
    ['can_modify_user_group', '用户组'],
    ['can_manage_user_template', '用户模板'],
    ['can_publish_site_notice', '站点公告'],
    ['can_publish_notification', '通知']
  ]
  return labels.filter(([key]) => boolValue(group[key])).map(([, label]) => label)
}

function displayUser(item) {
  return String(item.username || '').trim() || '用户名为空'
}

function formatPoints(value) {
  const points = Number(value || 0)
  return Number.isInteger(points) ? String(points) : points.toFixed(1)
}

function formatUserTime(value) {
  const text = String(value || '').trim()
  return text ? text.replace('T', ' ').slice(0, 19) : '-'
}

function formatBytes(value) {
  const size = Number(value || 0)
  if (!Number.isFinite(size) || size <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let n = size
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i += 1
  }
  return `${n >= 10 || i === 0 ? n.toFixed(0) : n.toFixed(1)} ${units[i]}`
}

function levelLabel(level) {
  const labels = {
    gray: '刚注册',
    blue: '正式用户',
    green: '贡献者',
    yellow: '活跃达人',
    orange: '社区之星',
    admin: '管理员'
  }
  return labels[level] || labels.gray
}
</script>
