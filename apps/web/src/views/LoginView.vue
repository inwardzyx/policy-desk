<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, login } from '../api'

const username = ref('hr_demo')
const password = ref('policy123')
const error = ref('')
const busy = ref(false)

const route = useRoute()
const router = useRouter()

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await login(username.value.trim(), password.value)
    const next = typeof route.query.next === 'string' ? route.query.next : '/chat'
    await router.push(next)
  } catch (e) {
    // ★ 后端对"用户不存在"与"口令错"返回同一句话，这里原样展示 —— 前端不额外区分，
    //   否则等于把后端刻意抹平的信息又漏回去。
    error.value = e instanceof ApiError ? e.detail : '登录失败，请稍后再试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="card narrow">
    <h1>登录</h1>
    <p class="hint">演示账号：hr_demo / emp_demo，口令 policy123</p>

    <form @submit.prevent="submit">
      <label>
        用户名
        <input v-model="username" autocomplete="username" required />
      </label>
      <label>
        口令
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>

      <button type="submit" :disabled="busy">{{ busy ? '登录中…' : '登录' }}</button>
    </form>

    <p v-if="error" class="error">{{ error }}</p>
  </section>
</template>
