import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import './assets/style.css'
import App from './App.vue'
import { startAccessRouteProbe } from './accessRoute'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('./views/Home.vue') },
    { path: '/login', component: () => import('./views/Login.vue') },
    { path: '/register', component: () => import('./views/Register.vue') },
    { path: '/forum', component: () => import('./views/Forum.vue') },
    { path: '/forum/posts/:id', component: () => import('./views/ForumPostDetail.vue') },
    { path: '/profile', component: () => import('./views/Profile.vue') },
    { path: '/users/:uid', component: () => import('./views/UserProfile.vue') },
    { path: '/admin', component: () => import('./views/Upload.vue') },
    { path: '/dashboard', component: () => import('./views/Dashboard.vue') },
    { path: '/content-admin-login', component: () => import('./views/ContentAdminLogin.vue') },
    { path: '/content-admin', component: () => import('./views/ContentAdmin.vue') }
  ]
})

startAccessRouteProbe()

createApp(App).use(router).mount('#app')
