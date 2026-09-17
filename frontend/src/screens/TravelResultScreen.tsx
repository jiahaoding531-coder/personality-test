import { Fragment, useCallback, useEffect, useRef, useState } from 'react'

import { ApiError, api } from '../api'
import { AiKeyPanel } from '../components/AiKeyPanel'
import { BarChart } from '../components/BarChart'
import type {
  DimensionAdjustment,
  InterpretResponse,
  Reaction,
  RecommendationRequest,
  RecommendationResponse,
  RecommendedPlace,
  TravelProfileResponse,
} from '../types'
import {
  createEmptyTravelQueryState,
  createTravelQueryChips,
  describeTravelQueryOperations,
  reduceTravelQueryOperations,
  toRecommendationFilters,
  type TravelQueryOperation,
  type TravelQueryChip,
  type TravelQueryState,
} from '../travel/travelQueryState'
import {
  chooseAiRetryTarget,
  createLatestRequestGate,
  createSingleFlightGate,
} from '../travel/travelRequestState'

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

interface LocationState {
  latitude: string
  longitude: string
}

interface RecommendationRunOptions {
  excludeSeen?: boolean
  auto?: boolean
  message?: string | null
  operationFeedback?: string
}

interface RecommendationRunTask {
  location: LocationState
  query: TravelQueryState
  options: RecommendationRunOptions
}

const EMPTY_LOCATION: LocationState = {
  latitude: '',
  longitude: '',
}

interface Scenario {
  label: string
  operations: TravelQueryOperation[]
}

/**
 * 快捷场景按钮。
 *
 * <p>它们是"一句话输入"的快捷方式——点了立刻重新推荐，不用等 AI。
 * 两套并存：按钮快、零成本；输入框能表达按钮覆盖不了的东西。
 *
 * <p>快捷标签都是确定性操作：意图 / 偏好只追加，同类约束只替换自己。
 */
const SCENARIOS: Scenario[] = [
  { label: '我想散步一下', operations: [{ op: 'ADD_INTENT', value: 'WANT_WALK' }] },
  { label: '我有点累了', operations: [{ op: 'ADD_PREFERENCE', value: 'TIRED' }] },
  { label: '我想吃饭', operations: [{ op: 'ADD_INTENT', value: 'HUNGRY' }] },
  { label: '想安静点', operations: [{ op: 'ADD_PREFERENCE', value: 'QUIET' }] },
  { label: '只剩 1 小时', operations: [{ op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 60 }] },
  { label: '不想走远', operations: [{ op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 2000 }] },
  { label: '预算不多', operations: [{ op: 'SET_CONSTRAINT', key: 'budgetMax', value: 50 }] },
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
  /** 解析出来是空的——"这句我没听懂"。是正常结果，不是故障 */
  | { kind: 'nothing'; summary: string }
  | { kind: 'noServerAi' }
  | { kind: 'keyRejected' }
  | { kind: 'error'; message: string }

type RecState =
  | { kind: 'idle' }
  | { kind: 'done'; data: RecommendationResponse }
  | { kind: 'error'; message: string }

interface ChatTurn {
  id: string
  userMessage: string | null
  assistantMessage: string
  data: RecommendationResponse
}

export function TravelResultScreen({
  profile,
  sessionToken,
  onRestart,
}: {
  profile: TravelProfileResponse
  sessionToken?: string
  onRestart: () => void
}) {
  const [location, setLocation] = useState<LocationState>(EMPTY_LOCATION)
  const [query, setQuery] = useState<TravelQueryState>(createEmptyTravelQueryState)
  /** 定位回调可能几秒后才执行，届时必须读取用户最新的条件，而不是旧闭包。 */
  const queryRef = useRef(query)
  queryRef.current = query
  const [rec, setRec] = useState<RecState>({ kind: 'idle' })
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [pendingMessage, setPendingMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [showPreferences, setShowPreferences] = useState(false)
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
  /** 推荐请求也只允许最后一次更新界面，避免旧响应覆盖用户刚改的新条件。 */
  const recommendationGate = useRef(createLatestRequestGate())
  /** 请求发出到落库前都不允许第二条推荐进入，保持 UI 当前批次就是数据库最新批次。 */
  const recommendationFlight = useRef(createSingleFlightGate<RecommendationRunTask>())
  /** 用户手动改定位后，让更早发起的浏览器定位回调失效。 */
  const locationSequence = useRef(0)

  // ---- AI 理由 ----
  const [reasonTexts, setReasonTexts] = useState<Record<number, string>>({})
  const [reasonMode, setReasonMode] = useState<ReasonMode>(loadReasonMode)
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
  const streamEndRef = useRef<HTMLDivElement | null>(null)

  const sessionId = profile.sessionId

  /**
   * 进页面时自动跑一次的守卫。
   *
   * ⚠️ 用 ref 而不是 state：StrictMode 下 effect 会跑两次，
   * 用 state 做守卫时第二次执行读到的还是旧值，会重复发请求。
   */
  const autoStarted = useRef(false)

  const patchLocation = useCallback((patch: Partial<LocationState>) => {
    locationSequence.current++
    setLocation((current) => ({ ...current, ...patch }))
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
      nextLocation: LocationState = location,
      nextQuery: TravelQueryState = query,
      options: RecommendationRunOptions = {},
    ) => {
      const task = { location: nextLocation, query: nextQuery, options }
      if (!recommendationFlight.current.tryStart(task)) {
        setPendingMessage(options.message ?? null)
        return
      }
      const requestSequence = recommendationGate.current.next()
      const latitude = Number(nextLocation.latitude)
      const longitude = Number(nextLocation.longitude)

      if (
        !nextLocation.latitude ||
        !nextLocation.longitude ||
        !Number.isFinite(latitude) ||
        !Number.isFinite(longitude)
      ) {
        setNotice('没拿到定位。手动填一下经纬度，或者用杭州西湖的坐标。')
        setRec({ kind: 'error', message: '缺少定位，无法推荐。' })
        setBusy(false)
        setPendingMessage(null)
        recommendationFlight.current.finish()
        return
      }

      setPendingMessage(options.message ?? null)
      setBusy(true)
      setNotice(null)
      try {
        const body: RecommendationRequest = { latitude, longitude }
        if (options.auto) {
          body.autoInfer = true
        }
        const filters = toRecommendationFilters(nextQuery)
        body.remainingMinutes = filters.remainingMinutes
        if (filters.maxDistanceKm !== null) body.maxDistanceKm = filters.maxDistanceKm
        if (filters.maxTicketPrice !== null) body.maxTicketPrice = filters.maxTicketPrice
        if (filters.states.length > 0) body.states = filters.states
        if (Object.keys(filters.biases).length > 0) body.biases = filters.biases
        if (options.excludeSeen) {
          body.excludeSeen = true
        }

        const data = await api.getRecommendations(sessionId, body, sessionToken)
        if (!recommendationGate.current.isCurrent(requestSequence)) return
        setRec({ kind: 'done', data })
        setLastAdjustments(null)
        setTurns((items) => [
          ...items,
          {
            id: `${data.batchNo}-${Date.now()}`,
            userMessage: options.message ?? null,
            assistantMessage: [
              options.operationFeedback,
              recommendationLead(data, nextQuery),
            ].filter(Boolean).join('。'),
            data,
          },
        ])

        // 新的一批：旧批次的理由必须清掉，否则会挂在新卡片上。
        // ⚠️ 先更新 currentBatch —— 在途的理由请求回来时会拿它做校验。
        currentBatch.current = data.batchNo
        reasonRequestedFor.current = null
        setReasonError(null)
      } catch (e: unknown) {
        if (!recommendationGate.current.isCurrent(requestSequence)) return
        setRec({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
      } finally {
        const latest = recommendationFlight.current.takeLatest()
        recommendationFlight.current.finish()
        if (recommendationGate.current.isCurrent(requestSequence)) {
          setBusy(false)
          setPendingMessage(null)
        }
        if (latest) {
          void run(latest.location, latest.query, latest.options)
        }
      }
    },
    [location, query, sessionId, sessionToken],
  )

  /**
   * 拉这一批的 AI 理由。
   *
   * @param regenerate 为 true 时强制重新生成（会真的再花一次 token）
   */
  const loadReasons = useCallback(
    async (batchNo: number, regenerate = false) => {
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
        setReasonTexts((current) => ({ ...current, ...texts }))
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
    if (rec.data.places.length === 0) return
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

    setPendingMessage(text)
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
        setPendingMessage(null)
        return
      }

      // AI 只能生成 operations，真正的状态变更统一经过纯 reducer。
      const operations = interpretOperations(result)
      const nextQuery = reduceTravelQueryOperations(query, operations)
      const operationFeedback = describeTravelQueryOperations(operations, result.unrecognized)
      setQuery(nextQuery)
      setNlText('')
      setNlState({ kind: 'idle' })
      void run(location, nextQuery, { message: text, operationFeedback })
    } catch (e: unknown) {
      if (seq !== nlSeq.current) {
        return
      }
      if (e instanceof ApiError && e.status === 501) {
        setNlState({ kind: 'noServerAi' })
        setPendingMessage(null)
        return
      }
      if (e instanceof ApiError && e.status === 400) {
        setNlState({ kind: 'keyRejected' })
        setPendingMessage(null)
        return
      }
      setNlState({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
      setPendingMessage(null)
    }
  }, [nlText, sessionId, sessionToken, query, location, run])

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

    const retryTarget = chooseAiRetryTarget(
      nlState.kind,
      rec.kind === 'done' && rec.data.places.length > 0,
    )
    if (retryTarget === 'naturalLanguage') {
      void submitNaturalLanguage()
      return
    }
    if (retryTarget !== 'reasons' || rec.kind !== 'done') {
      return
    }
    // 先占住这个批次号，免得自动模式的 effect 紧接着又发一次
    reasonRequestedFor.current = rec.data.batchNo
    void loadReasons(rec.data.batchNo)
  }, [nlState.kind, rec, loadReasons, submitNaturalLanguage])

  /**
   * 自动模式：拿定位 → 直接推荐。
   *
   * <p>定位失败不是错误路径，而是"退回手动"的信号——浏览器权限、
   * 用户拒接、超时都会走到这里，三种情况都该让人能手动填。
   */
  const startAuto = useCallback(async () => {
    const attempt = ++locationSequence.current
    if (!('geolocation' in navigator)) {
      setNotice('这个浏览器不支持定位。可以手动填坐标，或直接用杭州西湖的坐标。')
      return
    }

    navigator.geolocation.getCurrentPosition(
      (pos) => {
        if (attempt !== locationSequence.current) return
        const nextLocation = {
          latitude: pos.coords.latitude.toFixed(4),
          longitude: pos.coords.longitude.toFixed(4),
        }
        setLocation(nextLocation)
        void run(nextLocation, queryRef.current, { auto: true })
      },
      (err) => {
        if (attempt !== locationSequence.current) return
        setNotice(
          err.code === err.PERMISSION_DENIED
            ? '你拒绝了定位。手动填一下，或者直接用杭州西湖的坐标——演示景点都在杭州。'
            : `没拿到定位（${err.message}）。手动填一下，或者用杭州西湖的坐标。`,
        )
      },
      { timeout: 8000 },
    )
  }, [run])

  // 进页面就跑一次自动模式
  useEffect(() => {
    if (autoStarted.current) return
    autoStarted.current = true
    void startAuto()
  }, [startAuto])

  const applyScenario = useCallback(
    (scenario: Scenario) => {
      const nextQuery = reduceTravelQueryOperations(query, scenario.operations)
      setQuery(nextQuery)
      void run(location, nextQuery, { message: scenario.label })
    },
    [location, query, run],
  )

  const useDemoLocation = useCallback(() => {
    locationSequence.current++
    const nextLocation = { latitude: DEMO_LAT, longitude: DEMO_LNG }
    setLocation(nextLocation)
    setNotice(null)
    void run(nextLocation, query, { message: '先按杭州西湖附近帮我找' })
  }, [query, run])

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

  const setConstraint = useCallback((
    key: 'durationMinutes' | 'budgetMax',
    rawValue: string,
  ) => {
    const parsed = rawValue.trim() === '' ? null : Number(rawValue)
    if (parsed !== null && (!Number.isFinite(parsed) || parsed < 0)) return
    setQuery((current) => reduceTravelQueryOperations(current, [
      { op: 'SET_CONSTRAINT', key, value: parsed },
    ]))
  }, [])

  const setDistanceConstraint = useCallback((rawValue: string) => {
    const kilometers = rawValue.trim() === '' ? null : Number(rawValue)
    if (kilometers !== null && (!Number.isFinite(kilometers) || kilometers < 0)) return
    setQuery((current) => reduceTravelQueryOperations(current, [
      {
        op: 'SET_CONSTRAINT',
        key: 'maxDistanceMeters',
        value: kilometers === null ? null : kilometers * 1000,
      },
    ]))
  }, [])

  const manualMode = query.constraints.maxDistanceMeters !== null || query.constraints.budgetMax !== null
  const activeChips = createTravelQueryChips(query)

  const removeChip = useCallback((chip: TravelQueryChip) => {
    const nextQuery = reduceTravelQueryOperations(query, [chip.removeOperation])
    setQuery(nextQuery)
    void run(location, nextQuery, { message: `去掉“${chip.label}”这个条件` })
  }, [location, query, run])

  const clearTravelIntent = useCallback(() => {
    const nextQuery = reduceTravelQueryOperations(query, [{ op: 'CLEAR_TRAVEL_INTENT' }])
    setQuery(nextQuery)
    void run(location, nextQuery, { message: '重新来，清空我这次的想法' })
  }, [location, query, run])

  useEffect(() => {
    if (turns.length === 0 && !busy) return
    const frame = requestAnimationFrame(() => {
      streamEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
    })
    return () => cancelAnimationFrame(frame)
  }, [turns.length, busy, nlState.kind])

  return (
    <div className="travel-shell">
      <header className="travel-product-header">
        <div className="travel-product-brand">
          <span className="travel-logo" aria-hidden="true">T</span>
          <span><b>TravelMind</b><small>你的即时旅行助手</small></span>
        </div>
        <button className="preference-trigger" type="button" onClick={() => setShowPreferences(true)}>
          ⚙ 偏好设置
        </button>
      </header>

      <main className="travel-stream" aria-label="与 TravelMind 的对话">
        <div className="chat-turn assistant">
          <span className="chat-avatar" aria-hidden="true">T</span>
          <div className="chat-bubble welcome-bubble">
            <b>今天想去哪儿？</b>
            <span>告诉我你此刻的状态，我会结合位置和旅行偏好替你筛选。</span>
            {profile.reused && <small>我还记得你上次的旅行偏好，不用重新答题。</small>}
          </div>
        </div>

        {notice && (
          <div className="chat-turn assistant">
            <span className="chat-avatar" aria-hidden="true">T</span>
            <div className="chat-bubble chat-warning">
              <span>{notice}</span>
              <div className="chat-actions">
                <button className="link-btn" type="button" onClick={() => void startAuto()}>重新定位</button>
                <button className="link-btn" type="button" onClick={useDemoLocation}>先看西湖附近</button>
              </div>
            </div>
          </div>
        )}

        {turns.map((turn) => (
          <Fragment key={turn.id}>
            {turn.userMessage && (
              <div className="chat-turn user">
                <div className="chat-bubble">{turn.userMessage}</div>
              </div>
            )}
            <div className="chat-turn assistant recommendation-message">
              <span className="chat-avatar" aria-hidden="true">T</span>
              <div className="assistant-result">
                <p className="assistant-lead">{turn.assistantMessage}</p>
                <RecommendationList
                  data={turn.data}
                  myFeedback={myFeedback}
                  lastAdjustments={lastAdjustments}
                  loading={busy}
                  manualMode={manualMode}
                  reasonTexts={reasonTexts}
                  onFeedback={giveFeedback}
                  onRefresh={() => void run(location, query, { excludeSeen: true, message: '再换一批看看' })}
                />
              </div>
            </div>
          </Fragment>
        ))}

        {pendingMessage && (
          <div className="chat-turn user">
            <div className="chat-bubble">{pendingMessage}</div>
          </div>
        )}

        {busy && (
          <div className="chat-turn assistant">
            <span className="chat-avatar" aria-hidden="true">T</span>
            <div className="chat-bubble"><span className="typing-dot">正在重新理解你的需求并筛选地点…</span></div>
          </div>
        )}

        {rec.kind === 'error' && !busy && (
          <div className="chat-turn assistant">
            <span className="chat-avatar" aria-hidden="true">T</span>
            <div className="chat-bubble chat-warning">{rec.message}</div>
          </div>
        )}

        {(nlState.kind === 'nothing' || nlState.kind === 'error' || nlState.kind === 'noServerAi' || nlState.kind === 'keyRejected') && (
          <div className="chat-turn assistant">
            <span className="chat-avatar" aria-hidden="true">T</span>
            <div className="chat-bubble chat-warning">
              {nlState.kind === 'nothing' && (nlState.summary || '这句话里没有可用的旅行条件，换个说法试试。')}
              {nlState.kind === 'error' && (
                <>
                  <span>理解服务暂时不可用，已有条件没有改变：{nlState.message}</span>
                  <button className="link-btn" type="button" onClick={() => void submitNaturalLanguage()}>
                    重试这句话
                  </button>
                </>
              )}
              {nlState.kind === 'noServerAi' && '自然语言理解需要先配置 AI Key。'}
              {nlState.kind === 'keyRejected' && '当前 AI Key 无法使用，请在偏好设置里检查。'}
              {(nlState.kind === 'noServerAi' || nlState.kind === 'keyRejected') && (
                <button className="link-btn" type="button" onClick={() => setShowPreferences(true)}>打开偏好设置</button>
              )}
            </div>
          </div>
        )}
        <div ref={streamEndRef} className="travel-stream-end" aria-hidden="true" />
      </main>

      <BottomActionBar
        activeChips={activeChips}
        query={query}
        busy={busy || nlState.kind === 'loading'}
        text={nlText}
        onTextChange={setNlText}
        onSend={() => void submitNaturalLanguage()}
        onRemoveChip={removeChip}
        onScenario={applyScenario}
        onClear={clearTravelIntent}
        onOpenPreferences={() => setShowPreferences(true)}
      />

      {showPreferences && (
        <div className="preference-backdrop" role="presentation" onMouseDown={() => setShowPreferences(false)}>
          <aside className="preference-drawer" role="dialog" aria-modal="true" aria-label="TravelMind 偏好设置" onMouseDown={(e) => e.stopPropagation()}>
            <header>
              <div><small>TRAVELMIND</small><h2>偏好设置</h2></div>
              <button className="drawer-close" type="button" aria-label="关闭偏好设置" onClick={() => setShowPreferences(false)}>×</button>
            </header>

            <section>
              <h3>位置与限制</h3>
              <div className="field-row">
                {numberField('travel-lat', '纬度', location.latitude, (value) => patchLocation({ latitude: value }), '30.2420', 'decimal')}
                {numberField('travel-lng', '经度', location.longitude, (value) => patchLocation({ longitude: value }), '120.1400', 'decimal')}
              </div>
              <div className="field-row">
                {numberField('travel-minutes', '剩余分钟', nullableNumber(query.constraints.durationMinutes), (value) => setConstraint('durationMinutes', value), '240', 'numeric')}
                {numberField('travel-distance', '最远公里', query.constraints.maxDistanceMeters === null ? '' : formatKm(query.constraints.maxDistanceMeters), (value) => setDistanceConstraint(value), '不限', 'decimal')}
                {numberField('travel-budget', '预算上限', nullableNumber(query.constraints.budgetMax), (value) => setConstraint('budgetMax', value), '不限', 'numeric')}
              </div>
              <button className="btn btn-primary" type="button" disabled={busy} onClick={() => {
                setShowPreferences(false)
                void run(location, query, { message: '更新了位置与筛选条件' })
              }}>应用设置</button>
            </section>

            <section>
              <h3>AI 服务</h3>
              <div className="preference-segments">
                <span>推荐理由</span>
                <button className={reasonMode === 'auto' ? 'seg chosen' : 'seg'} type="button" onClick={() => changeReasonMode('auto')}>自动生成</button>
                <button className={reasonMode === 'manual' ? 'seg chosen' : 'seg'} type="button" onClick={() => changeReasonMode('manual')}>需要时生成</button>
              </div>
              {(aiUnavailable || aiKeyRejected || reasonError) && <p className="drawer-note">{aiKeyRejected ? '当前 Key 被厂商拒绝，请重新填写。' : reasonError ?? '当前服务器没有配置 AI，可使用自己的 Key。'}</p>}
              <AiKeyPanel onSaved={handleKeySaved} defaultOpen={aiUnavailable || aiKeyRejected} />
            </section>

            <details className="profile-details">
              <summary>查看我的旅行偏好画像</summary>
              <BarChart dimensions={profile.dimensions} />
              <button className="link-btn" type="button" onClick={onRestart}>重新测一次</button>
            </details>
          </aside>
        </div>
      )}
    </div>
  )
}

/** 新后端直接返回 operations；过渡期内也兼容正在运行的旧后端。 */
function interpretOperations(result: InterpretResponse): TravelQueryOperation[] {
  if (result.operations?.length) {
    return result.operations
  }

  const operations: TravelQueryOperation[] = result.states.map(({ key }) => (
    key === 'HUNGRY' || key === 'WANT_WALK'
      ? { op: 'ADD_INTENT' as const, value: key }
      : { op: 'ADD_PREFERENCE' as const, value: key }
  ))
  if (Object.keys(result.biases).length > 0) {
    operations.push({ op: 'MERGE_BIASES', values: result.biases })
  }
  if (result.remainingMinutes !== null) {
    operations.push({ op: 'SET_CONSTRAINT', key: 'durationMinutes', value: result.remainingMinutes })
  }
  if (result.maxDistanceKm !== null) {
    operations.push({ op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: result.maxDistanceKm * 1000 })
  }
  if (result.maxTicketPrice !== null) {
    operations.push({ op: 'SET_CONSTRAINT', key: 'budgetMax', value: result.maxTicketPrice })
  }
  return operations
}

function formatKm(meters: number): string {
  return String(Number((meters / 1000).toFixed(1)))
}

function nullableNumber(value: number | null): string {
  return value === null ? '' : String(value)
}

function recommendationLead(data: RecommendationResponse, query: TravelQueryState): string {
  const context = data.appliedContext
  if (data.places.length === 0) {
    return '这组条件下暂时没有合适的地点。可以放宽距离、预算或剩余时间，我再帮你找。'
  }

  const details: string[] = []
  if (query.constraints.durationMinutes !== null) {
    details.push(`仅剩的 ${query.constraints.durationMinutes} 分钟`)
  }
  if (context.states.length > 0) details.push(context.states.map((state) => state.label).join('、'))
  if (context.maxTicketPrice !== null) details.push(`门票 ${context.maxTicketPrice} 元以内`)
  const prefix = details.length > 0 ? `了解，结合${details.join('、')}` : '了解'
  return `${prefix}，为你推荐以下 ${data.places.length} 个地点：`
}

function BottomActionBar({
  activeChips,
  query,
  busy,
  text,
  onTextChange,
  onSend,
  onRemoveChip,
  onScenario,
  onClear,
  onOpenPreferences,
}: {
  activeChips: TravelQueryChip[]
  query: TravelQueryState
  busy: boolean
  text: string
  onTextChange: (value: string) => void
  onSend: () => void
  onRemoveChip: (chip: TravelQueryChip) => void
  onScenario: (scenario: Scenario) => void
  onClear: () => void
  onOpenPreferences: () => void
}) {
  const suggestions = SCENARIOS.filter((scenario) => !isScenarioActive(scenario, query))

  return (
    <div className="bottom-action-wrap">
      <div className="bottom-action-bar">
        <div className="action-chip-scroll" aria-label="当前条件与快捷条件">
          {activeChips.map((chip) => (
            <button className="active-action-chip" type="button" key={chip.id} onClick={() => onRemoveChip(chip)} disabled={busy}>
              {chip.label}<span aria-hidden="true">×</span>
            </button>
          ))}
          {suggestions.map((scenario) => (
            <button className="suggestion-chip" type="button" key={scenario.label} onClick={() => onScenario(scenario)} disabled={busy}>
              + {scenario.label}
            </button>
          ))}
          {activeChips.length > 0 && (
            <button className="clear-intent-chip" type="button" onClick={onClear} disabled={busy}>
              重新开始
            </button>
          )}
        </div>

        <div className="action-composer">
          <button className="composer-settings" type="button" aria-label="打开偏好设置" onClick={onOpenPreferences}>⚙</button>
          <textarea
            rows={1}
            maxLength={200}
            aria-label="继续告诉 TravelMind 你的要求"
            placeholder="继续告诉我：想吃什么、还有多久、愿意走多远…"
            value={text}
            disabled={busy}
            onChange={(event) => onTextChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                if (text.trim() && !busy) onSend()
              }
            }}
          />
          <button className="composer-send" type="button" disabled={busy || !text.trim()} onClick={onSend}>
            {busy ? '思考中' : '发送'}
          </button>
        </div>
        <small className="composer-hint">TravelMind 会保留本页对话；Enter 发送，Shift + Enter 换行</small>
      </div>
    </div>
  )
}

/** 数字/文本输入框。抽出来是因为这里要渲染六个长得一样的字段。 */
function numberField(
  id: string,
  label: string,
  value: string,
  onChange: (value: string) => void,
  placeholder: string,
  mode: 'decimal' | 'numeric',
) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        inputMode={mode}
      />
    </div>
  )
}

/** 这个场景当前是不是"生效中"——用来把按钮标成选中态。 */
function isScenarioActive(scenario: Scenario, query: TravelQueryState): boolean {
  return scenario.operations.every((operation) => {
    switch (operation.op) {
      case 'ADD_INTENT': return query.intents.includes(operation.value)
      case 'ADD_PREFERENCE': return query.preferences.includes(operation.value)
      case 'SET_CONSTRAINT': return query.constraints[operation.key] === operation.value
      default: return false
    }
  })
}

function RecommendationList({
  data,
  myFeedback,
  lastAdjustments,
  loading,
  manualMode,
  onFeedback,
  onRefresh,
  reasonTexts,
}: {
  data: RecommendationResponse
  myFeedback: Record<number, Reaction>
  lastAdjustments: DimensionAdjustment[] | null
  loading: boolean
  manualMode: boolean
  onFeedback: (recommendationId: number, reaction: Reaction) => void
  onRefresh: () => void
  reasonTexts: Record<number, string>
}) {
  return (
    <div className="recommendation-result">
      {data.places.length === 0 ? (
        <div className="empty-recommendation">
          <p>
            这不是出错了。演示数据的 59 个地点<b>全部在杭州</b>；如果你现在不在杭州，
            默认 10 公里的距离限制会把它们全部过滤掉。
          </p>
          <p>
            {manualMode ? (
              <>可以在底部取消条件，或到偏好设置放宽距离和预算。</>
            ) : (
              <>可以在偏好设置中调整位置，或直接告诉我换到杭州西湖附近。</>
            )}
          </p>
        </div>
      ) : (
        <div className="result-card-list">
          {data.places.map((place) => (
            <PlaceCard
              key={place.recommendationId}
              place={place}
              reaction={myFeedback[place.recommendationId]}
              onFeedback={onFeedback}
              reason={reasonTexts[place.recommendationId]}
            />
          ))}
        </div>
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
        <button className="result-refresh" type="button" onClick={onRefresh} disabled={loading}>
          {loading ? '正在寻找…' : '这批不合适，换一批'}
        </button>
      </div>
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
  reason,
}: {
  place: RecommendedPlace
  reaction: Reaction | undefined
  onFeedback: (recommendationId: number, reaction: Reaction) => void
  /** AI 写的那句人话，可能还没有 */
  reason?: string
}) {
  const openHours =
    place.openFrom && place.openTo ? `${place.openFrom} – ${place.openTo}` : '全天开放'
  const fallbackReason = place.reasons[0]
    ? `很符合你对“${place.reasons[0].dimensionLabel}”的偏好。`
    : place.description

  return (
    <article className="place-card">
      <div className={`place-thumb category-${place.category.toLowerCase()}`} aria-hidden="true">
        <span>{place.rank}</span>
        <b>{place.category.slice(0, 1)}</b>
      </div>

      <div className="place-content">
        <header className="place-head">
          <h3 className="place-name">{place.name}</h3>
          <span className="place-category">{place.category}</span>
        </header>

        <div className="place-meta">
          <span>📍 {place.distanceKm === null ? '距离未知' : `${place.distanceKm.toFixed(1)} km`}</span>
          <span>{place.ticketPrice === 0 ? '免费' : `门票 ¥${place.ticketPrice}`}</span>
          <span>约 {place.suggestedMinutes} 分钟</span>
          <span>{openHours}</span>
        </div>

        <p className="place-recommendation">{reason ?? fallbackReason}</p>

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
      </div>
    </article>
  )
}
