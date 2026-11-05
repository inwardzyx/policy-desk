import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from './api'
import ChatView from './views/ChatView.vue'
import DocsView from './views/DocsView.vue'
import LoginView from './views/LoginView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/login', name: 'login', component: LoginView },
    // ★ meta.requiresAuth 只写在"需要登录"的路由上：默认拒绝比默认放行安全，
    //   将来加页面时忘了加 meta 会表现为"被踢回登录页"，而不是"匿名也能看"。
    { path: '/chat', name: 'chat', component: ChatView, meta: { requiresAuth: true } },
    { path: '/docs', name: 'docs', component: DocsView, meta: { requiresAuth: true } },
  ],
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !getToken()) {
    return { name: 'login', query: { next: to.fullPath } }
  }
  return true
})
