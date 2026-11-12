<script setup lang="ts">
/**
 * agent 时间线：把"它调了什么工具、拿到了什么"画出来。
 *
 * ★ 一切都来自后端的 `steps`，前端**不解析模型的散文**去猜它干了什么 ——
 *   猜出来的过程是编故事，而这里的目的是让人能核对。
 * ★ 色调判据在 `agentTimeline.ts::verdictOf`（纯函数、有单测），组件只负责显示。
 */
import type { AgentResult } from '../api'
import { stepTone, verdictOf } from '../agentTimeline'

const props = defineProps<{ result: AgentResult }>()

const tone = (step: Parameters<typeof stepTone>[0]) => stepTone(step)
const verdict = () => verdictOf(props.result.steps, props.result.stopped_reason, props.result.refused)
const argText = (args: Record<string, unknown>) =>
  Object.entries(args).map(([k, v]) => `${k}=${String(v).slice(0, 40)}`).join(' ') || '（无参数）'
</script>

<template>
  <section class="timeline">
    <div class="head">
      <span class="badge" :class="verdict().tone">{{ verdict().label }}</span>
      <span class="muted">
        {{ result.rounds }} 轮 · {{ result.tokens }} token · {{ result.took_ms }} ms
      </span>
    </div>
    <p class="detail" :class="verdict().tone">{{ verdict().detail }}</p>

    <ol v-if="result.steps.length" class="steps">
      <li v-for="s in result.steps" :key="s.i" class="step" :class="tone(s)">
        <span class="dot" />
        <div class="body">
          <div class="line">
            <strong>{{ s.i }}. {{ s.tool }}</strong>
            <span class="muted">{{ argText(s.args) }}</span>
          </div>
          <div class="line muted">
            <span>{{ s.tool_ms }} ms</span>
            <span>命中 {{ s.n_src }}</span>
            <span v-if="s.status === 'no_hit'">库里没有</span>
            <span v-if="s.error" class="err">错误：{{ s.error }}</span>
          </div>
        </div>
      </li>
    </ol>
    <p v-else class="muted">（这次没有调用任何工具）</p>
  </section>
</template>

<style scoped>
.timeline { margin: 10px 0 14px; padding: 10px 12px; border: 1px solid #e2e2e2; border-radius: 6px; }
.head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.badge { font-size: 12px; padding: 2px 8px; border-radius: 10px; font-weight: 600; }
.badge.ok { background: #e8f5e9; color: #2e7d32; }
.badge.warn { background: #fff8e1; color: #8d6e00; }
.badge.error { background: #fdecea; color: #b3261e; }
.detail { margin: 6px 0 8px; font-size: 12.5px; color: #555; }
.detail.error { color: #b3261e; }
.steps { list-style: none; margin: 0; padding: 0; }
.step { display: flex; gap: 8px; padding: 4px 0; }
.dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; flex: 0 0 auto; background: #bbb; }
.step.ok .dot { background: #2e7d32; }
.step.warn .dot { background: #f9a825; }
.step.error .dot { background: #b3261e; }
.body { flex: 1 1 auto; }
.line { display: flex; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.muted { color: #777; }
.err { color: #b3261e; }
</style>
