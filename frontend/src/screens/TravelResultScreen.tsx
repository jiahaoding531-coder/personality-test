import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { ApiError, api } from '../api'
import { AiKeyPanel } from '../components/AiKeyPanel'
import { BarChart } from '../components/BarChart'
import type {
  AppliedContext,
  DimensionAdjustment,
  InterpretResponse,
  Reaction,
  RecommendationRequest,
  RecommendationResponse,
  RecommendedPlace,
  TravelProfileResponse,
  TravelState,
} from '../types'

/**
 * 旅行测试的结果页。
 *
 * <h2>默认是「自动模式」，不是表单</h2>
 *
 * <p>进页面就自动拿定位、按当前时间推断处境、直接给推荐——用户一个字段都不用填。
 * 手动表单折叠在「改一下」后面，只在两种情况下才需要展开：
 * <b>定位被拒绝</b>，或者<b>用户想纠正系统猜的东西</b>。
 *
 * <p>这个取舍来自一个很直接的判断：让用户为了拿一个推荐先填五个输入框，
 * 那是表单，不是助手。
 *
 * <h2>⚠️ 推断必须可见、可撤销</h2>
 *
 * <p>系统一旦开始替用户猜，就得说清楚猜了什么。结果上方那行
 * 「我按 12:30、饭点 推的」就是干这个的——没有它，用户被推了一堆馆子
 * 只会觉得系统坏了，而不是意识到"它在按饭点推"。
 *
 * <p>而猜错了要能改：那行提示旁边就是「改一下」。
 */

/** 演示数据的中心点，与后端 V7 种子里西湖的坐标一致。 */
const DEMO_LAT = '30.2420'
const DEMO_LNG = '120.1400'

// ---------------------------------------------------------------
// AI 理由的生成方式
// ---------------------------------------------------------------

/**
 * `auto` = 列表出来就自动请求；`manual` = 用户点了才请求。
 *
 * 两种模式打的是**同一个接口、同一份缓存**，所以来回切换不会重复花钱。
 * 区别只是"什么时候花"——自动省一次点击，手动省 token。
 */
type ReasonMode = 'auto' | 'manual'

const REASON_MODE_KEY = 'travelmind.reasonMode'

function loadReasonMode(): ReasonMode {
  try {
    // 只有明确存过 'manual' 才算手动。其它任何情况（没存过、存了脏值、
    // localStorage 不可用）都退回自动——它是更省心的那个默认值。
    return localStorage.getItem(REASON_MODE_KEY) === 'manual' ? 'manual' : 'auto'
  } catch {
    // ⚠️ 隐私模式 / 禁用 Cookie 时 localStorage 会直接抛异常。
    // 一个偏好设置读不到，不该让整个结果页崩掉。
    return 'auto'
  }
}

function saveReasonMode(mode: ReasonMode) {
  try {
    localStorage.setItem(REASON_MODE_KEY, mode)
  } catch {
    // 存不下就算了，只影响"下次进来还记不记得"，不影响这次使用
  }
}

/** 交给 `RecommendationList` 的 AI 理由状态。打包传免得加七个 props。 */
interface AiReasonState {
  /** recommendationId → 那句话 */
  texts: Record<number, string>
  mode: ReasonMode
  loading: boolean
  error: string | null
  /**
   * 这台服务器没配 AI（后端 501）。
   *
   * <p>⚠️ 这个状态的前端行为**和以前正好相反**：
   * 以前 501 = "这站没有 AI" → 把入口整个藏起来；
   * 现在 501 = "这站没替你配 AI，但**你可以填自己的**" → 引导用户去填。
   *
   * <p>别人打开你的站点，不该看到"AI 不可用"而走掉，
   * 该看到"填个 key 就能用"。这两句话的差别就是这个功能的全部意义。
   */
  unavailable: boolean
  /** 填的 key 被上游拒了（后端 400）。该让用户重填，而不是重试 */
  keyRejected: boolean
  onModeChange: (mode: ReasonMode) => void
  onRequest: () => void
  /** 用户填完 key 之后重试（内容其实和 onRequest 一样，但语义不同） */
  onKeySaved: () => void
}

/**
 * 系统"没把握"的判据（前端算）。
 *
 * <p>这就是文档里说的"必要时追问"——**一上来就问等于又变成表单**，
 * 所以只在系统自己都不确定的时候才开口。
 */
const LOW_CONFIDENCE_SCORE = 35

interface ContextForm {
  latitude: string
  longitude: string
  remainingMinutes: string
  maxDistanceKm: string
  maxTicketPrice: string
  states: TravelState[]
  /**
   * 词表覆盖不了时，AI 从自然语言里解析出来的**原始维度倾向**。
   *
   * 键是维度名（`CROWD_TOLERANCE`），不是中文——因为要原样回传给后端。
   * 展示时现查 `DIMENSION_LABELS`。
   */
  biases: Record<string, number>
}

const EMPTY_FORM: ContextForm = {
  latitude: '',
  longitude: '',
  remainingMinutes: '240',
  maxDistanceKm: '',
  maxTicketPrice: '',
  states: [],
  biases: {},
}

interface Scenario {
  label: string
  patch: Partial<ContextForm>
}

/**
 * 快捷场景按钮。
 *
 * <p>它们是"一句话输入"的快捷方式——点了立刻重新推荐，不用等 AI。
 * 两套并存：按钮快、零成本；输入框能表达按钮覆盖不了的东西。
 *
 * <p>⚠️ 每个 patch 都带 `biases: {}` ——点快捷按钮意味着**换一种处境**，
 * 上一次用自然语言说出来的倾向必须清掉。不清的话，
 * 用户先说了"想找带猫的咖啡馆"、再点"我有点累了"，
 * 那个"小众↑"还在偷偷生效，而他完全不知道。
 */
const SCENARIOS: Scenario[] = [
  { label: '我想散步一下', patch: { states: ['WANT_WALK'], maxDistanceKm: '5', biases: {} } },
  { label: '我有点累了', patch: { states: ['TIRED'], biases: {} } },
  { label: '我想吃饭', patch: { states: ['HUNGRY'], biases: {} } },
  { label: '想安静点', patch: { states: ['QUIET'], biases: {} } },
  { label: '只剩 1 小时', patch: { remainingMinutes: '60', biases: {} } },
  { label: '不想走远', patch: { maxDistanceKm: '2', biases: {} } },
  { label: '预算不多', patch: { maxTicketPrice: '50', biases: {} } },
]

/**
 * 自然语言输入的状态。
 *
 * <p>用可辨识联合而不是几个独立 boolean——理由见 `AiPanel` 的注释：
 * 几个 boolean 能组合出"正在加载而且同时出错了"这种不可能的状态。
 *
 * <p>⚠️ `noServerAi` 和 `keyRejected` 单独成状态，而不是并进 `error`：
 * 它们的处理方式完全不同——不是"出错了重试"，而是"**填个 key 就能用**"。
 * 混进 error 显示一句"理解失败"，就把一条可走的路说成了死路。
 */
type NlState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'done'; result: InterpretResponse }
  /** 解析出来是空的——"这句我没听懂"。是正常结果，不是故障 */
  | { kind: 'nothing'; summary: string }
  | { kind: 'noServerAi' }
  | { kind: 'keyRejected' }
  | { kind: 'error'; message: string }

type RecState =
  | { kind: 'idle' }
  | { kind: 'done'; data: RecommendationResponse }
  | { kind: 'error'; message: string }

export function TravelResultScreen({
  profile,
  sessionToken,
  onRestart,
}: {
  profile: TravelProfileResponse
  sessionToken?: string
  onRestart: () => void
}) {
  const [form, setForm] = useState<ContextForm>(EMPTY_FORM)
  const [rec, setRec] = useState<RecState>({ kind: 'idle' })
  const [busy, setBusy] = useState(false)
  const [showManual, setShowManual] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const [myFeedback, setMyFeedback] = useState<Record<number, Reaction>>({})
  const [lastAdjustments, setLastAdjustments] = useState<DimensionAdjustment[] | null>(null)

  // ---- 自然语言输入 ----
  const [nlText, setNlText] = useState('')
  const [nlState, setNlState] = useState<NlState>({ kind: 'idle' })

  /**
   * 请求序号，用来**丢弃过期响应**。
   *
   * ⚠️ 和「换一批」是同一类问题：连着提交两次，先发的可能后回来，
   * 把第一次的理解结果盖在第二次上面。界面上看不出错——
   * 两段话都是通顺的，只是对不上用户最后一次说的话。
   */
  const nlSeq = useRef(0)

  // ---- AI 理由 ----
  const [reasonTexts, setReasonTexts] = useState<Record<number, string>>({})
  const [reasonMode, setReasonMode] = useState<ReasonMode>(loadReasonMode)
  const [reasonLoading, setReasonLoading] = useState(false)
  const [reasonError, setReasonError] = useState<string | null>(null)
  /** 后端说"这台服务器没配 AI"（501）。这时引导访客填自己的 key */
  const [aiUnavailable, setAiUnavailable] = useState(false)
  /** 访客填的 key 被厂商拒了（400）。该让他重填，不是重试 */
  const [aiKeyRejected, setAiKeyRejected] = useState(false)

  /**
   * 当前展示的是哪一批推荐。
   *
   * ⚠️ 它是**丢弃过期响应用的凭据**，不是给渲染用的——所以放 ref 不放 state。
   * 连点「换一批」会并发好几个理由请求，先发的完全可能后回来；
   * 不看一眼"现在已经是第几批了"就把结果 setState 进去，
   * 旧批次的理由会贴到新列表上，而且看不出是错的（都是通顺的句子）。
   */
  const currentBatch = useRef<number | null>(null)
  /** 已经为哪一批发起过自动请求，避免 effect 重复触发 */
  const reasonRequestedFor = useRef<number | null>(null)

  const sessionId = profile.sessionId

  /**
   * 维度名 → 中文。
   *
   * <p>从后端给的画像里现取，**前端不另维护一份翻译表**——
   * 文字全由后端定，加一个维度或改一个中文名，前端不用跟着改。
   * （自然语言解析出来的原始倾向要用它才能显示成"人群耐受 ↓"。）
   */
  const dimensionLabels = useMemo(() => {
    const labels: Record<string, string> = {}
    for (const d of profile.dimensions) {
      labels[d.key] = d.name
    }
    return labels
  }, [profile.dimensions])

  /**
   * 进页面时自动跑一次的守卫。
   *
   * ⚠️ 用 ref 而不是 state：StrictMode 下 effect 会跑两次，
   * 用 state 做守卫时第二次执行读到的还是旧值，会重复发请求。
   */
  const autoStarted = useRef(false)

  const patchForm = useCallback((patch: Partial<ContextForm>) => {
    setForm((f) => ({ ...f, ...patch }))
  }, [])

  /**
   * 发一次推荐请求。
   *
   * @param override 本次覆盖的表单值。场景按钮用它——`setState` 是异步的，
   *                 点完按钮立刻发请求会拿到旧值，所以把要用的值直接传进来。
   * @param auto     自动模式：带 autoInfer，让服务端按时间推断处境
   */
  const run = useCallback(
    async (
      override: Partial<ContextForm> = {},
      options: { excludeSeen?: boolean; auto?: boolean } = {},
    ) => {
      const values = { ...form, ...override }
      const latitude = Number(values.latitude)
      const longitude = Number(values.longitude)

      if (
        !values.latitude ||
        !values.longitude ||
        !Number.isFinite(latitude) ||
        !Number.isFinite(longitude)
      ) {
        setNotice('没拿到定位。手动填一下经纬度，或者用杭州西湖的坐标。')
        setShowManual(true)
        setRec({ kind: 'error', message: '缺少定位，无法推荐。' })
        return
      }

      setBusy(true)
      setNotice(null)
      try {
        const body: RecommendationRequest = { latitude, longitude }
        if (options.auto) {
          body.autoInfer = true
        }
        const minutes = Number(values.remainingMinutes)
        if (Number.isFinite(minutes) && minutes > 0) {
          body.remainingMinutes = minutes
        }
        const distance = Number(values.maxDistanceKm)
        if (values.maxDistanceKm && Number.isFinite(distance) && distance > 0) {
          body.maxDistanceKm = distance
        }
        const budget = Number(values.maxTicketPrice)
        if (values.maxTicketPrice && Number.isFinite(budget) && budget >= 0) {
          body.maxTicketPrice = budget
        }
        if (values.states.length > 0) {
          body.states = values.states
        }
        // 自然语言解析出来的原始倾向。没说过就没有，不必传空对象。
        if (Object.keys(values.biases).length > 0) {
          body.biases = values.biases
        }
        if (options.excludeSeen) {
          body.excludeSeen = true
        }

        const data = await api.getRecommendations(sessionId, body, sessionToken)
        setRec({ kind: 'done', data })
        setMyFeedback({})
        setLastAdjustments(null)

        // 新的一批：旧批次的理由必须清掉，否则会挂在新卡片上。
        // ⚠️ 先更新 currentBatch —— 在途的理由请求回来时会拿它做校验。
        currentBatch.current = data.batchNo
        reasonRequestedFor.current = null
        setReasonTexts({})
        setReasonError(null)
      } catch (e: unknown) {
        setRec({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
      } finally {
        setBusy(false)
      }
    },
    [form, sessionId, sessionToken],
  )

  /**
   * 拉这一批的 AI 理由。
   *
   * @param regenerate 为 true 时强制重新生成（会真的再花一次 token）
   */
  const loadReasons = useCallback(
    async (batchNo: number, regenerate = false) => {
      setReasonLoading(true)
      setReasonError(null)
      try {
        const res = await api.getTravelReasons(sessionId, regenerate, sessionToken)

        // ⚠️ 两道校验，缺一不可。见 currentBatch 的注释。
        //
        // ① 后端给的是不是我们要的那批？接口返回的永远是"最新一批"，
        //    如果在途期间又生成了新批次，回来的就不是当初问的那一批。
        // ② 我们要的那批还是当前展示的吗？用户可能已经点了「换一批」，
        //    旧批次的理由贴到新列表上完全看不出错——句子都是通顺的。
        if (res.batchNo !== batchNo || batchNo !== currentBatch.current) {
          return
        }

        const texts: Record<number, string> = {}
        for (const item of res.reasons) {
          texts[item.recommendationId] = item.reason
        }
        setReasonTexts(texts)
      } catch (e: unknown) {
        if (e instanceof ApiError && e.status === 501) {
          // 这台服务器没替访客配 AI。以前这里是把入口藏起来，
          // 现在改成引导他填自己的 key——见 AiReasonState.unavailable 的注释。
          setAiUnavailable(true)
          return
        }
        if (e instanceof ApiError && e.status === 400) {
          // 400 = 调用方给的东西有问题（厂商名认不出 / key 被上游拒了）。
          // 后端刻意把它和 502（上游故障）分开，就是为了让这里能弹"重新填写"
          // 而不是"重试"——重试一万次还是 401。
          setAiKeyRejected(true)
          return
        }
        setReasonError(e instanceof Error ? e.message : String(e))
      } finally {
        setReasonLoading(false)
      }
    },
    [sessionId, sessionToken],
  )

  /**
   * 自动模式：列表一出来就去要理由。
   *
   * ⚠️ 用 `reasonRequestedFor` 做守卫，而不是把 state 放进依赖里。
   * 后者会形成"请求 → setState → effect 再跑 → 再请求"的循环，
   * 而这种 bug 在开发时表现为"怎么一直在请求"，上线后表现为账单。
   */
  useEffect(() => {
    if (rec.kind !== 'done') return
    if (reasonMode !== 'auto') return
    if (aiUnavailable) return

    const batchNo = rec.data.batchNo
    if (reasonRequestedFor.current === batchNo) return

    reasonRequestedFor.current = batchNo
    void loadReasons(batchNo)
  }, [rec, reasonMode, aiUnavailable, loadReasons])

  const changeReasonMode = useCallback((mode: ReasonMode) => {
    setReasonMode(mode)
    saveReasonMode(mode)
  }, [])

  /**
   * 把用户打的那句话发给后端解析，然后把解析结果**摊开**，再按它重新推荐。
   *
   * <p>顺序很重要：先 setNlState（把理解结果亮出来），再 run（重新推荐）。
   * 反过来也能跑，但用户会先看到推荐变了、过了一会儿才看到"我理解成什么"——
   * 那一刻他已经开始怀疑推荐了。
   */
  const submitNaturalLanguage = useCallback(async () => {
    const text = nlText.trim()
    if (!text) {
      return
    }

    const seq = ++nlSeq.current
    setNlState({ kind: 'loading' })

    try {
      const result = await api.interpret(sessionId, text, sessionToken)

      // ⚠️ 回来的时候用户可能又提交了一句。见 nlSeq 的注释。
      if (seq !== nlSeq.current) {
        return
      }

      if (!result.usable) {
        // 解析出来是空的。**不重新推荐**——拿一个空条件去跑，
        // 用户会以为"说了等于没说"，而其实是我们没听懂。
        // 如实说，让他换个说法。
        setNlState({ kind: 'nothing', summary: result.summary })
        return
      }

      setNlState({ kind: 'done', result })

      // 把解析结果填进表单，再按它重新推荐。
      // ⚠️ biases 每次都要一起给：这次说了什么就是什么，不能留着上一次的。
      const patch: Partial<ContextForm> = {
        states: result.states.map((s) => s.key),
        biases: result.biases,
      }
      if (result.remainingMinutes !== null) {
        patch.remainingMinutes = String(result.remainingMinutes)
      }
      if (result.maxDistanceKm !== null) {
        patch.maxDistanceKm = String(result.maxDistanceKm)
      }
      if (result.maxTicketPrice !== null) {
        patch.maxTicketPrice = String(result.maxTicketPrice)
      }

      patchForm(patch)
      void run(patch)
    } catch (e: unknown) {
      if (seq !== nlSeq.current) {
        return
      }
      if (e instanceof ApiError && e.status === 501) {
        setNlState({ kind: 'noServerAi' })
        return
      }
      if (e instanceof ApiError && e.status === 400) {
        setNlState({ kind: 'keyRejected' })
        return
      }
      setNlState({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
    }
  }, [nlText, sessionId, sessionToken, patchForm, run])

  /**
   * 访客刚填完自己的 key —— 立刻替他重试一次。
   *
   * <p>让他填完还要再点一次「生成」，是很没耐心的设计：他填 key 的动机
   * 就是"想让它现在能跑"，而这个动机在该动作完成的瞬间最强。
   *
   * <p>⚠️ 三件事都要复位，少一件就会静默失效：
   * <ul>
   *   <li>两个失败标记，否则界面还停在"没配 AI / key 被拒"那一屏</li>
   *   <li>{@code reasonRequestedFor}，它是自动模式防重复请求的守卫——
   *       不复位的话，即使自动模式想重试也会被自己挡住</li>
   * </ul>
   */
  const handleKeySaved = useCallback(() => {
    setAiUnavailable(false)
    setAiKeyRejected(false)
    setReasonError(null)

    if (rec.kind !== 'done') {
      return
    }
    // 先占住这个批次号，免得自动模式的 effect 紧接着又发一次
    reasonRequestedFor.current = rec.data.batchNo
    void loadReasons(rec.data.batchNo)
  }, [rec, loadReasons])

  /**
   * 自动模式：拿定位 → 直接推荐。
   *
   * <p>定位失败不是错误路径，而是"退回手动"的信号——浏览器权限、
   * 用户拒接、超时都会走到这里，三种情况都该让人能手动填。
   */
  const startAuto = useCallback(async () => {
    if (!('geolocation' in navigator)) {
      setNotice('这个浏览器不支持定位。可以手动填坐标，或直接用杭州西湖的坐标。')
      setShowManual(true)
      return
    }

    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const patch = {
          latitude: pos.coords.latitude.toFixed(4),
          longitude: pos.coords.longitude.toFixed(4),
        }
        patchForm(patch)
        void run(patch, { auto: true })
      },
      (err) => {
        setShowManual(true)
        setNotice(
          err.code === err.PERMISSION_DENIED
            ? '你拒绝了定位。手动填一下，或者直接用杭州西湖的坐标——演示景点都在杭州。'
            : `没拿到定位（${err.message}）。手动填一下，或者用杭州西湖的坐标。`,
        )
      },
      { timeout: 8000 },
    )
  }, [patchForm, run])

  // 进页面就跑一次自动模式
  useEffect(() => {
    if (autoStarted.current) return
    autoStarted.current = true
    void startAuto()
  }, [startAuto])

  const applyScenario = useCallback(
    (scenario: Scenario) => {
      patchForm(scenario.patch)
      // patch 直接传给 run，不依赖 setForm 是否已经生效
      void run(scenario.patch)
    },
    [patchForm, run],
  )

  const useDemoLocation = useCallback(() => {
    const patch = { latitude: DEMO_LAT, longitude: DEMO_LNG }
    patchForm(patch)
    setNotice(null)
    void run(patch)
  }, [patchForm, run])

  const giveFeedback = useCallback(
    async (recommendationId: number, reaction: Reaction) => {
      setMyFeedback((f) => ({ ...f, [recommendationId]: reaction }))
      try {
        const res = await api.giveFeedback(sessionId, recommendationId, reaction, sessionToken)
        setLastAdjustments(res.adjustments)
      } catch {
        setMyFeedback((f) => {
          const next = { ...f }
          delete next[recommendationId]
          return next
        })
      }
    },
    [sessionId, sessionToken],
  )

  const manualMode = showManual || Boolean(form.maxDistanceKm || form.maxTicketPrice)

  return (
    <>
      <div className="card">
        <h1>你的旅行偏好</h1>

        {/*
          复用上次画像时必须说清楚。系统直接跳到结果页而用户没答题，
          不说的话他会以为"我还没测怎么就出结果了"。
        */}
        {profile.reused && (
          <p className="hint">
            这是你<b>上次</b>测出来的画像——不用再答一遍了。{' '}
            <button className="link-btn" type="button" onClick={onRestart}>
              重新测一次
            </button>
          </p>
        )}

        <p className="lede">
          8 个维度各 1 道题，所以分数只有 0 / 25 / 50 / 75 / 100 五档。
          这份画像只是一开始的猜测——真正让它变准的，是你对推荐的反馈。
        </p>

        <BarChart dimensions={profile.dimensions} />

        <p className="note">
          这是初始画像，不是固定结论。你的 👍/👎 不会改掉这份问卷结果，
          而是作为一份独立的修正叠加在上面——所以这份结果永远可追溯。
        </p>
      </div>

      <div className="card">
        <h2 className="section-title">现在，你附近最值得去哪</h2>

        {busy && rec.kind === 'idle' && <p className="lede">正在定位、判断你现在的情况…</p>}

        {notice && <p className="hint hint-error">{notice}</p>}

        {rec.kind === 'idle' && !busy && (
          <div className="locate-actions">
            <button className="btn btn-primary" type="button" onClick={() => void startAuto()}>
              开始智能推荐
            </button>
            <button className="theme-btn" type="button" onClick={useDemoLocation}>
              用杭州西湖的坐标
            </button>
          </div>
        )}

        {rec.kind === 'error' && (
          <div className="locate-actions">
            <button className="theme-btn" type="button" onClick={useDemoLocation}>
              用杭州西湖的坐标
            </button>
          </div>
        )}

        {/* 「改一下」：手动兜底。默认折叠着，因为绝大多数情况用不上 */}
        <button
          className="link-btn manual-toggle"
          type="button"
          onClick={() => setShowManual((v) => !v)}
        >
          {showManual ? '收起手动设置' : '改一下（手动设置处境）'}
        </button>

        {showManual && (
          <>
            <div className="field-row">
              {numberField('travel-lat', '纬度', 'latitude', form, patchForm, '30.2420', 'decimal')}
              {numberField('travel-lng', '经度', 'longitude', form, patchForm, '120.1400', 'decimal')}
            </div>

            {/*
              自然语言输入。放在快捷按钮**上面**——它是更一般的表达方式，
              按钮是它的快捷方式，而不是反过来。
            */}
            <p className="field-label">或者，直接用一句话说</p>
            <div className="nl-row">
              <textarea
                className="nl-input"
                rows={2}
                maxLength={200}
                placeholder="比如：我有点累了，想找个安静的地方坐坐，还有一个小时"
                value={nlText}
                onChange={(e) => setNlText(e.target.value)}
                disabled={nlState.kind === 'loading'}
              />
              <button
                className="btn btn-primary"
                type="button"
                disabled={nlState.kind === 'loading' || !nlText.trim()}
                onClick={() => void submitNaturalLanguage()}
              >
                {nlState.kind === 'loading' ? '理解中…' : '按这句重新推荐'}
              </button>
            </div>

            <NlFeedback
              state={nlState}
              dimensionLabels={dimensionLabels}
              onRetry={() => void submitNaturalLanguage()}
              onKeySaved={() => void submitNaturalLanguage()}
              onDismiss={() => {
                setNlState({ kind: 'idle' })
                setNlText('')
              }}
            />

            <p className="field-label">现在是什么情况？（点一下立刻重新推荐）</p>
            <div className="scenario-row">
              {SCENARIOS.map((s) => (
                <button
                  key={s.label}
                  className={isScenarioActive(s, form) ? 'scenario-btn chosen' : 'scenario-btn'}
                  type="button"
                  disabled={busy}
                  onClick={() => applyScenario(s)}
                >
                  {s.label}
                </button>
              ))}
            </div>

            <div className="field-row">
              {numberField('travel-minutes', '还剩多少分钟', 'remainingMinutes', form, patchForm, '240', 'numeric')}
              {numberField('travel-distance', '最远走多少公里', 'maxDistanceKm', form, patchForm, '不限', 'decimal')}
              {numberField('travel-budget', '门票最多多少钱', 'maxTicketPrice', form, patchForm, '不限', 'numeric')}
            </div>

            <button className="btn btn-primary" type="button" onClick={() => void run()} disabled={busy}>
              {busy ? '正在计算…' : '按这个重新推荐'}
            </button>
          </>
        )}

        {rec.kind === 'error' && <p className="hint hint-error">{rec.message}</p>}
      </div>

      {rec.kind === 'done' && (
        <RecommendationList
          data={rec.data}
          myFeedback={myFeedback}
          lastAdjustments={lastAdjustments}
          loading={busy}
          manualMode={manualMode}
          onFeedback={giveFeedback}
          onRefresh={() => void run({}, { excludeSeen: true })}
          onCorrect={(patch) => {
            patchForm(patch)
            void run(patch)
          }}
          onPickScenario={applyScenario}
          aiReason={{
            texts: reasonTexts,
            mode: reasonMode,
            loading: reasonLoading,
            error: reasonError,
            unavailable: aiUnavailable,
            keyRejected: aiKeyRejected,
            onModeChange: changeReasonMode,
            // 「重新生成」：regenerate=true 会真的再花一次 token，
            // 所以这个按钮只在手动模式下出现（见 AiReasonBar）
            onRequest: () => void loadReasons(rec.data.batchNo, true),
            onKeySaved: handleKeySaved,
          }}
        />
      )}

      <div className="nav-row">
        <button className="theme-btn" type="button" onClick={onRestart}>
          重新测一次
        </button>
      </div>
    </>
  )
}

/** 数字/文本输入框。抽出来是因为这里要渲染六个长得一样的字段。 */
function numberField(
  id: string,
  label: string,
  key: keyof ContextForm,
  form: ContextForm,
  patchForm: (patch: Partial<ContextForm>) => void,
  placeholder: string,
  mode: 'decimal' | 'numeric',
) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        value={form[key] as string}
        onChange={(e) => patchForm({ [key]: e.target.value } as Partial<ContextForm>)}
        placeholder={placeholder}
        inputMode={mode}
      />
    </div>
  )
}

/** 这个场景当前是不是"生效中"——用来把按钮标成选中态。 */
function isScenarioActive(scenario: Scenario, form: ContextForm): boolean {
  const { patch } = scenario
  if (patch.states) {
    return (
      patch.states.length === form.states.length &&
      patch.states.every((s) => form.states.includes(s))
    )
  }
  if (patch.remainingMinutes) return form.remainingMinutes === patch.remainingMinutes
  if (patch.maxDistanceKm) return form.maxDistanceKm === patch.maxDistanceKm
  if (patch.maxTicketPrice) return form.maxTicketPrice === patch.maxTicketPrice
  return false
}

function RecommendationList({
  data,
  myFeedback,
  lastAdjustments,
  loading,
  manualMode,
  onFeedback,
  onRefresh,
  onCorrect,
  onPickScenario,
  aiReason,
}: {
  data: RecommendationResponse
  myFeedback: Record<number, Reaction>
  lastAdjustments: DimensionAdjustment[] | null
  loading: boolean
  manualMode: boolean
  onFeedback: (recommendationId: number, reaction: Reaction) => void
  onRefresh: () => void
  onCorrect: (patch: Partial<ContextForm>) => void
  onPickScenario: (scenario: Scenario) => void
  aiReason: AiReasonState
}) {
  const { appliedContext } = data
  const topScore = data.places[0]?.scorePercent ?? 0

  // 系统"没把握"的判据：要么最高分太低，要么候选太少。
  // 这时才问一句——有信心的时候不该打扰用户。
  const unsure = data.places.length > 0 && (topScore < LOW_CONFIDENCE_SCORE || data.places.length < 3)

  return (
    <div className="card">
      {/*
        地名让定位**可被验证**：浏览器给的是「30.2420, 120.1400」这样一串数字，
        用户没法看着它判断准不准。换成「杭州市西湖区北山街附近」，偏了一眼就能看出来。
        ⚠️ null 是正常情况（没配高德 / 用户拒绝定位 / 高德挂了），不是错误。
      */}
      {data.locationLabel && (
        <p className="hint">
          你在<b>{data.locationLabel}</b>。定位不准的话，展开下面「改一下」手动填经纬度。
        </p>
      )}

      <ContextBanner context={appliedContext} onCorrect={onCorrect} />

      {unsure && (
        <div className="hint">
          这几个地方我把握不大。告诉我一句，我重新算：
          <div className="scenario-row">
            {SCENARIOS.slice(0, 3).map((s) => (
              <button
                key={s.label}
                className="scenario-btn"
                type="button"
                onClick={() => onPickScenario(s)}
              >
                {s.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {data.places.length === 0 ? (
        <>
          <h2 className="section-title">附近没有合适的推荐</h2>
          <p className="lede">
            这不是出错了，而是几个条件同时没满足：没有<b>正在营业</b>、
            且停留时长装得进你还剩下的时间、门票也在预算内的地点。
          </p>
          <p className="note">
            {manualMode ? (
              <>把"还剩多少分钟"调大、或者放宽距离和预算再试。</>
            ) : (
              <>展开上面的「改一下」把条件放宽些，或者用"杭州西湖的坐标"再试。</>
            )}
            演示数据只有杭州的 59 个景点。
          </p>
        </>
      ) : (
        <>
          <h2 className="section-title">
            Top {data.places.length}
            <span className="batch-tag">第 {data.batchNo} 批</span>
          </h2>

          <AiReasonBar state={aiReason} />

          {data.places.map((place) => (
            <PlaceCard
              key={place.recommendationId}
              place={place}
              reaction={myFeedback[place.recommendationId]}
              onFeedback={onFeedback}
              // 「为什么是它」里该列出哪几个因子，取决于**这次有哪些输入**——
              // 不是看因子等不等于 1.0（那两种 1.0 含义相反，见 WhyPanel 注释）
              showDistance={place.distanceKm !== null}
              showState={appliedContext.states.length > 0}
              showWeather={appliedContext.weather !== null}
              reason={aiReason.texts[place.recommendationId]}
            />
          ))}
        </>
      )}

      {lastAdjustments && lastAdjustments.length > 0 && (
        <p className="hint">
          已记下你的反馈。下次推荐会使用调整后的画像：
          {lastAdjustments.map((a) => (
            <span key={a.key} className="adjust-chip">
              {a.name} {a.questionnaireScore} → {a.effectiveScore}
            </span>
          ))}
          <br />
          问卷结果本身没有变——修正只作用于推荐，而且下次再测也记得。
        </p>
      )}

      <div className="nav-row">
        <button className="theme-btn" type="button" onClick={onRefresh} disabled={loading}>
          换一批
        </button>
      </div>
    </div>
  )
}

/**
 * 「这次是按什么推的」。
 *
 * <p>这一行是自动模式能成立的前提：系统替用户猜了，就得摊开说清楚。
 * 推断出来的状态单独标注 + 一键否定，用户不用去翻「改一下」也能纠正。
 *
 * <p>天气也在这里，但<b>没有"不算"按钮</b>——这是个有意的区别：
 * 状态是系统猜的，猜错了可以一键否定；天气是查来的事实，
 * 用户能做的只是"知道它影响了排序"，而不是"告诉系统今天没下雨"。
 */
function ContextBanner({
  context,
  onCorrect,
}: {
  context: AppliedContext
  onCorrect: (patch: Partial<ContextForm>) => void
}) {
  const inferredKeys = new Set(context.inferredStates.map((s) => s.key))
  const hhmm = context.now.slice(0, 5)

  return (
    <p className="context-banner">
      我按 <b>{hhmm}</b>
      {context.remainingMinutes ? <>、还剩 <b>{context.remainingMinutes}</b> 分钟</> : null}
      {context.maxTicketPrice !== null ? <>、门票 <b>{context.maxTicketPrice}</b> 元以内</> : null}
      {context.states.length > 0 ? (
        <>
          、以及
          {context.states.map((s, i) => (
            <span key={s.key}>
              {i > 0 && ' + '}
              <b>{s.label}</b>
              {inferredKeys.has(s.key) && <span className="inferred-tag">我猜的</span>}
            </span>
          ))}
        </>
      ) : null}
      推的。
      {/*
        天气：只在真的影响排序时才多说半句。
        晴天也弹一句"已考虑天气"的话，这个提示很快就变成噪音，
        用户会连真正重要的提示一起忽略。
      */}
      {context.weather && (
        <>
          {' '}
          <span className="weather-note">
            {context.weather.label}
            {context.weather.affectsRecommendation && '，户外的地方已往后排'}
          </span>
        </>
      )}
      {context.inferredStates.length > 0 && (
        <>
          {' '}
          <button
            className="link-btn"
            type="button"
            onClick={() => onCorrect({ states: [] })}
          >
            猜错了，不算
          </button>
        </>
      )}
    </p>
  )
}

/**
 * 「我把你这句话理解成什么了」。
 *
 * <h2>⚠️ 这个组件是这个功能的信誉所在</h2>
 *
 * <p>理解完就直接拿去重新推荐了。如果用户看不到系统理解成了什么，
 * 一次误解会表现成"这推荐怎么莫名其妙的"——他只会觉得这东西乱来，
 * 而完全想不到是它把"想安静"听成了别的。所以这里必须**摊开**：
 * 复述一句、列出解析出的每个条件、把用不上的如实说出来。
 *
 * <p>和项目里一直贯彻的那条原则是同一条：
 * <b>系统替用户做的判断，都要摊开给他看。</b>
 */
function NlFeedback({
  state,
  dimensionLabels,
  onRetry,
  onKeySaved,
  onDismiss,
}: {
  state: NlState
  /** 维度名 → 中文。从画像里现取，前端不另维护一份翻译表 */
  dimensionLabels: Record<string, string>
  onRetry: () => void
  onKeySaved: () => void
  onDismiss: () => void
}) {
  if (state.kind === 'idle' || state.kind === 'loading') {
    return null
  }

  if (state.kind === 'noServerAi') {
    return (
      <div className="nl-feedback">
        <p>这台服务器没有配 AI，没法理解自然语言。</p>
        <AiKeyPanel defaultOpen onSaved={onKeySaved} hint="填上你自己的 API Key 就能用了。" />
      </div>
    )
  }

  if (state.kind === 'keyRejected') {
    return (
      <div className="nl-feedback error">
        <p>你填的 AI Key 被厂商拒绝了（可能填错、过期或额度用尽）。</p>
        <AiKeyPanel defaultOpen onSaved={onKeySaved} hint="换一个 key 再试试。" />
      </div>
    )
  }

  if (state.kind === 'error') {
    return (
      <div className="nl-feedback error">
        <p>没能理解：{state.message}</p>
        <button className="link-btn" type="button" onClick={onRetry}>
          重试
        </button>
      </div>
    )
  }

  if (state.kind === 'nothing') {
    // "这句我没听懂"是一个正常结果，不是故障。
    // ⚠️ 这时**不要重新推荐**——拿一个空条件去跑，用户会以为"说了等于没说"，
    //    而其实是我们没理解。如实说，让他换个说法。
    return (
      <div className="nl-feedback">
        <p>{state.summary || '这句话里我没提取出可用的条件。'}</p>
        <p className="nl-hint">换个说法试试，比如「我有点累了，不想走太远」。</p>
        <button className="link-btn" type="button" onClick={onDismiss}>
          好，我重说
        </button>
      </div>
    )
  }

  const { result } = state
  const biasEntries = Object.entries(result.biases)

  return (
    <div className="nl-feedback">
      <p className="nl-summary">
        <span className="nl-tag">我理解成</span>
        {result.summary}
      </p>

      <div className="nl-chips">
        {result.states.map((s) => (
          <span className="nl-chip" key={s.key}>
            {s.label}
          </span>
        ))}
        {result.remainingMinutes !== null && (
          <span className="nl-chip">还剩 {result.remainingMinutes} 分钟</span>
        )}
        {result.maxDistanceKm !== null && (
          <span className="nl-chip">最远 {result.maxDistanceKm} 公里</span>
        )}
        {result.maxTicketPrice !== null && (
          <span className="nl-chip">门票 {result.maxTicketPrice} 元以内</span>
        )}
        {biasEntries.map(([key, value]) => (
          <span className="nl-chip" key={key}>
            {dimensionLabels[key] ?? key}
            {value >= 0 ? '↑' : '↓'}
          </span>
        ))}
      </div>

      {/*
        ⚠️ 用不上的部分要如实说出来。这一条是诚实的落点：
        说不出来就说"这句我没用上"，比假装听懂强——用户据此才知道系统的边界在哪。

        （这里只列出来，不做成可点的东西：那些条件我们确实没有对应维度，
         能做的只有诚实地告诉他。）
      */}
      {result.unrecognized.length > 0 && (
        <p className="nl-unrecognized">
          这句我没能用上：
          {result.unrecognized.map((item, i) => (
            <span className="nl-chip muted" key={i}>
              {item}
            </span>
          ))}
        </p>
      )}

      <button className="link-btn" type="button" onClick={onDismiss}>
        重来
      </button>
    </div>
  )
}

/**
 * 「AI 解读」的模式开关 + 状态提示。
 *
 * <p>自动 / 手动两种模式打的是**同一个接口、同一份缓存**，所以来回切换
 * 不会重复花钱，区别只是"什么时候花"：自动省一次点击，手动省 token。
 *
 * <p>⚠️ <b>AI 没启用时整条不渲染</b>（后端返回 501）。别人 clone 仓库、
 * 不配 key 直接跑，结果页应该和没有这个功能时一模一样——
 * 而不是出现一个灰掉的按钮或者一行红字，让人以为哪里坏了。
 *
 * <p>（也是出于同样的考虑，这里的模式开关<b>没有沿用虚线标签</b>的样式：
 * 那个样式在这个界面里的含义是"这是系统猜的、可以否定"，
 * 而模式选择是用户自己的设置，不是系统的判断。）
 */
function AiReasonBar({ state }: { state: AiReasonState }) {
  const hasTexts = Object.keys(state.texts).length > 0

  // 服务器没配 AI：不藏起来，改成引导访客填自己的 key。
  if (state.unavailable) {
    return (
      <div className="ai-reason-bar">
        <span className="ai-reason-status">这台服务器没有配 AI。</span>
        <AiKeyPanel
          defaultOpen
          onSaved={state.onKeySaved}
          hint="填上你自己的 API Key 就能用了。"
        />
      </div>
    )
  }

  // key 被上游拒了：让用户改输入，不是让他重试——重试一万次还是 401
  if (state.keyRejected) {
    return (
      <div className="ai-reason-bar">
        <span className="ai-reason-status error">
          你填的 AI Key 被厂商拒绝了（可能填错、过期或额度用尽）。
        </span>
        <AiKeyPanel defaultOpen onSaved={state.onKeySaved} hint="换一个 key 再试试。" />
      </div>
    )
  }

  const showRequest = !state.loading && !state.error && (state.mode === 'manual' || hasTexts)

  return (
    <div className="ai-reason-bar">
      <span className="ai-reason-mode">
        AI 解读
        <button
          className={state.mode === 'auto' ? 'seg chosen' : 'seg'}
          type="button"
          title="列表出来就自动生成，不用你点"
          onClick={() => state.onModeChange('auto')}
        >
          自动
        </button>
        <button
          className={state.mode === 'manual' ? 'seg chosen' : 'seg'}
          type="button"
          title="你想看的时候再生成，省一点调用额度"
          onClick={() => state.onModeChange('manual')}
        >
          手动
        </button>
      </span>

      {state.loading && <span className="ai-reason-status">正在读你的处境…</span>}

      {!state.loading && state.error && (
        <>
          <span className="ai-reason-status error">没能生成：{state.error}</span>
          <button className="link-btn" type="button" onClick={state.onRequest}>
            重试
          </button>
        </>
      )}

      {showRequest && (
        <button className="link-btn" type="button" onClick={state.onRequest}>
          {hasTexts ? '重新生成' : '让 AI 说说为什么'}
        </button>
      )}

      <span className="spacer" />
      {/* 平时也能主动去填自己的 key —— 不是只有出错了才让你知道有这条路 */}
      <AiKeyPanel onSaved={state.onKeySaved} />
    </div>
  )
}

/**
 * 「为什么是它」——把打分因子摊开给用户看。
 *
 * <p>默认折叠着：不想让每张卡片都堆满数字。但它是这个产品敢说
 * "决策助手"而不是"排序器"的关键——用户随时能查账。
 *
 * <p>公式画成 {@code 兴趣 × 距离 × 质量 × 状态 × 天气 = 最终分} 而不是列成几行，
 * 是因为**乘法本身就是信息**：任何一项掉到 0 整个结果就是 0，
 * 所以"再好的地方，太远了也不去"。
 *
 * <h2>⚠️ 判据是「有没有参与计算」，而不是「等不等于 1.0」</h2>
 *
 * <p>没有定位的时候，距离因子恒为 1.0。这时画一个「距离 100%」是**错的**——
 * 它让用户以为"距离被考虑过、而且很合适"，而事实是根本没算距离。
 * 所以没定位就整项去掉。
 *
 * <p>但反过来，<b>参与计算也可能恰好算出 1.0，而那一项是该显示的</b>：
 * 下雨天推一个室内博物馆，天气因子正好是 1.0，含义是
 * 「今天下雨，但这个地方不受影响」——这恰恰是最该说的一句。
 *
 * <p>两种情况数值完全一样，含义却相反。所以这里按<b>输入在不在</b>来判断
 * （有没有定位、有没有状态、有没有天气），而绝不看因子本身的数值。
 */
function WhyPanel({
  place,
  showDistance,
  showState,
  showWeather,
  reason,
}: {
  place: RecommendedPlace
  showDistance: boolean
  showState: boolean
  showWeather: boolean
  /** AI 写的那句人话。没生成、或没启用 AI 时是 undefined */
  reason?: string
}) {
  const [open, setOpen] = useState(false)
  const b = place.scoreBreakdown
  const pct = (v: number) => Math.round(v * 100)

  // 兴趣和质量永远参与（兴趣是主信号，质量是每个地点都有的属性），
  // 另外三个要看这次有没有相应的输入。
  const factors: { label: string; value: number }[] = [
    { label: '兴趣匹配', value: b.interest },
  ]
  if (showDistance) factors.push({ label: '距离', value: b.distance })
  factors.push({ label: '质量', value: b.quality })
  if (showState) factors.push({ label: '此刻状态', value: b.state })
  if (showWeather) factors.push({ label: '天气', value: b.weather })

  return (
    <div className="why">
      <button className="link-btn why-toggle" type="button" onClick={() => setOpen((v) => !v)}>
        {/* 有 AI 解读时在按钮上就点出来——否则用户不知道折叠的面板里
            多了一句话，那段 token 就白花了 */}
        {open ? '收起' : reason ? '为什么是它？· 含 AI 解读' : '为什么是它？'}
      </button>

      {open && (
        <div className="why-body">
          {/*
            ⚠️ AI 这段话放在**乘法式之上**，顺序不能反。

            下面那一串是"账本"——准确、可验证，但读起来是数字。
            这句话是"人话"——回答的是"为什么是它，而不是另外两个"，
            那恰恰是账本答不了的问题（乘法式只能说明"它自己好不好"）。

            先说人话再给账本，用户先拿到结论、再按需查账。
            反过来就成了"先看一堆数字，最后才知道结论"。
          */}
          {reason && (
            <p className="why-ai">
              <span className="why-ai-tag">AI 解读</span>
              {reason}
            </p>
          )}

          <div className="why-formula">
            {factors.map((factor, index) => (
              <Fragment key={factor.label}>
                {index > 0 && <span className="why-op">×</span>}
                <span>
                  {factor.label} <b>{pct(factor.value)}%</b>
                </span>
              </Fragment>
            ))}
            <span className="why-op">=</span>
            <span className="why-final">{pct(b.finalScore)}%</span>
          </div>

          {place.reasons.length > 0 && (
            <ul className="reasons">
              {place.reasons.map((r) => (
                <li key={r.dimensionKey}>
                  你很在乎「{r.dimensionLabel}」<b>{r.userPreference}</b>，这里{' '}
                  <b>{r.placeValue}</b>
                </li>
              ))}
            </ul>
          )}

          <p className="why-note">
            这几项是<b>相乘</b>的：任何一项掉到 0，整体就是 0。
            所以再合口味的地方，太远、太贵或者时间不够，都不会被推荐。
          </p>
        </div>
      )}
    </div>
  )
}

/**
 * 拼一个「在高德地图里打开这个地点」的链接。
 *
 * <p><b>这件事完全不需要后端、不需要 key、不消耗任何配额。</b>
 * 高德的 URI API 就是个普通网址，点开之后：装了高德 App 就直接唤起，
 * 没装就落到网页版，页面上有「到这去」。
 *
 * <h2>⚠️ 坐标顺序是「经度,纬度」</h2>
 *
 * <p>高德的 {@code position} 参数是 <b>先经度后纬度</b>——和绝大多数地图
 * API 以及日常说的"经纬度"相反，也和我们后端请求体里的
 * {@code {latitude, longitude}} 相反。传反了<b>不会报错</b>，
 * 它会把那个纬度当成一个合法经度算下去，于是定位到地球另一边，
 * 或者干脆落进海里什么都不显示。所以这里刻意不写简写。
 *
 * <p>{@code coordinate=gaode} 是告诉高德"这些坐标已经是高德坐标系了，
 * 别再转一次"。我们的 POI 坐标（V7 的种子数据）就是高德坐标系下的，
 * 再转一次会整体偏移几百米。
 *
 * <p>名字要 encode：地点名里有中文和括号（"楼外楼（孤山店）"），
 * 直接拼进 URL 会被截断——"（"在 URL 里有特殊含义。
 */
function amapMarkerUrl(place: RecommendedPlace): string {
  const params = new URLSearchParams({
    position: `${place.longitude},${place.latitude}`,
    name: place.name,
    coordinate: 'gaode',
    callnative: '1',   // 有高德 App 就直接唤起，没有则用网页版
    src: 'travelmind',
  })
  return `https://uri.amap.com/marker?${params.toString()}`
}

function PlaceCard({
  place,
  reaction,
  onFeedback,
  showDistance,
  showState,
  showWeather,
  reason,
}: {
  place: RecommendedPlace
  reaction: Reaction | undefined
  onFeedback: (recommendationId: number, reaction: Reaction) => void
  /** 这几个只影响「为什么是它」里列出哪几项，原样透传给 WhyPanel */
  showDistance: boolean
  showState: boolean
  showWeather: boolean
  /** AI 写的那句人话，可能还没有 */
  reason?: string
}) {
  const openHours =
    place.openFrom && place.openTo ? `${place.openFrom} – ${place.openTo}` : '全天开放'

  return (
    <article className="place-card">
      <header className="place-head">
        <span className="place-rank">#{place.rank}</span>
        <h3 className="place-name">{place.name}</h3>
        <span className="place-score">{place.scorePercent}%</span>
      </header>

      {place.description && <p className="place-desc">{place.description}</p>}

      <div className="place-meta">
        <span>{place.distanceKm === null ? '距离未知' : `${place.distanceKm.toFixed(2)} km`}</span>
        <span>{place.ticketPrice === 0 ? '免费' : `门票 ${place.ticketPrice} 元`}</span>
        <span>建议停留 {place.suggestedMinutes} 分钟</span>
        <span>{openHours}</span>
      </div>

      <WhyPanel
        place={place}
        showDistance={showDistance}
        showState={showState}
        showWeather={showWeather}
        reason={reason}
      />

      <div className="feedback-row">
        {/*
          「导航过去」是个**普通链接**，不是按钮：
          它打开的是站外地址，用 <a> 才对——浏览器会显示真实目标、
          支持中键新标签打开、右键复制链接、也能被读屏软件正确识别。
          用 button + window.open 把这些全丢掉，只为换个样式，不划算。
        */}
        <a
          className="fb-btn nav-btn"
          href={amapMarkerUrl(place)}
          target="_blank"
          rel="noreferrer"
        >
          🧭 导航过去
        </a>
        <button
          className={reaction === 'LIKE' ? 'fb-btn chosen' : 'fb-btn'}
          type="button"
          aria-pressed={reaction === 'LIKE'}
          onClick={() => onFeedback(place.recommendationId, 'LIKE')}
        >
          👍 想去
        </button>
        <button
          className={reaction === 'DISLIKE' ? 'fb-btn chosen' : 'fb-btn'}
          type="button"
          aria-pressed={reaction === 'DISLIKE'}
          onClick={() => onFeedback(place.recommendationId, 'DISLIKE')}
        >
          👎 不感兴趣
        </button>
      </div>
    </article>
  )
}
