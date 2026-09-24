<template>
  <NavBar />
  <main class="courses-page">
    <div class="container">
      <div class="courses-heading">
        <div>
          <span class="courses-eyebrow">COURSE GUIDE</span>
          <h1>公选课</h1>
          <p>{{ semester ? `${semester} 学期课程数据` : '课程数据' }} · 看课程安排和同学们的真实评价。</p>
        </div>
        <span class="courses-total">{{ filtered.length }} 门课程</span>
      </div>

      <div class="courses-filters">
        <label class="courses-search">
          <span>搜索</span>
          <input v-model.trim="query" placeholder="课程、课程号或教师" />
        </label>
        <label>
          <span>校区</span>
          <select v-model="campus">
            <option value="">全部校区</option>
            <option v-for="item in campuses" :key="item" :value="item">{{ item }}</option>
          </select>
        </label>
        <label>
          <span>艺术课程</span>
          <select v-model="art">
            <option value="">全部</option><option value="yes">是</option><option value="no">否</option>
          </select>
        </label>
        <label>
          <span>2.0 核心课程</span>
          <select v-model="core">
            <option value="">全部</option><option value="yes">是</option><option value="no">否</option>
          </select>
        </label>
        <label>
          <span>课程类别</span>
          <select v-model="category">
            <option value="">全部类别</option>
            <option v-for="item in categories" :key="item" :value="item">{{ item }}</option>
          </select>
        </label>
      </div>
      <p class="courses-filter-note">2.0 核心标识来自课程原始归属说明，适用年级请以课程详情为准。</p>

      <p v-if="error" class="courses-feedback">{{ error }}</p>
      <p v-else-if="loading" class="courses-feedback">课程加载中...</p>
      <div v-else-if="!filtered.length" class="courses-feedback">没有符合条件的课程，试试调整筛选条件。</div>

      <div v-else class="courses-grid">
        <router-link v-for="course in visible" :key="course.id" :to="`/courses/${course.id}`" class="course-card">
          <div class="course-card-top">
            <span class="course-category">{{ course.category || course.type }}</span>
            <span class="course-score" :class="{ unrated: !course.review_count }">
              <template v-if="course.review_count">★ {{ Number(course.average_score).toFixed(1) }}<small> / 10</small></template>
              <template v-else>暂无评分</template>
            </span>
          </div>
          <h2>{{ course.name }}</h2>
          <p class="course-meta">{{ course.code }} · {{ course.credits }} 学分</p>
          <p class="course-teachers">{{ course.teachers.join('、') || '教师待定' }}</p>
          <div class="course-tags">
            <span v-for="item in course.campuses" :key="item">{{ item }}</span>
            <span v-if="course.is_art" class="highlight">艺术课程</span>
            <span v-if="course.is_core_2" class="highlight">2.0 核心</span>
          </div>
          <div class="course-card-bottom">
            <span>{{ course.review_count }} 条评价</span>
            <strong>查看课程 <span aria-hidden="true">→</span></strong>
          </div>
        </router-link>
      </div>

      <div v-if="pages > 1" class="pagination courses-pagination">
        <button class="page-btn" :disabled="page === 1" @click="page--">上一页</button>
        <span class="courses-page-number">{{ page }} / {{ pages }}</span>
        <button class="page-btn" :disabled="page === pages" @click="page++">下一页</button>
      </div>
    </div>
  </main>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import NavBar from '../components/NavBar.vue'
import { api } from '../api/http'

const route = useRoute()
const courses = ref([])
const loading = ref(true)
const error = ref('')
const query = ref(String(route.query.q || ''))
const campus = ref('')
const art = ref('')
const core = ref('')
const category = ref('')
const page = ref(1)
const pageSize = 24

const campuses = computed(() => [...new Set(courses.value.flatMap(c => c.campuses))].sort())
const semester = computed(() => courses.value[0]?.semester || '')
const categories = computed(() => [...new Set(courses.value.map(c => c.category).filter(Boolean))].sort())
const filtered = computed(() => courses.value.filter(c => {
  const term = query.value.toLocaleLowerCase()
  if (term && !`${c.name} ${c.code} ${c.teachers.join(' ')}`.toLocaleLowerCase().includes(term)) return false
  if (campus.value && !c.campuses.includes(campus.value)) return false
  if (art.value && c.is_art !== (art.value === 'yes')) return false
  if (core.value && c.is_core_2 !== (core.value === 'yes')) return false
  if (category.value && c.category !== category.value) return false
  return true
}))
const pages = computed(() => Math.ceil(filtered.value.length / pageSize))
const visible = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))

watch([query, campus, art, core, category], () => { page.value = 1 })
watch(() => route.query.q, value => { query.value = String(value || '') })
onMounted(async () => {
  try {
    courses.value = await api('/api/courses')
  } catch (e) {
    error.value = e.message || '课程加载失败'
  } finally {
    loading.value = false
  }
})
</script>
