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

// ============================================================
// 旅行偏好测试（TravelMind）
// ============================================================

/**
 * 旅行画像里的单个维度。
 *
 * ⚠️ 注意这里**没有** `level` / `levelLabel` / `description`——和人格的
 * {@link DimensionResult} 不一样。因为旅行每维度只有 1 道题，分数只能落在
 * 0/25/50/75/100 五档，再套"偏低/中等/偏高"三档等于把分数重复说一遍。
 * 后端也是这么定义的：`TravelProfileResponse.TravelDimensionResult`。
 */
export interface TravelDimensionResult {
  key: string
  name: string
  score: number
}

export interface TravelProfileResponse {
  sessionId: number
  status: SessionStatus
  createdAt: string
  submittedAt: string | null
  /** 固定是 'TRAVEL' */
  scale: string
  /**
   * 这份画像是不是从**上一次测试**借来的（当前会话还没答题）。
   *
   * 前端必须把这件事说清楚——否则用户会以为系统把他没做的测试算完了。
   * ⚠️ 只有登录用户才可能为 true：匿名没有稳定的身份，"上次"无从谈起。
   */
  reused: boolean
  dimensions: TravelDimensionResult[]
}

/** 一条「为什么推荐它」的依据 */
export interface MatchReason {
  dimensionKey: string
  dimensionLabel: string
  /** 你在这次画像里给这个维度打的分（0~100） */
  userPreference: number
  /** 这个地点在该属性上的得分（0~100） */
  placeValue: number
}

/** 👍 或 👎 */
export type Reaction = 'LIKE' | 'DISLIKE'

/**
 * 此刻的状态——和长期画像是两回事。
 *
 * 用户说"我累了"不代表他从此不喜欢走路，所以状态**不会被存进画像**，
 * 只作用于这一次推荐。后端的 `TravelState` 枚举定义了一一对应的取值。
 */
export type TravelState = 'TIRED' | 'HUNGRY' | 'WANT_WALK'

/**
 * 反馈提交后服务端返回的"画像变化"。
 *
 * 为什么要返回它：画像是"下次推荐才用到"的东西，用户点完当场看不到任何变化，
 * 很容易以为按钮是坏的。把调整前后的分数给出来，前端就能显示成
 * 「自然风光 50 → 40」，让反馈的影响立刻可见。
 *
 * ⚠️ 这些调整**不写回画像表**——问卷画像永远保持原样，
 * 调整是每次推荐时实时算出来的。
 */
export interface FeedbackResponse {
  recommendationId: number
  reaction: Reaction
  /** 被反馈影响到的维度。没被影响的不出现 */
  adjustments: DimensionAdjustment[]
}

export interface DimensionAdjustment {
  key: string
  name: string
  /** 问卷算出来的原始分 */
  questionnaireScore: number
  /** 叠加反馈修正后、推荐实际使用的分数 */
  effectiveScore: number
}

/**
 * 推荐分数的构成——回答「为什么是它」。
 *
 * ⚠️ 这四个是**相乘**的关系，不是相加。展示时要画出乘号：
 * 乘法意味着任何一项掉到 0，整个结果就是 0——这正是"太远的地方不该被推荐"
 * 的算法依据（加权求和会让"兴趣极高"补偿掉"距离极远"）。
 *
 * 都是 0~1 的比例。
 */
export interface ScoreBreakdown {
  /** 兴趣匹配（主信号） */
  interest: number
  /** 距离衰减。没定位时恒为 1.0 */
  distance: number
  /** 质量修正。0.85~1.0，只往下扣 */
  quality: number
  /** 当前状态的修正。没状态时恒为 1.0 */
  state: number
  /** 四个因子相乘，等于 scorePercent / 100 */
  finalScore: number
}

export interface RecommendedPlace {
  rank: number
  /** 点 👍/👎 时要带上它——反馈挂在"某一次推荐的某一条"上，不是挂在地点上 */
  recommendationId: number
  placeId: number
  name: string
  category: string
  description: string
  /** 综合得分，0~100 的整数 */
  scorePercent: number
  /** 这个分数是怎么来的 */
  scoreBreakdown: ScoreBreakdown
  /** 距离用户多少公里。**全天开放的地点是 null** */
  distanceKm: number | null
  ticketPrice: number
  suggestedMinutes: number
  /** 'HH:mm'。**null 表示全天开放**（公园、街区） */
  openFrom: string | null
  openTo: string | null
  reasons: MatchReason[]
}

/** 一个状态 + 它的中文名（中文名由后端给，前端不用维护翻译表） */
export interface StateLabel {
  key: string
  label: string
}

/**
 * 这次推荐**实际用的处境**。
 *
 * 系统一旦开始替用户猜，就必须说清楚猜了什么——否则用户莫名其妙，
 * 想纠正也不知道从哪下手。`inferredStates` 就是"哪些是我猜的"。
 */
export interface AppliedContext {
  /** 服务端时间（"HH:mm:ss"） */
  now: string
  remainingMinutes: number
  maxDistanceKm: number
  maxTicketPrice: number | null
  states: StateLabel[]
  /** 其中哪些是系统自己推断的。用户没说过，所以最该给一键改 */
  inferredStates: StateLabel[]
}

export interface RecommendationResponse {
  sessionId: number
  /** 这是该会话的第几批推荐。点 👍/👎 时要连它一起带上 */
  batchNo: number
  generatedAt: string
  /** 可能是空数组——附近没有营业中的地点时，这是正常结果不是错误 */
  places: RecommendedPlace[]
  /** 这次按什么处境算的，含"哪些是系统推断的" */
  appliedContext: AppliedContext
}

/**
 * 请求推荐时传的「当前处境」。
 *
 * ⚠️ 经纬度**必填**：旅行助手不知道你在哪，推荐就没有意义。
 * 后端会校验，不传返回 400。
 */
export interface RecommendationRequest {
  latitude: number
  longitude: number
  /** 还剩多少分钟。不传后端默认 240（4 小时） */
  remainingMinutes?: number
  /** 最大半径（公里）。不传后端默认 10 */
  maxDistanceKm?: number
  /** 门票价格上限（元）。不传 = 不限 */
  maxTicketPrice?: number
  /** 此刻的状态（累了 / 饿了 / 想散步），可以传多个 */
  states?: TravelState[]
  /**
   * 让系统自己推断处境，而不是等用户填。
   *
   * 为 true 时服务端会按当前时间推断（比如饭点 → 想吃饭），
   * 并把推断结果回传在 `appliedContext.inferredStates` 里——猜了什么必须看得见。
   */
  autoInfer?: boolean
  /**
   * 是否排除这个会话里已经推荐过、且没被点过 👍 的地点。
   *
   * "换一批"必须传 `true`——引擎没有记忆，同样的输入必然算出同样的输出，
   * 不排除的话按钮就是个摆设。不传时后端按 `false` 处理。
   */
  excludeSeen?: boolean
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
