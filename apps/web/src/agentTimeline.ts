/**
 * agent 时间线的**判定逻辑**（纯函数，不碰 DOM）。
 *
 * ★ 为什么把它从组件里拆出来：组件测不了"判据会不会响"（没有 vue-test-utils，
 *   加一个依赖只为测渲染不值）。而这里要做的事**恰恰是判据**：
 *   什么情况下必须显示"没跑完/没取证/不可信"，而不是画成一片绿。
 *
 * ★★ 这条判据来自一次真实事故（2026-11-12）：`/agent` 的工具连错端口、两次调用全失败，
 *   响应却是 `200 + stopped_reason=done`，答案是一段"后台服务未启动"的优雅话术。
 *   如果前端只按 HTTP 200 画成功，用户看到的就是一条绿色的、什么都没查到的路径。
 */

export interface AgentStep {
  i: number
  tool: string
  args: Record<string, unknown>
  tool_ms: number
  /** hit / no_hit / null（解析不了不猜） */
  status: string | null
  n_src: number
  error: string | null
}

export type Tone = 'ok' | 'warn' | 'error'

export interface Verdict {
  tone: Tone
  label: string
  detail: string
}

/**
 * 整次运行怎么算"成功"。
 *
 * 只有 `stopped_reason === 'done'` 且**没拒答**且**至少一步工具**才算 ok；
 * 其余一律 warn/error —— 未知的 stopped_reason 也归 warn（**不许当成功**）。
 */
export function verdictOf(steps: AgentStep[], stoppedReason: string, refused: boolean): Verdict {
  if (steps.length === 0) {
    return { tone: 'error', label: '没有工具证据', detail: '模型直接作答，本次回答未取证；按拒答处理' }
  }
  switch (stoppedReason) {
    case 'done':
      return refused
        ? { tone: 'warn', label: '库外问题 · 已拒答', detail: `工具都没命中，按拒答处理（${steps.length} 步）` }
        : { tone: 'ok', label: '已完成', detail: `${steps.length} 步工具调用，全部有结果` }
    case 'no_tool':
      return { tone: 'warn', label: '未取证', detail: '模型没有调用任何工具就作答了' }
    case 'tool_error':
      return { tone: 'error', label: '工具失败 · 不可信', detail: '所有工具调用都失败了，这段回答没有证据' }
    case 'max_rounds':
      return { tone: 'warn', label: '撞轮数上限', detail: '循环被上限截断，答案可能不完整' }
    case 'budget':
      return { tone: 'warn', label: '超 token 预算', detail: '循环被预算截断，答案可能不完整' }
    default:
      // ★ 未知状态**不许当成功**：宁可显示"状态未知"，也不要把没跑完的当跑完了。
      return { tone: 'warn', label: '状态未知', detail: `stopped_reason=${stoppedReason || '（空）'}` }
  }
}

/** 单个步骤的色调：失败红、未命中黄、命中绿、解析不了灰（用 warn 表示"不确定"）。 */
export function stepTone(step: AgentStep): Tone {
  if (step.error) return 'error'
  if (step.status === 'hit') return 'ok'
  if (step.status === 'no_hit') return 'warn'
  return 'warn'
}
