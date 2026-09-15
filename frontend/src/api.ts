import type {
  AiReportResponse,
  AnswersSavedResponse,
  ApiErrorBody,
  FeedbackResponse,
  QuestionsResponse,
  Reaction,
  RecommendationRequest,
  RecommendationResponse,
  SessionResponse,
  SessionResultResponse,
  SessionSummary,
  TravelProfileResponse,
  UserResponse,
} from './types'

/**
 * API 基础地址。
 *
 * 留空 = 同源请求。开发时由 Vite 的 proxy 转发到 `localhost:8080`
 * （见 `vite.config.ts`），生产部署到同域时也是同源。
 * 如果将来前后端分域部署，把这里改成后端域名即可。
 */
const BASE = ''

/**
 * 统一的 API 错误类型。
 *
 * 继承 `Error` 而不是直接抛字符串，有两个实际好处：
 * 1. 能带上下文（HTTP 状态码、字段级错误），调用方可以按状态码分支处理
 * 2. 有堆栈信息，出错时能在浏览器控制台直接看到是哪一行发起的请求
 */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly fieldErrors: ApiErrorBody['fieldErrors'] = [],
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 从 Cookie 里读 CSRF 令牌。
 *
 * 后端用 `CookieCsrfTokenRepository.withHttpOnlyFalse()` 把令牌写进
 * `XSRF-TOKEN` 这个 Cookie。"withHttpOnlyFalse" 是刻意的——
 * HttpOnly 的 Cookie 读不到，前端就没法把它放进请求头，
 * 这个令牌本来就设计成要能被 JS 读到的。
 *
 * 注意它和会话 Cookie（`JSESSIONID`）的区别：
 * - `JSESSIONID` 是 **HttpOnly** 的，JS 读不到 —— 所以 XSS 也偷不走
 * - `XSRF-TOKEN` 是可读的 —— 但它单独拿到没用，得配合会话才有效
 */
function readCsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match?.[1] ? decodeURIComponent(match[1]) : null
}

/** 会改变服务端状态的方法——这些才需要 CSRF 令牌。GET 不需要。 */
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

/**
 * 统一的请求封装。
 *
 * 后端**所有**错误返回的都是同一个 JSON 结构（见 GlobalExceptionHandler），
 * 所以这里只需要写一处解析逻辑，所有接口自动受益。
 * 这就是后端做「统一错误响应」换来的前端收益。
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()

  const headers = new Headers(init?.headers)
  if (MUTATING_METHODS.has(method)) {
    const token = readCsrfToken()
    if (token) {
      headers.set('X-XSRF-TOKEN', token)
    }
  }

  let res: Response
  try {
    res = await fetch(BASE + path, {
      ...init,
      method,
      headers,
      // 带上 Cookie。同源时默认也会带，显式写出来是为了
      // 将来前后端分域部署时不用再想起来改这里。
      credentials: 'include',
    })
  } catch {
    // fetch 只在网络层失败时 reject（断网、服务没起来、被 CORS 拦截）。
    // HTTP 4xx/5xx **不会**走这里，它们在下面的 !res.ok 分支处理。
    throw new ApiError('无法连接服务器，请确认后端已启动（./mvnw spring-boot:run）', 0)
  }

  const text = await res.text()
  let body: unknown = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    // 后端理论上永远返回 JSON，但 502/504 可能来自反向代理，
    // 那些返回的是 HTML 错误页。解析失败时保持 body 为 null 即可。
  }

  if (!res.ok) {
    const err = body as ApiErrorBody | null
    let message = err?.message ?? `请求失败（HTTP ${res.status}）`
    // 字段级校验错误逐条列出来，比只说「参数不合法」有用得多
    if (err?.fieldErrors?.length) {
      message += '\n' + err.fieldErrors.map((f) => `· ${f.field}：${f.message}`).join('\n')
    }
    throw new ApiError(message, res.status, err?.fieldErrors ?? [])
  }

  return body as T
}

/** 一个空的 JSON POST 请求体。后端这几个端点都不需要参数。 */
const emptyJsonPost = (): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: '{}',
})

/**
 * 后端接口。每个方法对应一个 `docs/api.md` 里的端点。
 *
 * 用对象字面量而不是导出一堆独立函数，是为了调用处能读成
 * `api.createSession()` 而不是 `createSession()`——
 * 后者在文件顶部看 import 列表时，根本不知道这个函数属于谁。
 */
export const api = {
  // ---------- 认证 ----------

  /**
   * 当前登录用户。
   *
   * 未登录时后端返回 200 + **空响应体**（不是 401），所以这里可能返回 null。
   * 这样前端启动时判断"我登录了没"只需要一句 `if (user)`，
   * 不用为一个完全正常的情况写 try/catch。
   */
  getCurrentUser: async (): Promise<UserResponse | null> => {
    const body = await request<UserResponse | null>('/api/auth/me')
    return body ?? null
  },

  register: (username: string, password: string) =>
    request<UserResponse>('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }),

  login: (username: string, password: string) =>
    request<UserResponse>('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }),

  /** 注销。后端销毁会话，返回 204（无响应体）。 */
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),

  /** 我的测试历史。未登录会返回 401。 */
  getMyHistory: () => request<SessionSummary[]>('/api/me/test-sessions'),

  // ---------- 测试流程 ----------

  /** 取题目 + 量表选项 */
  getQuestions: () => request<QuestionsResponse>('/api/questions'),

  /** 开一次新测试 */
  createSession: () => request<SessionResponse>('/api/test-sessions', emptyJsonPost()),

  /** 批量保存作答。已答过的题会被更新而不是重复插入，可以放心重复调用 */
  saveAnswers: (sessionId: number, answers: { questionId: number; score: number }[], sessionToken?: string) =>
    request<AnswersSavedResponse>(
      `/api/test-sessions/${sessionId}/answers`,
      withSessionToken(
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ answers }),
        },
        sessionToken,
      ),
    ),

  /** 提交并计分。**闭环收口点**，重复提交会返回 409 */
  submit: (sessionId: number, sessionToken?: string) =>
    request<SessionResultResponse>(
      `/api/test-sessions/${sessionId}/submit`,
      withSessionToken({ method: 'POST' }, sessionToken),
    ),

  /**
   * 查询已生成的画像。
   *
   * `sessionToken` 只在**匿名测试**时传——登录用户访问自己的会话
   * 凭身份即可，不带令牌也能过。
   */
  getResult: (sessionId: number, sessionToken?: string) =>
    request<SessionResultResponse>(
      `/api/test-sessions/${sessionId}/result`,
      withSessionToken({}, sessionToken),
    ),

  /**
   * 生成 AI 解读。
   * @param regenerate false 时已有报告直接返回旧结果，不重复消耗 token
   */
  generateAiReport: (sessionId: number, regenerate = false, sessionToken?: string) =>
    request<AiReportResponse>(
      `/api/test-sessions/${sessionId}/ai-report${regenerate ? '?regenerate=true' : ''}`,
      withSessionToken({ method: 'POST' }, sessionToken),
    ),

  // ---------- 旅行偏好测试（TravelMind） ----------

  /**
   * 取旅行偏好题（8 道）。
   *
   * `?scale=TRAVEL` 不能省——不带参数时后端默认返回人格那 20 道题，
   * 这是为了让老调用方不受影响。
   */
  getTravelQuestions: () => request<QuestionsResponse>('/api/questions?scale=TRAVEL'),

  /** 开一次旅行测试会话。和人格测试共用一张表，只是 scale 不同 */
  createTravelSession: () => request<SessionResponse>('/api/travel/sessions', emptyJsonPost()),

  saveTravelAnswers: (
    sessionId: number,
    answers: { questionId: number; score: number }[],
    sessionToken?: string,
  ) =>
    request<AnswersSavedResponse>(
      `/api/travel/sessions/${sessionId}/answers`,
      withSessionToken(
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ answers }),
        },
        sessionToken,
      ),
    ),

  /** 提交并计分，得到 8 维旅行画像。重复提交返回 409 */
  submitTravel: (sessionId: number, sessionToken?: string) =>
    request<TravelProfileResponse>(
      `/api/travel/sessions/${sessionId}/submit`,
      withSessionToken({ method: 'POST' }, sessionToken),
    ),

  /**
   * 读旅行画像。
   *
   * ⚠️ 这个接口有一个副作用是**好事**：当前会话还没答题时，
   * 它会退回用户**上一次**测出来的画像（`reused: true`）。
   * 所以"用户有没有历史画像"就是靠它 200/404 来判断的——不需要额外的探测接口。
   */
  getTravelProfile: (sessionId: number, sessionToken?: string) =>
    request<TravelProfileResponse>(
      `/api/travel/sessions/${sessionId}/profile`,
      withSessionToken({}, sessionToken),
    ),

  /**
   * 拿 Top 3 推荐。
   *
   * **是 POST 不是 GET**：每次调用都会在服务端新写一批推荐记录
   * （用户反馈要挂上去），有副作用，不符合 GET 的幂等语义。
   *
   * ⚠️ 定位必填。59 个模拟 POI 全在杭州，用真实定位多半会返回空列表——
   * 这不是 bug，是"模拟数据只有一个城市"的必然结果。
   */
  getRecommendations: (
    sessionId: number,
    body: RecommendationRequest,
    sessionToken?: string,
  ) =>
    request<RecommendationResponse>(
      `/api/travel/sessions/${sessionId}/recommendations`,
      withSessionToken(
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        },
        sessionToken,
      ),
    ),

  /**
   * 对某一条推荐点 👍 / 👎。
   *
   * 响应里带回画像被调整的结果，用来告诉用户"这次点击确实有影响"。
   * 重复提交同一条是合法的——改主意走 UPDATE，不会留下两条记录。
   */
  giveFeedback: (
    sessionId: number,
    recommendationId: number,
    reaction: Reaction,
    sessionToken?: string,
  ) =>
    request<FeedbackResponse>(
      `/api/travel/sessions/${sessionId}/recommendations/${recommendationId}/feedback`,
      withSessionToken(
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ reaction }),
        },
        sessionToken,
      ),
    ),
}

/**
 * 需要时给请求加上会话令牌头。
 *
 * 不传就原样返回，所以登录用户走"凭身份放行"那条路时不需要关心它。
 */
function withSessionToken(init: RequestInit, sessionToken?: string): RequestInit {
  if (!sessionToken) return init
  return {
    ...init,
    headers: { ...(init.headers as Record<string, string>), 'X-Session-Token': sessionToken },
  }
}
