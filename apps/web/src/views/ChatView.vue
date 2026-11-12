<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError, ask, askAgent, askAgentStream, type AgentResult, type ChatResult } from '../api'
import { applyEvent, emptyState, type AgentEvent, type AgentStreamState } from '../agentTimeline'
import AgentTimeline from '../components/AgentTimeline.vue'

const question = ref('')
const result = ref<ChatResult | null>(null)
const error = ref('')
const busy = ref(false)
/** 「Agent 模式」开关：默认关 —— 关掉时走的还是原来那条 /chat，行为逐字节不变。 */
const agentMode = ref(false)
/** 「看过程」开关：开 = 走 SSE（边跑边看）；关 = 一次性 JSON。默认开。 */
const streamMode = ref(true)
const live = ref<AgentStreamState | null>(null)
const agentSources = ref<AgentResult['sources']>([])

/** 把流式状态包成 AgentTimeline 认的形状（判据仍在 agentTimeline.ts）。 */
const liveResult = computed<AgentResult | null>(() => {
  const s = live.value
  if (!s) return null
  return {
    answer: s.answer,
    sources: agentSources.value,
    steps: s.steps,
    stopped_reason: s.stopped || 'streaming',
    rounds: s.rounds,
    tokens: s.tokens,
    refused: s.refused,
    took_ms: 0,
  }
})

async function submit() {
  const q = question.value.trim()
  if (!q || busy.value) return
  error.value = ''
  busy.value = true
  result.value = null
  live.value = null
  agentSources.value = []
  try {
    if (agentMode.value) {
      if (streamMode.value) {
        let state = emptyState()
        live.value = state
        await askAgentStream(q, (ev) => {
          state = applyEvent(state, ev as AgentEvent)
          live.value = state
          if (ev.type === 'result' && Array.isArray(ev.sources)) {
            agentSources.value = ev.sources as AgentResult['sources']
          }
        })
        // ★ 流断了但没收到 result ⇒ 状态留空，verdict 会落到"状态未知"（不许当成功）
      } else {
        const r = await askAgent(q)
        live.value = { steps: r.steps, answer: r.answer, rounds: r.rounds,
                       tokens: r.tokens, stopped: r.stopped_reason, refused: r.refused }
        agentSources.value = r.sources
      }
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
        <div class="toggles">
          <label class="toggle">
            <input v-model="agentMode" type="checkbox" :disabled="busy" />
            Agent 模式（多步检索，能看到每一步）
          </label>
          <label v-if="agentMode" class="toggle">
            <input v-model="streamMode" type="checkbox" :disabled="busy" />
            边跑边看（SSE）
          </label>
        </div>
        <button type="submit" :disabled="busy || !question.trim()">
          {{ busy ? (agentMode ? 'Agent 正在多步检索…' : '检索中…') : '提问' }}
        </button>
      </div>
    </form>

    <p v-if="error" class="error">{{ error }}</p>

    <!-- agent：先给"过程"，再给答案。过程不可信时，答案是次要的 -->
    <article v-if="liveResult" class="answer">
      <AgentTimeline :result="liveResult" />
      <p class="text">{{ liveResult.answer || '（还在跑…）' }}</p>
      <ol v-if="agentSources.length" class="sources">
        <li v-for="(s, i) in agentSources" :key="i">
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
.toggles { display: flex; flex-direction: column; gap: 4px; }
.toggle { font-size: 13px; color: #555; display: flex; align-items: center; gap: 6px; }
</style>
