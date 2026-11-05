<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, ask, type ChatResult } from '../api'

const question = ref('')
const result = ref<ChatResult | null>(null)
const error = ref('')
const busy = ref(false)

async function submit() {
  const q = question.value.trim()
  if (!q || busy.value) return
  error.value = ''
  busy.value = true
  result.value = null
  try {
    result.value = await ask(q)
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : '请求失败，请稍后再试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="card">
    <h1>问一句</h1>

    <form class="ask" @submit.prevent="submit">
      <textarea v-model="question" rows="2" placeholder="例如：学生违纪处分有哪些等级？" />
      <button type="submit" :disabled="busy || !question.trim()">
        {{ busy ? '检索中…' : '提问' }}
      </button>
    </form>

    <p v-if="error" class="error">{{ error }}</p>

    <article v-if="result" class="answer" :class="{ refusal: !result.knowledge_hit }">
      <!-- ★ 拒答与回答用不同样式：用户要能一眼分清"它不知道"和"它答了" -->
      <div class="meta">
        <span v-if="!result.knowledge_hit" class="tag warn">库外问题 · 已拒答</span>
        <span v-else class="tag ok">命中 {{ result.sources.length }} 条</span>
        <span class="muted">{{ result.took_ms }} ms</span>
        <span v-if="result.context?.trace_id" class="muted mono">
          trace {{ result.context.trace_id.slice(0, 8) }}
        </span>
      </div>

      <p class="text">{{ result.answer }}</p>

      <ol v-if="result.sources.length" class="sources">
        <li v-for="(s, i) in result.sources" :key="i">
          <div class="src-head">
            <strong>{{ s.doc }}</strong>
            <span v-if="s.rerank_score != null" class="muted">精排 {{ s.rerank_score }}</span>
          </div>
          <p class="src-text">{{ s.text }}</p>
        </li>
      </ol>
    </article>
  </section>
</template>
