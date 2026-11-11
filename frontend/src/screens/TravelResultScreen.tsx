import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type RefObject } from 'react'
import { createRestaurantSearch, isRestaurantIntent, type RestaurantCandidate } from '../travel/restaurantSearch'
import { createBehaviorClient, createBehaviorMetadata, isTemporaryBehaviorError } from '../travel/behaviorEvents'
import type { BehaviorDraft, BehaviorMetadata } from '../travel/behaviorTypes'
import { RestaurantResults } from '../components/RestaurantResults'
import { DiningLocationPicker } from '../components/DiningLocationPicker'
import type { LocationCandidate } from '../travel/locationSearch'

import { ApiError, api } from '../api'
import { fromBrowserPosition, fromUnconfirmedPosition, type LocationState } from '../travel/travelLocation'
import { AiKeyPanel } from '../components/AiKeyPanel'
import { BarChart } from '../components/BarChart'
import { TravelConversationWelcome } from '../components/TravelConversationWelcome'
import type {
  DimensionAdjustment,
  FeedbackReactionState,
  RecommendationReplyTarget,
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
  interpretOperations,
  reduceTravelQueryOperations,
  toRecommendationFilters,
  type TravelQueryOperation,
  type TravelQueryChip,
  type TravelQueryState,
} from '../travel/travelQueryState'
import {
  applyFeedbackOperations,
  clearReplyTarget,
  selectReplyTarget,
} from '../travel/travelFeedbackState'
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
    // 只有用户明确选择自动时才付费生成；新用户和非法存储值默认按需。
    return localStorage.getItem(REASON_MODE_KEY) === 'auto' ? 'auto' : 'manual'
  } catch {
    // ⚠️ 隐私模式 / 禁用 Cookie 时 localStorage 会直接抛异常。
    // 一个偏好设置读不到，不该让整个结果页崩掉。
    return 'manual'
  }
}

function saveReasonMode(mode: ReasonMode) {
  try {
    localStorage.setItem(REASON_MODE_KEY, mode)
  } catch {
    // 存不下就算了，只影响"下次进来还记不记得"，不影响这次使用
  }
}

interface RecommendationRunOptions {
  excludeSeen?: boolean
  auto?: boolean
  message?: string | null
  operationFeedback?: string
  onSuccess?: () => void
  onFailure?: () => void
  /** 调用方上下文变化后，丢弃已经在途的推荐结果。 */
  isStillRelevant?: () => boolean
}

interface RecommendationRunTask {
  location: LocationState
  query: TravelQueryState
  options: RecommendationRunOptions
}

const EMPTY_LOCATION: LocationState = {
  latitude: '',
  longitude: '',
  coordinateSystem: 'UNKNOWN',
}

interface Scenario {
  label: string
  shortLabel: string
  icon: string
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
  { label: '我想散步一下', shortLabel: '附近逛逛', icon: '🧭', operations: [{ op: 'ADD_INTENT', value: 'WANT_WALK' }] },
  { label: '我有点累了', shortLabel: '轻松一点', icon: '🌿', operations: [{ op: 'ADD_PREFERENCE', value: 'TIRED' }] },
  { label: '我想吃饭', shortLabel: '找点吃的', icon: '🍜', operations: [{ op: 'ADD_INTENT', value: 'HUNGRY' }] },
  { label: '想安静点', shortLabel: '安静一点', icon: '☕', operations: [{ op: 'ADD_PREFERENCE', value: 'QUIET' }] },
  { label: '只剩 1 小时', shortLabel: '只有 1 小时', icon: '⏱', operations: [{ op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 60 }] },
  { label: '不想走远', shortLabel: '离我近些', icon: '📍', operations: [{ op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 2000 }] },
  { label: '预算不多', shortLabel: '预算友好', icon: '¥', operations: [{ op: 'SET_CONSTRAINT', key: 'budgetMax', value: 50 }] },
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

type FeedbackState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; message: string }

type RecState =
  | { kind: 'idle' }
  | { kind: 'done'; data: RecommendationResponse }
  | { kind: 'error'; message: string }

interface ChatTurn {
  id: string
  userMessage: string | null
  assistantMessage: string
  data?: RecommendationResponse
}

interface FeedbackRefreshRetry {
  location: LocationState
  query: TravelQueryState
  excludeSeen: boolean
}

export function TravelResultScreen({
  profile,
  sessionToken,
  onRestart,
  userId = null,
}: {
  profile: TravelProfileResponse
  sessionToken?: string
  onRestart: () => void
  userId?: number | null
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
  const [locationDisplayName, setLocationDisplayName] = useState<string | null>(null)

  const [myFeedback, setMyFeedback] = useState<Record<number, Reaction>>({})
  const [lastAdjustments, setLastAdjustments] = useState<DimensionAdjustment[] | null>(null)

  // ---- 自然语言输入 ----
  const [nlText, setNlText] = useState('')
  const [nlState, setNlState] = useState<NlState>({ kind: 'idle' })
  const [feedbackState, setFeedbackState] = useState<FeedbackState>({ kind: 'idle' })
  /** 当前输入是在回复哪一张推荐卡片；为空时保持原有普通条件输入。 */
  const [replyTarget, setReplyTarget] = useState<RecommendationReplyTarget | null>(null)
  const [feedbackRefreshRetry, setFeedbackRefreshRetry] = useState<FeedbackRefreshRetry | null>(null)

  /**
   * 请求序号，用来**丢弃过期响应**。
   *
   * ⚠️ 和「换一批」是同一类问题：连着提交两次，先发的可能后回来，
   * 把第一次的理解结果盖在第二次上面。界面上看不出错——
   * 两段话都是通顺的，只是对不上用户最后一次说的话。
   */
  const nlSeq = useRef(0)
  const feedbackSeq = useRef(0)
  const composerInputRef = useRef<HTMLTextAreaElement | null>(null)
  /** 推荐请求也只允许最后一次更新界面，避免旧响应覆盖用户刚改的新条件。 */
  const recommendationGate = useRef(createLatestRequestGate())
  /** 请求发出到落库前都不允许第二条推荐进入，保持 UI 当前批次就是数据库最新批次。 */
  const recommendationFlight = useRef(createSingleFlightGate<RecommendationRunTask>())
  /** 普通推荐失败时保留原请求快照，让用户重试而不用重新输入条件。 */
  const recommendationRetry = useRef<RecommendationRunTask | null>(null)
  /** 用户手动改定位后，让更早发起的浏览器定位回调失效。 */
  const locationSequence = useRef(0)

  // ---- AI 理由 ----
  const [reasonTexts, setReasonTexts] = useState<Record<number, string>>({})
  const [reasonMode, setReasonMode] = useState<ReasonMode>(loadReasonMode)
  const [reasonError, setReasonError] = useState<string | null>(null)
  const [reasonBusy, setReasonBusy] = useState(false)
  const reasonInFlight = useRef<string | null>(null)
  const reasonOwner = useRef('')
  const reasonContextVersion = useRef(0)
  const reasonOwnerKey = `${userId ?? 'guest'}:${profile.sessionId}`
  if (reasonOwner.current !== reasonOwnerKey) {
    reasonOwner.current = reasonOwnerKey
    reasonContextVersion.current += 1
  }
  useEffect(() => {
    reasonInFlight.current = null
    setReasonBusy(false)
    setReasonTexts({})
    setReasonError(null)
    setAiUnavailable(false)
    setAiKeyRejected(false)
  }, [reasonOwnerKey])
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
  const behaviorClient = useRef<ReturnType<typeof createBehaviorClient> | null>(null)
  const behaviorGeneration = useRef(0)
  type ReactionCommand = { metadata: BehaviorMetadata; previous?: Reaction; reaction: Reaction; cancelling: boolean; failed: boolean }
  const reactionCommands = useRef(new Map<number,ReactionCommand>())
  const reactionVersions = useRef<Record<number,number>>({})
  /** 服务端版本低于已确认版本时忽略；旧接口的无版本应答也不能覆盖新确认。 */
  const acceptReactionState = useCallback((id: number, state: FeedbackReactionState | null | undefined,
      fallback: Reaction | null, expected: number) => {
    const currentVersion=reactionVersions.current[id] ?? 0
    if(state ? state.version < currentVersion : currentVersion > expected) return false
    if(state) reactionVersions.current[id]=state.version
    const reaction=state ? state.reaction : fallback
    setMyFeedback(current=>{ const next={...current}; if(reaction) next[id]=reaction; else delete next[id]; return next })
    return true
  },[])
  const [reactionPending,setReactionPending] = useState<Record<number,boolean>>({})
  const [reactionErrors,setReactionErrors] = useState<Record<number,string>>({})
  useEffect(() => {
    const generation = ++behaviorGeneration.current
    const client = createBehaviorClient(api.recordBehavior,{ userId, sessionId, sessionToken, generation })
    behaviorClient.current = client
    reactionCommands.current.clear(); reactionVersions.current={}; setMyFeedback({}); setReactionPending({}); setReactionErrors({})
    return () => { client.dispose(); behaviorClient.current = null; behaviorGeneration.current++; reactionCommands.current.clear() }
  },[userId,sessionId,sessionToken])
  const recordBehavior = useCallback((draft: BehaviorDraft) => {
    try { behaviorClient.current?.record(draft) } catch { /* 行为记录失败不阻断导航、排除、换批。 */ }
  },[])
  const [diningActive, setDiningActive] = useState(false)
  const diningMode = useRef(false)
  // 已确认的高德中心独立保存，绝不能传给使用历史景点坐标的 run/反馈链路。
  const locationOwner=JSON.stringify([userId,sessionId,sessionToken])
  const [diningCenter,setDiningCenter]=useState<{owner:string;candidate:LocationCandidate} | null>(null)
  const diningCenterRevision=useRef(0)
  const confirmedCenter=diningCenter?.owner===locationOwner ? diningCenter.candidate : null
  const diningLocation=confirmedCenter ? {
    latitude:String(confirmedCenter.latitude),longitude:String(confirmedCenter.longitude),coordinateSystem:confirmedCenter.coordinateSystem,
  } : location
  useLayoutEffect(()=>{setDiningCenter(null)},[locationOwner])
  const [restaurants] = useState(() => createRestaurantSearch(api.searchRestaurants))
  const restaurantState = useSyncExternalStore(restaurants.subscribe, restaurants.getSnapshot)
  useLayoutEffect(() => {
    restaurants.setContext(diningActive ? {
      sessionId, sessionToken, userId, latitude: diningLocation.latitude ? Number(diningLocation.latitude) : NaN,
      longitude: diningLocation.longitude ? Number(diningLocation.longitude) : NaN,
      coordinateSystem: diningLocation.coordinateSystem, radius: query.constraints.maxDistanceMeters ?? 3000,
    } : null)
    if (diningActive) void restaurants.next()
  }, [restaurants, diningActive, sessionId, sessionToken, userId, diningLocation.latitude, diningLocation.longitude, diningLocation.coordinateSystem, query.constraints.maxDistanceMeters])
  useEffect(() => () => restaurants.setContext(null), [restaurants])

  const switchExperience = (dining: boolean) => {
    diningMode.current = dining
    if (!dining) restaurants.setContext(null)
    setDiningActive(dining)
  }

  const nextRestaurants = useCallback(() => {
    const before=restaurants.getSnapshot()
    if(before.loading || before.exhausted) return
    if(before.contextExpired) { restaurants.restart(); void restaurants.next(); return }
    // 失败后的重试仍属于上一次用户动作，不能重复记录成主动换批。
    if(before.error) { void restaurants.next(); return }
    if(before.lastSource) recordBehavior({targetType:'RESTAURANT',provider:'AMAP',targetId:null,action:'NEXT_BATCH_REQUESTED',
      searchSessionId:before.lastSource.searchSessionId,resultSetId:before.lastSource.resultSetId,
      visibleSources:before.visible.map(r=>({targetId:r.poiId,searchSessionId:r.source.searchSessionId,resultSetId:r.source.resultSetId}))})
    void restaurants.next()
  },[restaurants,recordBehavior])

  /**
   * 进页面时自动跑一次的守卫。
   *
   * ⚠️ 用 ref 而不是 state：StrictMode 下 effect 会跑两次，
   * 用 state 做守卫时第二次执行读到的还是旧值，会重复发请求。
   */
  const autoStarted = useRef(false)

  /** 上下文发生变化时，反馈响应只能作废，不能把旧条件写回来。 */
  const invalidateFeedbackRequest = useCallback(() => {
    feedbackSeq.current += 1
    setFeedbackRefreshRetry(null)
    setFeedbackState({ kind: 'idle' })
    setPendingMessage(null)
  }, [])

  const patchLocation = useCallback((patch: Partial<LocationState>) => {
    invalidateFeedbackRequest()
    locationSequence.current++
    setLocationDisplayName(null)
    setDiningCenter(null)
    setLocation((current) => ({ ...current, ...patch, coordinateSystem: 'UNKNOWN' }))
  }, [invalidateFeedbackRequest])

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

      recommendationRetry.current = task
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
        if (!recommendationGate.current.isCurrent(requestSequence)
          || options.isStillRelevant?.() === false) return
        setRec({ kind: 'done', data })
        recommendationRetry.current = null
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
        options.onSuccess?.()
      } catch (e: unknown) {
        if (!recommendationGate.current.isCurrent(requestSequence)
          || options.isStillRelevant?.() === false) return
        setRec({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
        options.onFailure?.()
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
      const owner = reasonOwner.current
      const contextVersion = reasonContextVersion.current
      const requestKey = `${owner}:${contextVersion}:${batchNo}`
      if (reasonInFlight.current === requestKey) return
      reasonInFlight.current = requestKey
      setReasonBusy(true)
      setReasonError(null)
      setAiKeyRejected(false)
      try {
        const res = await api.getTravelReasons(sessionId, regenerate, sessionToken, batchNo)

        // ⚠️ 两道校验，缺一不可。见 currentBatch 的注释。
        //
        // ① 后端给的是不是明确请求的那批？即使另一标签页换批，
        //    也只能接受这次请求绑定的批次，避免错配解释。
        // ② 我们要的那批还是当前展示的吗？用户可能已经点了「换一批」，
        //    旧批次的理由贴到新列表上完全看不出错——句子都是通顺的。
        if (contextVersion !== reasonContextVersion.current || owner !== reasonOwner.current || res.sessionId !== sessionId
          || res.batchNo !== batchNo || batchNo !== currentBatch.current) {
          return
        }

        const texts: Record<number, string> = {}
        for (const item of res.reasons) {
          texts[item.recommendationId] = item.reason
        }
        setReasonTexts((current) => ({ ...current, ...texts }))
      } catch (e: unknown) {
        if (contextVersion !== reasonContextVersion.current || owner !== reasonOwner.current || batchNo !== currentBatch.current) return
        if (e instanceof ApiError && e.status === 501) {
          // 这台服务器没替访客配 AI。以前这里是把入口藏起来，
          // 现在改成引导他填自己的 key——见 AiReasonState.unavailable 的注释。
          setAiUnavailable(true)
          return
        }
        if (e instanceof ApiError && e.status === 400 && !e.code) {
          // 400 = 调用方给的东西有问题（厂商名认不出 / key 被上游拒了）。
          // 后端刻意把它和 502（上游故障）分开，就是为了让这里能弹"重新填写"
          // 而不是"重试"——重试一万次还是 401。
          setAiKeyRejected(true)
          return
        }
        setReasonError(e instanceof Error ? e.message : String(e))
      } finally {
        if (reasonInFlight.current === requestKey) {
          reasonInFlight.current = null
          setReasonBusy(false)
        }
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

  const chooseReplyTarget = useCallback((target: RecommendationReplyTarget) => {
    invalidateFeedbackRequest()
    setReplyTarget((current) => selectReplyTarget(current, target))
    setFeedbackState({ kind: 'idle' })
    requestAnimationFrame(() => {
      composerInputRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      composerInputRef.current?.focus()
    })
  }, [invalidateFeedbackRequest])

  const cancelReply = useCallback(() => {
    invalidateFeedbackRequest()
    setReplyTarget((current) => clearReplyTarget(current))
    setFeedbackState({ kind: 'idle' })
    setPendingMessage(null)
  }, [invalidateFeedbackRequest])

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

    if (isRestaurantIntent(text)) {
      nlSeq.current++
      switchExperience(true)
      setNlText('')
      if (diningActive) nextRestaurants()
      return
    }
    switchExperience(false)

    invalidateFeedbackRequest()
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
  }, [invalidateFeedbackRequest, nlText, sessionId, sessionToken, query, location, run, diningActive, nextRestaurants])

  /**
   * 针对某张推荐卡片发送自然语言反馈。
   *
   * <p>反馈请求使用独立序号；上下文变化会调用失效函数，
   * 更早回来的响应就只能被丢弃，不能覆盖最新 query 或聊天上下文。</p>
   */
  const submitFeedback = useCallback(async () => {
    const text = nlText.trim()
    const target = replyTarget
    const latitude = Number(location.latitude)
    const longitude = Number(location.longitude)
    if (!text || !target || !Number.isFinite(latitude) || !Number.isFinite(longitude)) return

    const seq = ++feedbackSeq.current
    const expectedVersion=reactionVersions.current[target.recommendationId] ?? 0
    setFeedbackState({ kind: 'loading' })
    setPendingMessage(text)

    try {
      const response = await api.createFeedbackEvent(
        sessionId,
        {
          text,
          recommendationId: target.recommendationId,
          query,
          latitude,
          longitude,
          // 按钮应答未确认时，新自然语言操作由服务端在解析前读实际版本；
          // 不能把客户端尚未获知的版本误当成用户仍想执行旧动作。
          expectedFeedbackVersion: reactionCommands.current.has(target.recommendationId) ? undefined : expectedVersion,
        },
        sessionToken,
      )

      if (seq !== feedbackSeq.current) return

      const applied = applyFeedbackOperations(query, response)
      const nextQuery = applied.query
      setQuery(nextQuery)
      if(response.feedbackState || response.reaction) acceptReactionState(target.recommendationId,
        response.feedbackState,response.reaction,expectedVersion)
      setNlText('')
      setReplyTarget((current) => clearReplyTarget(current))
      setFeedbackState({ kind: 'idle' })
      setTurns((items) => [
        ...items,
        {
          id: `feedback-${response.eventId}`,
          userMessage: text,
          assistantMessage: applied.message,
        },
      ])

      if (response.shouldRecommend || response.excludeSeen) {
        const refreshRetry: FeedbackRefreshRetry = {
          location,
          query: nextQuery,
          excludeSeen: response.excludeSeen,
        }
        setFeedbackRefreshRetry(null)
        void run(location, nextQuery, {
          message: null,
          excludeSeen: response.excludeSeen,
          isStillRelevant: () => seq === feedbackSeq.current,
          onSuccess: () => setFeedbackRefreshRetry(null),
          onFailure: () => setFeedbackRefreshRetry(refreshRetry),
        })
        return
      }

      setPendingMessage(null)
    } catch (e: unknown) {
      if (seq !== feedbackSeq.current) return
      setFeedbackState({
        kind: 'error',
        message: e instanceof ApiError && e.status === 400
          ? '这句话暂时无法用于这张卡片，请调整后重试。'
          : '反馈服务暂时不可用，请稍后重试。',
      })
      // 保留 nlText 与 replyTarget，用户可以直接修正或重试原话。
      setPendingMessage(null)
    }
  }, [location, nlText, query, replyTarget, sessionId, sessionToken, run, acceptReactionState])

  const retryFeedbackRecommendation = useCallback(() => {
    const retry = feedbackRefreshRetry
    if (!retry) return
    const seq = feedbackSeq.current
    setFeedbackRefreshRetry(null)
    void run(retry.location, retry.query, {
      excludeSeen: retry.excludeSeen,
      message: null,
      isStillRelevant: () => seq === feedbackSeq.current,
      onSuccess: () => setFeedbackRefreshRetry(null),
      onFailure: () => setFeedbackRefreshRetry(retry),
    })
  }, [feedbackRefreshRetry, run])

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
    invalidateFeedbackRequest()
    const attempt = ++locationSequence.current
    const centerRevision=diningCenterRevision.current
    if (window.isSecureContext === false) {
      setNotice('手机浏览器只允许网页在 HTTPS 环境读取 GPS。请使用 HTTPS 地址，或打开位置设置改用杭州西湖测试位置或手动坐标。')
      return
    }
    if (!('geolocation' in navigator)) {
      setNotice('这个浏览器不支持定位。可以手动填坐标，或直接用杭州西湖的坐标。')
      return
    }

    navigator.geolocation.getCurrentPosition(
      (pos) => {
        if (attempt !== locationSequence.current) return
        const nextLocation = fromBrowserPosition(pos.coords)
        // 较早的 GPS 仍可更新设备位置，但不能清掉后来确认的餐饮搜索中心。
        if(centerRevision===diningCenterRevision.current) setDiningCenter(null)
        setLocation(nextLocation)
        setLocationDisplayName(`当前位置 · ${pos.coords.latitude.toFixed(4)}, ${pos.coords.longitude.toFixed(4)}`)
        setNotice(null)
        // 餐饮位置变化由独立搜索状态处理，不创建景点推荐批次。
        if (!diningMode.current) void run(nextLocation, queryRef.current, { auto: true })
      },
      (err) => {
        if (attempt !== locationSequence.current) return
        if (err.code === err.PERMISSION_DENIED) {
          setNotice('没有获得定位权限。请在浏览器设置中允许定位，或打开位置设置使用测试位置。')
        } else if (err.code === err.TIMEOUT) {
          setNotice('定位请求超时。可以重新定位，或改用杭州西湖测试位置。')
        } else {
          setNotice(`暂时无法获取当前位置（${err.message || '定位不可用'}）。可以打开位置设置使用测试位置或手动坐标。`)
        }
      },
      { enableHighAccuracy: true, maximumAge: 60_000, timeout: 10_000 },
    )
  }, [invalidateFeedbackRequest, run])

  // 进页面就跑一次自动模式
  useEffect(() => {
    if (autoStarted.current) return
    autoStarted.current = true
    void startAuto()
  }, [startAuto])

  const applyScenario = useCallback(
    (scenario: Scenario) => {
      invalidateFeedbackRequest()
      const nextQuery = reduceTravelQueryOperations(query, scenario.operations)
      setQuery(nextQuery)
      void run(location, nextQuery, { message: scenario.label })
    },
    [invalidateFeedbackRequest, location, query, run],
  )

  const useDemoLocation = useCallback(() => {
    invalidateFeedbackRequest()
    locationSequence.current++
    const nextLocation = fromUnconfirmedPosition(DEMO_LAT, DEMO_LNG)
    setDiningCenter(null)
    setLocation(nextLocation)
    setLocationDisplayName('杭州西湖')
    setShowPreferences(false)
    setNotice(null)
    if (!diningMode.current) void run(nextLocation, query, { message: '先按杭州西湖附近帮我找' })
  }, [invalidateFeedbackRequest, query, run])

  const usePingdingshanDemoLocation = useCallback(() => {
    invalidateFeedbackRequest()
    locationSequence.current++
    const nextLocation = fromUnconfirmedPosition('33.7350', '113.3077')
    setDiningCenter(null)
    setLocation(nextLocation)
    setLocationDisplayName('平顶山市中心')
    setShowPreferences(false)
    setNotice(null)
    if (!diningMode.current) void run(nextLocation, query, { message: '先按平顶山市中心附近帮我找' })
  }, [invalidateFeedbackRequest, query, run])

  const saveReactionCommand = useCallback(async (recommendationId: number, command: ReactionCommand) => {
      const generation = behaviorGeneration.current
      command.failed = false; setReactionErrors(current=>{ const next={...current}; delete next[recommendationId]; return next })
      try {
        if (command.cancelling) {
          const state=await api.removeFeedback(sessionId, recommendationId, sessionToken, command.metadata)
          if(generation !== behaviorGeneration.current) return
          if(acceptReactionState(recommendationId,state || undefined,null,command.metadata.expectedFeedbackVersion ?? 0)) setLastAdjustments(null)
        } else {
          const res = await api.giveFeedback(sessionId, recommendationId, command.reaction, sessionToken, command.metadata)
          if(generation !== behaviorGeneration.current) return
          if(acceptReactionState(recommendationId,res.feedbackState,res.reaction,command.metadata.expectedFeedbackVersion ?? 0)) setLastAdjustments(res.adjustments)
        }
        reactionCommands.current.delete(recommendationId)
        setReactionPending(current=>({...current,[recommendationId]:false}))
      } catch(error) {
        if(generation !== behaviorGeneration.current) return
        command.failed = true
        const temporary = isTemporaryBehaviorError(error)
        if(!temporary) { reactionCommands.current.delete(recommendationId); setReactionPending(current=>({...current,[recommendationId]:false})) }
        setReactionErrors(current=>({...current,[recommendationId]: temporary ? '反馈保存尚未确认，请重试；确认前暂不接受该地点的新反馈。' : '反馈未保存，请检查登录或重新获取推荐。'}))
        acceptReactionState(recommendationId,undefined,command.previous ?? null,command.metadata.expectedFeedbackVersion ?? 0)
      }
  },[sessionId,sessionToken,acceptReactionState])

  const giveFeedback = useCallback((recommendationId: number, reaction: Reaction) => {
      if(reactionCommands.current.has(recommendationId)) return
      const previousReaction = myFeedback[recommendationId], cancelling = previousReaction === reaction
      const command: ReactionCommand = { metadata:{...createBehaviorMetadata(),expectedFeedbackVersion:reactionVersions.current[recommendationId] ?? 0},previous:previousReaction,reaction,cancelling,failed:false }
      reactionCommands.current.set(recommendationId,command)
      setReactionPending(current=>({...current,[recommendationId]:true}))
      setMyFeedback((current) => {
        const next = { ...current }
        if (cancelling) delete next[recommendationId]
        else next[recommendationId] = reaction
        return next
      })
      void saveReactionCommand(recommendationId,command)
    }, [myFeedback,saveReactionCommand])

  const restaurantDraft = (r: RestaurantCandidate, action: 'NAVIGATION_REQUESTED'|'EXCLUDE'): BehaviorDraft => ({
    targetType:'RESTAURANT',provider:'AMAP',targetId:r.poiId,action,searchSessionId:r.source.searchSessionId,resultSetId:r.source.resultSetId,
  })
  const dismissRestaurant = (id: string) => {
    const r=restaurants.getSnapshot().visible.find(item=>item.poiId===id)
    if(!r || restaurants.getSnapshot().excluded.includes(id)) return
    restaurants.dismiss(id); recordBehavior(restaurantDraft(r,'EXCLUDE'))
  }
  const refreshScenic = (data: RecommendationResponse) => {
    if(recommendationFlight.current.isRunning() || diningMode.current) return
    recordBehavior({targetType:'PLACE',provider:'LOCAL',targetId:null,action:'NEXT_BATCH_REQUESTED',
      ...(data.places.length?{recommendationBatchNo:data.batchNo}:{}),
      visibleSources:data.places.map(p=>({recommendationId:p.recommendationId,targetId:String(p.placeId)}))})
    void run(location,query,{excludeSeen:true,message:'再换一批看看'})
  }

  const setConstraint = useCallback((
    key: 'durationMinutes' | 'budgetMax',
    rawValue: string,
  ) => {
    invalidateFeedbackRequest()
    const parsed = rawValue.trim() === '' ? null : Number(rawValue)
    if (parsed !== null && (!Number.isFinite(parsed) || parsed < 0)) return
    setQuery((current) => reduceTravelQueryOperations(current, [
      { op: 'SET_CONSTRAINT', key, value: parsed },
    ]))
  }, [invalidateFeedbackRequest])

  const setDistanceConstraint = useCallback((rawValue: string) => {
    invalidateFeedbackRequest()
    const kilometers = rawValue.trim() === '' ? null : Number(rawValue)
    if (kilometers !== null && (!Number.isFinite(kilometers) || kilometers < 0)) return
    setQuery((current) => reduceTravelQueryOperations(current, [
      {
        op: 'SET_CONSTRAINT',
        key: 'maxDistanceMeters',
        value: kilometers === null ? null : kilometers * 1000,
      },
    ]))
  }, [invalidateFeedbackRequest])

  const manualMode = query.constraints.maxDistanceMeters !== null || query.constraints.budgetMax !== null
  const activeChips = createTravelQueryChips(query)
  const interactionBusy = busy || nlState.kind === 'loading' || feedbackState.kind === 'loading'
  const appliedContext = rec.kind === 'done' ? rec.data.appliedContext : null
  const welcomeContext = {
    locationLabel: rec.kind === 'done'
      ? rec.data.locationLabel ?? locationDisplayName
      : locationDisplayName,
    durationMinutes: query.constraints.durationMinutes ?? appliedContext?.remainingMinutes ?? null,
    maxDistanceKm: query.constraints.maxDistanceMeters === null
      ? appliedContext?.maxDistanceKm ?? null
      : query.constraints.maxDistanceMeters / 1000,
    budgetMax: query.constraints.budgetMax ?? appliedContext?.maxTicketPrice ?? null,
  }
  const quickNeeds = SCENARIOS.map((scenario) => ({
    id: scenario.label,
    icon: scenario.icon,
    label: scenario.shortLabel,
    active: isScenarioActive(scenario, query),
  }))

  const removeChip = useCallback((chip: TravelQueryChip) => {
    invalidateFeedbackRequest()
    const nextQuery = reduceTravelQueryOperations(query, [chip.removeOperation])
    setQuery(nextQuery)
    void run(location, nextQuery, { message: `去掉“${chip.label}”这个条件` })
  }, [invalidateFeedbackRequest, location, query, run])

  const clearTravelIntent = useCallback(() => {
    invalidateFeedbackRequest()
    const nextQuery = reduceTravelQueryOperations(query, [{ op: 'CLEAR_TRAVEL_INTENT' }])
    setQuery(nextQuery)
    void run(location, nextQuery, { message: '重新来，清空我这次的想法' })
  }, [invalidateFeedbackRequest, location, query, run])

  useEffect(() => {
    if (turns.length === 0 && !busy) return
    const frame = requestAnimationFrame(() => {
      streamEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
    })
    return () => cancelAnimationFrame(frame)
  }, [turns.length, busy, nlState.kind])

  return (
    <div className="travel-shell">
      <main className="travel-stream" aria-label="与 TravelMind 的对话">
        <TravelConversationWelcome
          context={diningActive ? { ...welcomeContext, locationLabel: confirmedCenter?.name ?? locationDisplayName, maxDistanceKm: (query.constraints.maxDistanceMeters ?? 3000) / 1000 } : welcomeContext}
          quickNeeds={quickNeeds}
          reusedProfile={profile.reused}
          busy={diningActive ? false : interactionBusy}
          onSelectNeed={(id) => {
            if (id === '我想吃饭') {
              nlSeq.current++
              switchExperience(true)
              if (diningActive) nextRestaurants()
              return
            }
            switchExperience(false)
            const scenario = SCENARIOS.find((candidate) => candidate.label === id)
            if (scenario) applyScenario(scenario)
          }}
          onOpenPreferences={() => setShowPreferences(true)}
        />

        {diningActive && <p className="dining-center-summary">{confirmedCenter
          ? `餐饮搜索中心：${confirmedCenter.name} · ${[confirmedCenter.city,confirmedCenter.district,confirmedCenter.address].filter(Boolean).join(' · ')}（非设备当前位置，仅用于餐饮）`
          : location.coordinateSystem==='WGS84' ? '餐饮搜索中心：设备 GPS 位置'
          : '餐饮搜索中心：原有测试或手动位置（坐标系待确认，请重新定位或搜索地名）'}</p>}
        {diningActive && <RestaurantResults state={restaurantState} onNext={nextRestaurants} onDismiss={dismissRestaurant}
          onNavigate={r=>recordBehavior(restaurantDraft(r,'NAVIGATION_REQUESTED'))} onLocation={() => setShowPreferences(true)} />}
        <div hidden={diningActive}>
        {notice && (
          <div className="chat-turn assistant">
            <span className="chat-avatar" aria-hidden="true">T</span>
            <div className="chat-bubble chat-warning">
              <span>{notice}</span>
              <div className="chat-actions">
                <button className="link-btn" type="button" onClick={() => void startAuto()}>重新定位</button>
                <button className="link-btn" type="button" onClick={() => setShowPreferences(true)}>打开位置设置</button>
                <button className="link-btn" type="button" onClick={useDemoLocation}>杭州西湖测试位置</button>
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
                {turn.data && (
                  <RecommendationList
                    data={turn.data}
                    myFeedback={myFeedback}
                    lastAdjustments={lastAdjustments}
                    loading={busy || feedbackState.kind === 'loading'}
                    manualMode={manualMode}
                    reasonTexts={reasonTexts}
                    onFeedback={giveFeedback}
                    reactionPending={reactionPending}
                    onNavigate={place=>recordBehavior({targetType:'PLACE',provider:'LOCAL',targetId:String(place.placeId),recommendationId:place.recommendationId,action:'NAVIGATION_REQUESTED'})}
                    onReply={chooseReplyTarget}
                    onRefresh={() => refreshScenic(turn.data!)}
                  />
                )}
              </div>
            </div>
          </Fragment>
        ))}

        {!diningActive && rec.kind === 'done' && rec.data.places.length > 0 && (
          <section aria-label="AI 推荐解释">
            <button className="fb-btn" type="button" disabled={busy || reasonBusy}
              onClick={() => { if (rec.kind === 'done') void loadReasons(rec.data.batchNo) }}>
              {reasonBusy ? '正在生成 AI 解释…' : '生成 AI 解释'}
            </button>
            <small>按需生成；AI 不改变推荐排序。解释不可用时仍可使用原推荐。</small>
            {reasonError && <p role="alert">{reasonError}</p>}
            {aiKeyRejected && <div role="alert">
              <p>当前 AI Key 无法使用，请检查 AI 配置。原推荐仍可正常使用。</p>
              <button className="fb-btn" type="button" onClick={() => setShowPreferences(true)}>检查 AI 配置</button>
            </div>}
            {aiUnavailable && <p role="status">AI 暂未配置，原有推荐和餐饮功能仍可使用。</p>}
          </section>
        )}
        {Object.entries(reactionErrors).map(([id,message])=><div role="alert" key={id} className="restaurant-error"><p>{message}</p>
          {reactionCommands.current.get(Number(id))?.failed && <button type="button" onClick={()=>{ const command=reactionCommands.current.get(Number(id)); if(command?.failed) void saveReactionCommand(Number(id),command) }}>重试保存反馈</button>}
        </div>)}
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
            <div className="chat-bubble chat-warning">
              <span>{rec.message}</span>
              {feedbackRefreshRetry && (
                <button className="link-btn" type="button" onClick={retryFeedbackRecommendation}>
                  仅重试推荐
                </button>
              )}
              {!feedbackRefreshRetry && recommendationRetry.current && (
                <button
                  className="link-btn"
                  type="button"
                  onClick={() => {
                    const retry = recommendationRetry.current
                    if (retry) void run(retry.location, retry.query, retry.options)
                  }}
                >
                  重试推荐
                </button>
              )}
            </div>
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
        {feedbackState.kind === 'error' && (
          <div className="chat-turn assistant">
            <span className="chat-avatar" aria-hidden="true">T</span>
            <div className="chat-bubble chat-warning">
              <span>这条反馈暂时没有记下，原话和回复地点都还在，可以重试：{feedbackState.message}</span>
              <button className="link-btn" type="button" onClick={() => void submitFeedback()}>
                重试这条反馈
              </button>
            </div>
          </div>
        )}
        </div>
        <div ref={streamEndRef} className="travel-stream-end" aria-hidden="true" />
      </main>

      <BottomActionBar
        activeChips={activeChips}
        busy={diningActive ? false : interactionBusy}
        text={nlText}
        replyTarget={diningActive ? null : replyTarget}
        inputRef={composerInputRef}
        onTextChange={setNlText}
        onSend={() => void (!diningActive && replyTarget ? submitFeedback() : submitNaturalLanguage())}
        onClearReply={cancelReply}
        onRemoveChip={removeChip}
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

            <DiningLocationPicker key={locationOwner} scope={{sessionId,sessionToken}} onConfirm={candidate=>{
              // 只升级餐饮中心版本；不能取消景点侧仍在等待的设备定位。
              diningCenterRevision.current++
              setDiningCenter({owner:locationOwner,candidate})
              switchExperience(true)
              setShowPreferences(false)
            }}/>
            {confirmedCenter && <section>
              <p>已选餐饮中心：{confirmedCenter.name}；设备位置与景点位置未改变。</p>
              <p className="drawer-note">原有位置可能是设备 GPS、测试点或手动坐标；坐标系未确认时不能用于餐饮搜索。</p>
              <button className="btn" type="button" onClick={()=>setDiningCenter(null)}>恢复原有位置搜索餐饮</button>
            </section>}
            <section>
              <h3>位置与限制</h3>
              <div className="location-test-actions">
                <button type="button" disabled={interactionBusy} onClick={() => {
                  setShowPreferences(false)
                  void startAuto()
                }}>使用我的当前位置</button>
                <button type="button" disabled={interactionBusy} onClick={useDemoLocation}>杭州西湖测试位置</button>
                <button type="button" disabled={interactionBusy} onClick={usePingdingshanDemoLocation}>平顶山市中心测试位置</button>
              </div>
              <p className="drawer-note">真机 GPS 需要 HTTPS；无法授权时可用测试位置或手动坐标。</p>
              <div className="field-row">
                {numberField('travel-lat', '纬度', location.latitude, (value) => patchLocation({ latitude: value }), '30.2420', 'decimal')}
                {numberField('travel-lng', '经度', location.longitude, (value) => patchLocation({ longitude: value }), '120.1400', 'decimal')}
              </div>
              <div className="field-row">
                {numberField('travel-minutes', '剩余分钟', nullableNumber(query.constraints.durationMinutes), (value) => setConstraint('durationMinutes', value), '240', 'numeric')}
                {numberField('travel-distance', '最远公里', query.constraints.maxDistanceMeters === null ? '' : formatKm(query.constraints.maxDistanceMeters), (value) => setDistanceConstraint(value), '不限', 'decimal')}
                {numberField('travel-budget', '预算上限', nullableNumber(query.constraints.budgetMax), (value) => setConstraint('budgetMax', value), '不限', 'numeric')}
              </div>
              <button className="btn btn-primary" type="button" disabled={interactionBusy} onClick={() => {
                const latitude = Number(location.latitude)
                const longitude = Number(location.longitude)
                if (Number.isFinite(latitude) && Number.isFinite(longitude)) {
                  setLocationDisplayName(`手动位置 · ${latitude.toFixed(4)}, ${longitude.toFixed(4)}`)
                }
                setShowPreferences(false)
                if (!diningMode.current) void run(location, query, { message: '更新了位置与筛选条件' })
              }}>应用位置与限制</button>
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
  busy,
  text,
  replyTarget,
  inputRef,
  onTextChange,
  onSend,
  onClearReply,
  onRemoveChip,
  onClear,
  onOpenPreferences,
}: {
  activeChips: TravelQueryChip[]
  busy: boolean
  text: string
  replyTarget: RecommendationReplyTarget | null
  inputRef: RefObject<HTMLTextAreaElement | null>
  onTextChange: (value: string) => void
  onSend: () => void
  onClearReply: () => void
  onRemoveChip: (chip: TravelQueryChip) => void
  onClear: () => void
  onOpenPreferences: () => void
}) {
  return (
    <div className="bottom-action-wrap">
      <div className="bottom-action-bar">
        <div className="action-chip-scroll" aria-label="当前旅行条件">
          {activeChips.map((chip) => (
            <button className="active-action-chip" type="button" key={chip.id} onClick={() => onRemoveChip(chip)} disabled={busy}>
              {chip.label}<span aria-hidden="true">×</span>
            </button>
          ))}
          {activeChips.length > 0 && (
            <button className="clear-intent-chip" type="button" onClick={onClear} disabled={busy}>
              重新开始
            </button>
          )}
        </div>

        <div className="action-composer">
          {replyTarget && (
            <div className="reply-context" role="status">
              <span>正在回复：<b>{replyTarget.placeName}</b></span>
              <button type="button" aria-label="取消回复地点" onClick={onClearReply}>×</button>
            </div>
          )}
          <button className="composer-settings" type="button" aria-label="打开偏好设置" onClick={onOpenPreferences}>⚙</button>
          <textarea
            ref={inputRef}
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
  onReply,
  onRefresh,
  reasonTexts,
  reactionPending,
  onNavigate,
}: {
  data: RecommendationResponse
  myFeedback: Record<number, Reaction>
  lastAdjustments: DimensionAdjustment[] | null
  loading: boolean
  manualMode: boolean
  onFeedback: (recommendationId: number, reaction: Reaction) => void
  onReply: (target: RecommendationReplyTarget) => void
  onRefresh: () => void
  reasonTexts: Record<number, string>
  reactionPending: Record<number,boolean>
  onNavigate: (place: RecommendedPlace) => void
}) {
  return (
    <div className="recommendation-result">
      {data.places.length === 0 ? (
        <div className="empty-recommendation">
          <p>
            这不是出错了。当前地点库覆盖杭州和平顶山，但附近没有同时满足条件的地点；
            TravelMind 不会为了凑满 Top 3 而突破你设置的距离限制。
          </p>
          <p>
            {manualMode ? (
              <>可以在底部取消条件，或到偏好设置放宽距离和预算。</>
            ) : (
              <>可以在偏好设置中调整位置，或使用杭州西湖、平顶山市中心测试位置。</>
            )}
          </p>
        </div>
      ) : (
        <>
          <header className="recommendation-heading">
            <div><span>为你精选</span><h2>Top {data.places.length} 推荐</h2></div>
            {data.locationLabel && <small>📍 {data.locationLabel}</small>}
          </header>
          <div className="result-card-list">
            {data.places.map((place) => (
              <PlaceCard
                key={place.recommendationId}
                place={place}
                reaction={myFeedback[place.recommendationId]}
                onFeedback={onFeedback}
                onReply={onReply}
                onNavigate={()=>onNavigate(place)}
                disabled={loading || !!reactionPending[place.recommendationId]}
                reason={reasonTexts[place.recommendationId]}
              />
            ))}
          </div>
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

      <div className="recommendation-refresh-row">
        <span>没有心动的？我会避开已经看过的地点。</span>
        <button className="result-refresh" type="button" onClick={onRefresh} disabled={loading}>
          {loading ? '正在寻找…' : '换一批'}
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
  onReply,
  disabled,
  reason,
  onNavigate,
}: {
  place: RecommendedPlace
  reaction: Reaction | undefined
  onFeedback: (recommendationId: number, reaction: Reaction) => void
  onReply: (target: RecommendationReplyTarget) => void
  disabled: boolean
  /** AI 写的那句人话，可能还没有 */
  reason?: string
  onNavigate: () => void
}) {
  const openHours = place.openFrom === null && place.openTo === null
    ? '全天开放'
    : place.openFrom && place.openTo
      ? `${place.openFrom} – ${place.openTo}`
      : '营业时间未知'
  const category = placeCategoryPresentation(place.category)
  const fallbackReason = place.reasons[0]
    ? `很符合你对“${place.reasons[0].dimensionLabel}”的偏好。`
    : place.description

  return (
    <article className="place-card">
      <div className={`place-thumb category-${place.category.toLowerCase()}`} aria-hidden="true">
        <span>{place.rank}</span>
        <b>{category.icon}</b>
      </div>

      <div className="place-content">
        <header className="place-head">
          <h3 className="place-name">{place.name}</h3>
          <span className="place-category">{category.label}</span>
        </header>

        <div className="place-meta">
          <span>📍 {place.distanceKm === null ? '距离未知' : `${place.distanceKm.toFixed(1)} km`}</span>
          <span>{place.ticketPrice === 0 ? '免费' : `门票 ¥${place.ticketPrice}`}</span>
          <span>约 {place.suggestedMinutes} 分钟</span>
          <span>{openHours}</span>
        </div>

        <p className="place-recommendation"><span>推荐理由</span>{reason ?? fallbackReason}</p>

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
          onClick={onNavigate}
        >
          🧭 导航过去
        </a>
        <button
          className={reaction === 'LIKE' ? 'fb-btn chosen' : 'fb-btn'}
          type="button"
          aria-pressed={reaction === 'LIKE'}
          disabled={disabled}
          onClick={() => onFeedback(place.recommendationId, 'LIKE')}
        >
          👍 想去
        </button>
        <button
          className={reaction === 'DISLIKE' ? 'fb-btn chosen' : 'fb-btn'}
          type="button"
          aria-pressed={reaction === 'DISLIKE'}
          disabled={disabled}
          onClick={() => onFeedback(place.recommendationId, 'DISLIKE')}
        >
          👎 不感兴趣
        </button>
        <button
          className="fb-btn reply-btn"
          type="button"
          disabled={disabled}
          onClick={() => onReply({ recommendationId: place.recommendationId, placeName: place.name })}
        >
          针对这里说一句
        </button>
        </div>
      </div>
    </article>
  )
}

function placeCategoryPresentation(category: string): { label: string; icon: string } {
  const presentations: Record<string, { label: string; icon: string }> = {
    NATURE: { label: '自然风光', icon: '⛰' },
    CULTURE: { label: '人文历史', icon: '🏛' },
    FOOD: { label: '美食探索', icon: '🍜' },
    PHOTO: { label: '摄影打卡', icon: '📷' },
    DISTRICT: { label: '城市街区', icon: '🏘' },
    MUSEUM: { label: '博物馆', icon: '🏛' },
    MARKET: { label: '市集', icon: '🛍' },
  }
  return presentations[category] ?? { label: category, icon: '📍' }
}
