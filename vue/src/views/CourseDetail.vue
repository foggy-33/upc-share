<template>
  <NavBar />
  <main class="courses-page course-detail-page">
    <div class="container">
      <router-link to="/courses" class="course-back">← 返回公选课</router-link>
      <p v-if="error" class="courses-feedback">{{ error }}</p>
      <p v-else-if="loading" class="courses-feedback">课程加载中...</p>
      <template v-else-if="course">
        <section class="course-detail-hero">
          <div>
            <div class="course-tags">
              <span>{{ course.category || course.type }}</span>
              <span v-if="course.is_art" class="highlight">艺术课程</span>
              <span v-if="course.is_core_2" class="highlight">2.0 核心</span>
            </div>
            <h1>{{ course.name }}</h1>
            <p>{{ course.code }} · {{ course.credits }} 学分 · {{ course.campuses.join('、') }} · {{ course.semester }} 学期</p>
          </div>
          <div class="course-detail-rating">
            <strong>{{ course.review_count ? Number(course.average_score).toFixed(1) : '—' }}</strong>
            <span>满分 10 分 · {{ course.review_count }} 人评分</span>
          </div>
        </section>

        <div class="course-detail-layout">
          <div class="course-detail-main">
            <section v-if="course.description" class="course-detail-panel">
              <h2>课程简介</h2>
              <p class="course-description">{{ course.description }}</p>
            </section>
            <section class="course-detail-panel">
              <h2>开课安排 <small>{{ course.offerings.length }} 个教学班</small></h2>
              <div class="course-offering" v-for="(item, index) in shownOfferings" :key="index">
                <strong>{{ item.teacher || '教师待定' }}</strong>
                <span>{{ item.campus }}</span>
                <span>{{ item.time || '时间待定' }}</span>
                <span v-if="item.location">{{ item.location }}</span>
                <span v-if="item.exam">{{ item.exam }}</span>
              </div>
              <button v-if="course.offerings.length > 6" class="course-more" @click="showAllOfferings = !showAllOfferings">
                {{ showAllOfferings ? '收起' : `查看全部 ${course.offerings.length} 个教学班` }}
              </button>
            </section>
            <section class="course-detail-panel">
              <h2>课程评价 <small>{{ reviewTotal }} 条</small></h2>
              <div v-if="!reviews.length" class="course-empty">还没有评价，来写第一条吧。</div>
              <div v-for="review in reviews" :key="review.user_id" class="course-review">
                <div class="course-review-head">
                  <strong>{{ review.username }} <span v-if="me.uid === review.user_id">· 我的评价</span></strong>
                  <span class="course-review-score">★ {{ review.score }} / 10</span>
                </div>
                <p v-if="review.comment">{{ review.comment }}</p>
                <small>{{ String(review.updated_at).slice(0, 10) }}</small>
              </div>
              <div v-if="reviewPages > 1" class="pagination courses-pagination">
                <button class="page-btn" :disabled="reviewPage === 1" @click="changeReviewPage(reviewPage - 1)">上一页</button>
                <span class="courses-page-number">{{ reviewPage }} / {{ reviewPages }}</span>
                <button class="page-btn" :disabled="reviewPage === reviewPages" @click="changeReviewPage(reviewPage + 1)">下一页</button>
              </div>
            </section>
          </div>

          <aside class="course-detail-side">
            <section class="course-detail-panel course-rate-panel">
              <h2>{{ myReview ? '修改我的评价' : '给课程评分' }}</h2>
              <template v-if="me.logged_in">
                <form @submit.prevent="submitReview">
                  <label for="course-score">评分 <strong>{{ score }} / 10</strong></label>
                  <input id="course-score" v-model.number="score" type="range" min="1" max="10" step="1" />
                  <div class="course-score-scale"><span>1 分</span><span>10 分</span></div>
                  <label for="course-comment">评价 <small>选填，最多 1000 字</small></label>
                  <textarea id="course-comment" v-model.trim="comment" maxlength="1000" rows="6" placeholder="课程内容、上课体验、考核方式……"></textarea>
                  <p v-if="submitError" class="course-form-error">{{ submitError }}</p>
                  <p v-if="saved" class="course-form-success">评价已保存</p>
                  <button class="course-submit" :disabled="submitting">{{ submitting ? '保存中...' : '提交评价' }}</button>
                </form>
                <p class="course-form-hint">每人每门课一条评价，可随时修改。</p>
              </template>
              <p v-else class="course-login-note">登录后可以评分和写评价。</p>
              <router-link v-if="!me.logged_in" :to="loginTarget" class="course-submit course-login-link">去登录</router-link>
            </section>
            <section v-if="course.core_note" class="course-detail-panel course-core-note">
              <h2>课程归属说明</h2>
              <p>{{ course.core_note }}</p>
            </section>
          </aside>
        </div>
      </template>
    </div>
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import NavBar from '../components/NavBar.vue'
import { currentUser as me, loadCurrentUser } from '../authState'
import { api, postJson } from '../api/http'

const route = useRoute()
const course = ref(null)
const reviews = ref([])
const myReview = ref(null)
const reviewTotal = ref(0)
const reviewPage = ref(1)
const score = ref(10)
const comment = ref('')
const loading = ref(true)
const submitting = ref(false)
const showAllOfferings = ref(false)
const error = ref('')
const submitError = ref('')
const saved = ref(false)
const shownOfferings = computed(() => showAllOfferings.value ? course.value.offerings : course.value.offerings.slice(0, 6))
const reviewPages = computed(() => Math.ceil(reviewTotal.value / 20))
const loginTarget = computed(() => `/login?next=${encodeURIComponent(route.fullPath)}`)

async function loadReviews() {
  const data = await api(`/api/courses/${encodeURIComponent(route.params.id)}/reviews?page=${reviewPage.value}`)
  reviews.value = data.reviews
  reviewTotal.value = data.total
  myReview.value = data.my_review || null
  if (myReview.value) {
    score.value = Number(myReview.value.score)
    comment.value = myReview.value.comment
  }
}

async function changeReviewPage(next) {
  reviewPage.value = next
  try { await loadReviews() } catch (e) { error.value = e.message }
}

async function submitReview() {
  submitting.value = true
  submitError.value = ''
  saved.value = false
  try {
    await postJson(`/api/courses/${encodeURIComponent(route.params.id)}/reviews`, {
      score: score.value, comment: comment.value
    })
    course.value = await api(`/api/courses/${encodeURIComponent(route.params.id)}`)
    reviewPage.value = 1
    await loadReviews()
    saved.value = true
  } catch (e) {
    submitError.value = e.message || '保存失败'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    await loadCurrentUser()
    course.value = await api(`/api/courses/${encodeURIComponent(route.params.id)}`)
    await loadReviews()
  } catch (e) {
    error.value = e.message || '课程加载失败'
  } finally {
    loading.value = false
  }
})
</script>
