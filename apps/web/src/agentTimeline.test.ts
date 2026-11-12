import { describe, expect, it } from 'vitest'
import { stepTone, verdictOf, type AgentStep } from './agentTimeline'

/**
 * 判据：**"没跑完"必须显示成没跑完**。
 *
 * ★ 反面用例比正面用例重要：这里 6 条里 4 条断言"不许显示成成功"。
 *   原因是一次真实事故 —— 后端返回 200 + stopped_reason=done，但工具全失败，
 *   前端的"看起来成功了"会让用户相信一段什么都没查到的答案。
 */

const hit = (i: number): AgentStep => ({
  i, tool: 'search_policy', args: {}, tool_ms: 3, status: 'hit', n_src: 5, error: null,
})
const failed = (i: number): AgentStep => ({
  i, tool: 'search_policy', args: {}, tool_ms: 0, status: null, n_src: 0, error: 'connection refused',
})

describe('verdictOf', () => {
  it('done + 有工具 + 没拒答 ⇒ ok', () => {
    const v = verdictOf([hit(1), hit(2)], 'done', false)
    expect(v.tone).toBe('ok')
    expect(v.label).toBe('已完成')
  })

  it('done + 拒答 ⇒ warn（不是成功，也不是错误）', () => {
    const v = verdictOf([hit(1)], 'done', true)
    expect(v.tone).toBe('warn')
    expect(v.label).toContain('拒答')
  })

  it('零步骤 ⇒ error：模型没取证就答，按拒答处理', () => {
    const v = verdictOf([], 'done', false)
    expect(v.tone).toBe('error')
    expect(v.label).toBe('没有工具证据')
  })

  it('工具全失败 ⇒ error，不许画成成功', () => {
    const v = verdictOf([failed(1), failed(2)], 'tool_error', false)
    expect(v.tone).toBe('error')
    expect(v.label).toContain('不可信')
  })

  it('撞上限 / 超预算 ⇒ warn：答案可能不完整，必须显式说出来', () => {
    expect(verdictOf([hit(1)], 'max_rounds', false).tone).toBe('warn')
    expect(verdictOf([hit(1)], 'budget', false).detail).toContain('截断')
  })

  it('未知 stopped_reason ⇒ warn（不许当成功）', () => {
    const v = verdictOf([hit(1)], 'something_new', false)
    expect(v.tone).toBe('warn')
    expect(v.label).toBe('状态未知')
  })
})

describe('stepTone', () => {
  it('失败红、命中绿、未命中黄', () => {
    expect(stepTone(failed(1))).toBe('error')
    expect(stepTone(hit(1))).toBe('ok')
    expect(stepTone({ ...hit(1), status: 'no_hit', n_src: 0 })).toBe('warn')
  })
})
