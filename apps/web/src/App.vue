<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getToken, setToken } from './api'

const route = useRoute()
const router = useRouter()
const loggedIn = ref(!!getToken())

// ★ 用 router 的钩子同步登录态，而不是轮询 localStorage：
//   登录/登出都发生在路由跳转前后，这两个时刻足够覆盖。
router.afterEach(() => {
  loggedIn.value = !!getToken()
})

const username = computed(() => {
  // TODO(M6b): 换成 /api/me 的返回值（现在只用于顶栏显示）
  return loggedIn.value ? '已登录' : ''
})

function logout() {
  setToken(null)
  loggedIn.value = false
  router.push({ name: 'login' })
}

const onLoginPage = computed(() => route.name === 'login')
</script>

<template>
  <header class="topbar">
    <div class="brand">制度问答台</div>
    <nav>
      <template v-if="loggedIn">
        <router-link to="/chat">问答</router-link>
        <router-link to="/docs">文档</router-link>
        <span class="who">{{ username }}</span>
        <button class="link" @click="logout">退出</button>
      </template>
      <router-link v-else-if="!onLoginPage" to="/login">登录</router-link>
    </nav>
  </header>
  <main class="page">
    <router-view />
  </main>
</template>
