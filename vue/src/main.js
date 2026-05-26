import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import './assets/style.css'
import App from './App.vue'
import { startAccessRouteProbe } from './accessRoute'
import Home from './views/Home.vue'
import Login from './views/Login.vue'
import Register from './views/Register.vue'
import Upload from './views/Upload.vue'
import Dashboard from './views/Dashboard.vue'
import ContentAdminLogin from './views/ContentAdminLogin.vue'
import ContentAdmin from './views/ContentAdmin.vue'
import Forum from './views/Forum.vue'
import ForumPostDetail from './views/ForumPostDetail.vue'
import Profile from './views/Profile.vue'
import UserProfile from './views/UserProfile.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: Home },
    { path: '/login', component: Login },
    { path: '/register', component: Register },
    { path: '/forum', component: Forum },
    { path: '/forum/posts/:id', component: ForumPostDetail },
    { path: '/profile', component: Profile },
    { path: '/users/:uid', component: UserProfile },
    { path: '/admin', component: Upload },
    { path: '/dashboard', component: Dashboard },
    { path: '/content-admin-login', component: ContentAdminLogin },
    { path: '/content-admin', component: ContentAdmin }
  ]
})

startAccessRouteProbe()

createApp(App).use(router).mount('#app')
