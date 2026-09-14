/**
 * 后端 API 的类型定义。
 *
 * <p>这些接口和后端 `dto` 包里的 record **一一对应**——字段名必须完全一致，
 * 因为 JSON 反序列化是靠字段名匹配的，拼错一个字母不会报错，
 * 只会在运行时拿到 `undefined`。
 *
 * <p>为什么不自动生成类型（比如 OpenAPI Generator）？
 * 对 6 个接口、6 个类型来说，手写的成本远低于引入代码生成工具链的成本。
 * 等接口涨到几十个再考虑自动化——那时候手写才真的会出错。
 *
 * <p><b>⚠️ 修改后端 DTO 字段名时，这个文件必须同步改。</b>
 * TypeScript 只能保证前端内部的一致性，管不到跨语言的契约。
 */

/** 李克特量表的一个选项，例如 `{ value: 1, label: '非常不同意' }` */
export interface ScaleOption {
  value: number
  label: string
}

/**
 * 一道题目。
 *
 * 注意：**没有 `reverseScored` 字段**——后端刻意不返回它，
 * 否则用户打开 F12 就能看到哪些题是反向计分的，据此操纵结果。
 */
export interface Question {
  id: number
  content: string
  dimension: string
  dimensionLabel: string
  sortOrder: number
}

export interface QuestionsResponse {
  options: ScaleOption[]
  questions: Question[]
}

export type SessionStatus = 'IN_PROGRESS' | 'SUBMITTED'

export interface SessionResponse {
  sessionId: number
  /**
   * 会话访问令牌，**只在创建会话时返回这一次**。
   *
   * 后续对这个会话的所有操作（答题、提交、查结果、生成 AI 解读）
   * 都要在 `X-Session-Token` 请求头里带上它。
   *
   * 为什么要它：`sessionId` 是自增的连续整数，猜得到；令牌是随机 UUID，猜不到。
   * 没有令牌的话，任何人遍历 id 就能读到所有人的测试结果（IDOR）。
   *
   * 前端存内存里就行，不需要持久化——测试做完就不用了。
   */
  accessToken: string
  status: SessionStatus
  createdAt: string
}

export interface AnswersSavedResponse {
  sessionId: number
  savedCount: number
}

export type Level = 'LOW' | 'MEDIUM' | 'HIGH'

/**
 * 结果页上单个维度的完整信息。
 *
 * ⚠️ `rawScore` 和 `itemCount` 目前后端固定返回 `-1`（占位）。
 * 原因：数据库只存了归一化分数，没存原始分。
 * 用之前先判断是否大于 0，别直接展示。
 */
export interface DimensionResult {
  /** 英文标识，如 'OPENNESS'。用来做 Map 的 key，**不要拿去展示** */
  key: string
  /** 中文名，如 '开放性'。给用户看的 */
  name: string
  /** 归一化分数，0.00 ~ 100.00 */
  score: number
  /** ⚠️ 占位值 -1，见上方说明 */
  rawScore: number
  /** ⚠️ 占位值 -1，见上方说明 */
  itemCount: number
  level: Level
  /** 档位中文：偏低 / 中等 / 偏高 */
  levelLabel: string
  description: string
}

export interface SessionResultResponse {
  sessionId: number
  status: SessionStatus
  createdAt: string
  /** 未提交时为 null */
  submittedAt: string | null
  dimensions: DimensionResult[]
  /** 免责声明。**务必在结果页展示** */
  disclaimer: string
}

export interface AiReportResponse {
  sessionId: number
  content: string
  /** 生成方，如 'deepseek:deepseek-chat'；未启用 AI 时是 'stub' */
  provider: string
  /** 报告的**首次**生成时间；缓存命中时是过去的时间 */
  generatedAt: string
  /** true 表示这次直接复用了旧结果、没有调用大模型 */
  cached: boolean
}

// ============================================================
// 认证与历史
// ============================================================

/**
 * 当前登录用户。
 *
 * 注意**没有 passwordHash**——后端返回的是 `UserResponse` DTO，
 * 不是 `User` 实体。这条边界在后端就守住了，前端拿不到敏感字段，
 * 不是靠前端"记得别显示"。
 */
export interface UserResponse {
  id: number
  username: string
  createdAt: string
}

/** 历史列表里一条维度的简要分数（没有解读文案，列表页用不上） */
export interface DimensionBrief {
  key: string
  name: string
  score: number
  levelLabel: string
}

/**
 * 历史列表里的一次测试记录。
 *
 * `status` 可能是 `IN_PROGRESS`——用户答到一半就关了页面。
 * 这时 `dimensions` 是**空数组**（还没计分），前端要处理好这种情况。
 */
export interface SessionSummary {
  sessionId: number
  createdAt: string
  submittedAt: string | null
  status: SessionStatus
  dimensions: DimensionBrief[]
}

/** 后端统一的错误响应结构，见 ApiErrorResponse.java */
export interface ApiErrorBody {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
  fieldErrors: FieldError[]
}

export interface FieldError {
  field: string
  message: string
}
