import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, ask, getToken, login, setToken } from './api'

/**
 * 判据都对着"最容易出错的三个地方"：
 * ① 令牌有没有真的带上；② 401 有没有清令牌；③ 后端的 detail 有没有传出来。
 *
 * ★ 不测"页面长什么样" —— 那属于手工验收；这里测的是**协议层**，它可以被确定性验证。
 */
describe('api', () => {
  const store = new Map<string, string>()

  beforeEach(() => {
    store.clear()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
    vi.unstubAllGlobals()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
  })

  it('登录成功后把令牌存下来，并在后续请求里带上 Authorization', async () => {
    const fetchMock = vi
      .fn()
      // 第一次：登录
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 't-123', expiresAt: '2026-11-05T10:00:00Z', username: 'hr_demo', roles: ['hr'] }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      // 第二次：提问
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ answer: '800 元', sources: [], took_ms: 12, knowledge_hit: true }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    await login('hr_demo', 'policy123')
    expect(getToken()).toBe('t-123')

    await ask('加班费怎么算')
    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    const headers = init.headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer t-123')
  })

  it('401 ⇒ 清掉令牌并抛 ApiError(401)（否则用户会一直看到"未认证"却不知道要重新登录）', async () => {
    setToken('expired-token')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } })),
    )

    await expect(ask('q')).rejects.toBeInstanceOf(ApiError)
    expect(getToken()).toBeNull()
  })

  it('后端 ProblemDetail 的 detail 要透出来（不是只给一个状态码）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ title: '今日配额已用尽', status: 429, detail: '今日配额已用尽（13/12）' }), {
          status: 429,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )

    await expect(ask('q')).rejects.toThrowError('今日配额已用尽（13/12）')
  })
})
