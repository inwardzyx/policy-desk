<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, ask, askAgent, type AgentResult, type ChatResult } from '../api'
import AgentTimeline from '../components/AgentTimeline.vue'

const question = ref('')
const result = ref<ChatResult | null>(null)
const agentResult = ref<AgentResult | null>(null)
const error = ref('')
const busy = ref(false)
/** 「Agent 模式」开关：默认关 —— 关掉时走的还是原来那条 /chat，行为逐字节不变。 */
const agentMode = ref(false)

async function submit() {
  const q = question.value.trim()
  if (!q || busy.value) return
  error.value = ''
  busy.value = true
  result.value = null
  agentResult.value = null
  try {
    if (agentMode.value) {
      agentResult.value = await askAgent(q)
    } else {
      result.value = await ask(q)
    }
  } catch (e) {
    // ★ 404 = 后端没开 AGENT_ENABLED。要明确说出来，不能让用户以为"回答是空的"。
    if (e instanceof ApiError && e.status === 404) {
      error.value = 'agent 未启用（后端 AGENT_ENABLED=0），已回退到普通问答'
      agentMode.value = false
    } else {
      error.value = e instanceof ApiError ? e.detail : '请求失败，请稍后再试'
    }
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
      <div class="actions">
        <label class="toggle">
          <input v-model="agentMode" type="checkbox" :disabled="busy" />
          Agent 模式（多步检索，能看到每一步）
        </label>
        <button type="submit" :disabled="busy || !question.trim()">
          {{ busy ? (agentMode ? 'Agent 正在多步检索…' : '检索中…') : '提问' }}
        </button>
      </div>
    </form>

    <p v-if="error" class="error">{{ error }}</p>

    <!-- agent：先给"过程"，再给答案。过程不可信时，答案是次要的 -->
    <article v-if="agentResult" class="answer">
      <AgentTimeline :result="agentResult" />
      <p class="text">{{ agentResult.answer }}</p>
      <ol v-if="agentResult.sources.length" class="sources">
        <li v-for="(s, i) in agentResult.sources" :key="i">
          <div class="src-head">
            <strong>{{ s.doc }}</strong>
            <span v-if="s.score != null" class="muted">相似度 {{ s.score.toFixed(3) }}</span>
          </div>
          <p class="src-text">{{ s.text }}</p>
        </li>
      </ol>
    </article>

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

<style scoped>
.actions { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 8px; }
.toggle { font-size: 13px; color: #555; display: flex; align-items: center; gap: 6px; }
</style>
