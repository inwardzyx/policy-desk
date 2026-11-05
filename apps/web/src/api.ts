/**
 * 后端接口的**唯一**出口。
 *
 * ★ 为什么把 fetch 收在一个文件里：令牌怎么带、401 怎么处理、错误信息从哪取，
 * 这三件事散在各个组件里就一定会不一致（比如某个页面忘了带 Authorization，
 * 表现是"这个页面总是 401"，而其它页面正常 —— 最难查的那种）。
 */

export interface Source {
  doc: string
  text: string
  score?: number
  rerank_score?: number | null
  reason?: string | null
}

export interface ChatResult {
  answer: string
  sources: Source[]
  took_ms: number
  knowledge_hit: boolean
  context?: { trace_id?: string } | null
}

export interface LoginResult {
  token: string
  expiresAt: string
  username: string
  roles: string[]
}

/** 后端返回 ProblemDetail 时，把 status 与 detail 带出来；组件据此决定提示什么。 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly detail: string,
  ) {
    super(detail)
    this.name = 'ApiError'
  }
}

const TOKEN_KEY = 'policy-desk.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null): void {
  if (token === null) {
    localStorage.removeItem(TOKEN_KEY)
  } else {
    localStorage.setItem(TOKEN_KEY, token)
  }
}

/**
 * 统一的请求入口。
 *
 * ★ 401 的处理是这里最值得看的一行：清掉令牌并抛出，让上层跳到登录页。
 *   如果只在某个页面处理 401，用户会在别的页面上反复看到"未认证"而不知道要重新登录。
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  // ★ 只有 JSON 才设 Content-Type。multipart 必须让浏览器自己设 ——
  //   它要在里面写 boundary，手写一个 Content-Type 会把 boundary 丢掉，后端解不出来。
  if (!(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const resp = await fetch(path, { ...init, headers })

  if (resp.status === 401) {
    setToken(null)
    throw new ApiError(401, '登录已过期，请重新登录')
  }

  if (!resp.ok) {
    // 后端用 ProblemDetail：优先取 detail，取不到就退回状态码
    let detail = `请求失败（${resp.status}）`
    try {
      const body = (await resp.json()) as { detail?: string; title?: string }
      detail = body.detail ?? body.title ?? detail
    } catch {
      // 非 JSON 响应（比如网关的 HTML 错误页）：保持状态码提示
    }
    throw new ApiError(resp.status, detail)
  }

  return (await resp.json()) as T
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const r = await request<LoginResult>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  setToken(r.token)
  return r
}

export function me(): Promise<{ userId: number; username: string; roles: string[] }> {
  return request('/api/me')
}

export function ask(question: string, namespace?: string): Promise<ChatResult> {
  return request<ChatResult>('/api/chat', {
    method: 'POST',
    body: JSON.stringify({ question, namespace: namespace || null }),
  })
}

// ---------------------------------------------------------------- 文档

export interface Doc {
  doc_id: string
  title?: string | null
  version?: string | null
  acl?: string[]
  updated_at?: string | null
  status?: string | null
  tombstone?: boolean
}

export interface DocList {
  namespace: string
  documents: Doc[]
}

export function listDocs(namespace: string): Promise<DocList> {
  return request<DocList>(`/api/docs?namespace=${encodeURIComponent(namespace)}`)
}

export function uploadDoc(
  file: File,
  namespace: string,
  acl: string,
): Promise<{ document: Doc; namespace_chunks: number }> {
  const form = new FormData()
  form.append('file', file)
  form.append('namespace', namespace)
  form.append('acl', acl)
  return request('/api/docs', { method: 'POST', body: form })
}

export function deleteDoc(namespace: string, docId: string): Promise<{ deleted: boolean }> {
  return request(`/api/docs/${encodeURIComponent(namespace)}/${encodeURIComponent(docId)}`, {
    method: 'DELETE',
  })
}
