import { useCallback, useState } from 'react'

import { api } from '../api'
import { BarChart } from '../components/BarChart'
import type {
  DimensionAdjustment,
  Reaction,
  RecommendationRequest,
  RecommendationResponse,
  RecommendedPlace,
  TravelProfileResponse,
  TravelState,
} from '../types'

/**
 * 旅行测试的结果页：8 维画像 + 当前状态 + Top 3 推荐 + 反馈。
 *
 * **为什么这个屏幕自己发请求，而不是像别的屏幕那样把状态交给 App？**
 * 因为推荐是"可以做也可以不做"的第二步：用户可能只想看看画像就走。
 * 如果用 App 那个全局的 loading 屏，一点"获取推荐"整个页面就会被替换掉，
 * 画像图表会闪一下再回来。把状态放在组件内部（和 `AiPanel` 一样），
 * 加载推荐时画像始终留在屏幕上。
 *
 * ⚠️ 59 个模拟 POI **全在杭州**，所以定位面板必须提供"用杭州西湖坐标"的兜底——
 * 否则用户点了真实定位，多半会因为超出 10 公里半径而拿到空列表。
 */

/** 演示数据的中心点，与后端 V7 种子里西湖的坐标一致。 */
const DEMO_LAT = '30.2420'
const DEMO_LNG = '120.1400'

/**
 * 表单里所有"当前处境"的输入。
 *
 * 打包成一个对象而不是六个 useState：场景按钮要一次改好几项
 * （比如"想散步"同时改状态和距离），合并起来才不会漏。
 */
interface ContextForm {
  latitude: string
  longitude: string
  remainingMinutes: string
  maxDistanceKm: string
  maxTicketPrice: string
  states: TravelState[]
}

const EMPTY_FORM: ContextForm = {
  latitude: '',
  longitude: '',
  remainingMinutes: '240',
  maxDistanceKm: '',
  maxTicketPrice: '',
  states: [],
}

/**
 * 场景快捷按钮。
 *
 * ⚠️ 这两类改动的**作用方式完全不同**，是后端刻意分开的：
 * - `states` 是"此刻的状态"，只影响**排序**（"累了"→ 费腿的地方被压下去，
 *   但不是把山全删掉）
 * - 其余三项是**硬约束**，超出直接排除（"只剩 1 小时"就不会推需要 3 小时的地方）
 */
interface Scenario {
  label: string
  patch: Partial<ContextForm>
}

const SCENARIOS: Scenario[] = [
  { label: '我想散步一下', patch: { states: ['WANT_WALK'], maxDistanceKm: '5' } },
  { label: '我有点累了', patch: { states: ['TIRED'] } },
  { label: '我想吃饭', patch: { states: ['HUNGRY'] } },
  { label: '只剩 1 小时', patch: { remainingMinutes: '60' } },
  { label: '不想走远', patch: { maxDistanceKm: '2' } },
  { label: '预算不多', patch: { maxTicketPrice: '50' } },
]

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
  /** 请求进行中。和 rec 正交：换一批时"有旧数据"和"正在加载"同时为真 */
  const [busy, setBusy] = useState(false)
  const [geoMessage, setGeoMessage] = useState<string | null>(null)
  const [locating, setLocating] = useState(false)

  /** 已经点过反馈的推荐：recommendationId → 反应。只影响按钮的选中态 */
  const [myFeedback, setMyFeedback] = useState<Record<number, Reaction>>({})
  /** 最近一次反馈造成的画像调整，用来解释"你的点击确实有影响" */
  const [lastAdjustments, setLastAdjustments] = useState<DimensionAdjustment[] | null>(null)

  const sessionId = profile.sessionId

  const patchForm = useCallback((patch: Partial<ContextForm>) => {
    setForm((f) => ({ ...f, ...patch }))
  }, [])

  /**
   * 取推荐。
   *
   * @param override 本次要覆盖的表单值。场景按钮用它——`setState` 是异步的，
   *                 点完按钮立刻发请求会拿到**旧值**，所以把要用的值直接传进来，
   *                 不依赖 state 已经更新完。
   * @param excludeSeen "换一批"传 true——排除看过、且没被点 👍 的地点。
   */
  const run = useCallback(
    async (override: Partial<ContextForm> = {}, excludeSeen = false) => {
      const values = { ...form, ...override }
      const latitude = Number(values.latitude)
      const longitude = Number(values.longitude)

      // 前端先挡一道，省一次必然失败的往返。后端也会校验（400）。
      if (
        !values.latitude ||
        !values.longitude ||
        !Number.isFinite(latitude) ||
        !Number.isFinite(longitude)
      ) {
        setRec({ kind: 'error', message: '请先填写经纬度，或点上面的按钮快速填入。' })
        return
      }

      setBusy(true)
      try {
        const body: RecommendationRequest = { latitude, longitude }
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
        if (excludeSeen) {
          body.excludeSeen = true
        }

        const data = await api.getRecommendations(sessionId, body, sessionToken)
        setRec({ kind: 'done', data })
        // 新的一批是全新的卡片，上一批的按钮状态不该跟过来
        setMyFeedback({})
        setLastAdjustments(null)
      } catch (e: unknown) {
        setRec({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
      } finally {
        setBusy(false)
      }
    },
    [form, sessionId, sessionToken],
  )

  /** 点场景按钮：写入表单 + 立刻按这组值重新推荐。 */
  const applyScenario = useCallback(
    (scenario: Scenario) => {
      patchForm(scenario.patch)
      // 把 patch 直接传给 run，不依赖 setForm 是否已经生效
      void run(scenario.patch)
    },
    [patchForm, run],
  )

  /** 用浏览器定位填进输入框。失败时给出可操作的提示，而不是只报错。 */
  const useMyLocation = useCallback(() => {
    setGeoMessage(null)

    if (!('geolocation' in navigator)) {
      setGeoMessage('这个浏览器不支持定位，请手动填写坐标，或直接用杭州西湖的坐标。')
      return
    }

    setLocating(true)
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        patchForm({
          latitude: pos.coords.latitude.toFixed(4),
          longitude: pos.coords.longitude.toFixed(4),
        })
        setLocating(false)
        // 演示数据只有杭州。拿到真实定位时提醒一句，
        // 免得用户对"推荐结果为空"感到莫名其妙。
        setGeoMessage('已填入你的真实位置。注意：演示数据只有杭州的景点，不在杭州大概率会返回空列表。')
      },
      (err) => {
        setLocating(false)
        setGeoMessage(
          err.code === err.PERMISSION_DENIED
            ? '定位被拒绝了。可以直接用杭州西湖的坐标，或者手动填写。'
            : '定位失败（' + err.message + '）。可以直接用杭州西湖的坐标。',
        )
      },
      { timeout: 8000 },
    )
  }, [patchForm])

  const useDemoLocation = useCallback(() => {
    patchForm({ latitude: DEMO_LAT, longitude: DEMO_LNG })
    setGeoMessage('已填入杭州西湖的坐标（59 个演示景点都在杭州）。')
  }, [patchForm])

  const giveFeedback = useCallback(
    async (recommendationId: number, reaction: Reaction) => {
      // 先乐观地更新按钮状态，失败再回滚——点按钮要有即时反馈
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

  const numberField = (
    id: string,
    label: string,
    key: keyof ContextForm,
    placeholder: string,
    mode: 'decimal' | 'numeric',
  ) => (
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

  return (
    <>
      <div className="card">
        <h1>你的旅行偏好</h1>
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
        <p className="lede">
          {form.latitude && form.longitude
            ? '定位已经填好了。下面这些是"此刻的处境"——它们只影响这一次推荐，不会改变你的画像。'
            : '推荐要结合你的位置。先告诉我在哪。'}
        </p>

        <div className="locate-actions">
          <button className="theme-btn" type="button" onClick={useMyLocation} disabled={locating}>
            {locating ? '定位中…' : '📍 使用我的定位'}
          </button>
          <button className="theme-btn" type="button" onClick={useDemoLocation}>
            用杭州西湖的坐标
          </button>
        </div>

        {geoMessage && <p className="hint">{geoMessage}</p>}

        <div className="field-row">
          {numberField('travel-lat', '纬度', 'latitude', '30.2420', 'decimal')}
          {numberField('travel-lng', '经度', 'longitude', '120.1400', 'decimal')}
        </div>

        <p className="field-label">现在是什么情况？（点一下立刻重新推荐）</p>
        <div className="scenario-row">
          {SCENARIOS.map((s) => {
            const active = isScenarioActive(s, form)
            return (
              <button
                key={s.label}
                className={active ? 'scenario-btn chosen' : 'scenario-btn'}
                type="button"
                disabled={busy}
                onClick={() => applyScenario(s)}
              >
                {s.label}
              </button>
            )
          })}
        </div>

        <div className="field-row">
          {numberField('travel-minutes', '还剩多少分钟', 'remainingMinutes', '240', 'numeric')}
          {numberField('travel-distance', '最远走多少公里', 'maxDistanceKm', '不限', 'decimal')}
          {numberField('travel-budget', '门票最多多少钱', 'maxTicketPrice', '不限', 'numeric')}
        </div>

        <button
          className="btn btn-primary"
          type="button"
          onClick={() => void run()}
          disabled={busy}
        >
          {busy ? '正在计算…' : '给我 Top 3 推荐'}
        </button>

        {rec.kind === 'error' && <p className="hint hint-error">{rec.message}</p>}
      </div>

      {/* 刷新期间保留上一批卡片（busy 只改按钮文案），避免"点一下全没了"的闪烁 */}
      {rec.kind === 'done' && (
        <RecommendationList
          data={rec.data}
          myFeedback={myFeedback}
          lastAdjustments={lastAdjustments}
          loading={busy}
          onFeedback={giveFeedback}
          onRefresh={() => void run({}, true)}
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

/** 这个场景当前是不是"生效中"——用来把按钮标成选中态，让用户知道现在是什么情况。 */
function isScenarioActive(scenario: Scenario, form: ContextForm): boolean {
  const { patch } = scenario
  if (patch.states) {
    // 状态是数组，比长度和内容
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
  onFeedback,
  onRefresh,
}: {
  data: RecommendationResponse
  myFeedback: Record<number, Reaction>
  lastAdjustments: DimensionAdjustment[] | null
  loading: boolean
  onFeedback: (recommendationId: number, reaction: Reaction) => void
  onRefresh: () => void
}) {
  if (data.places.length === 0) {
    return (
      <div className="card">
        <h2 className="section-title">附近没有合适的推荐</h2>
        <p className="lede">
          这不是出错了，而是几个条件同时没满足：没有<b>正在营业</b>、
          且停留时长装得进你还剩下的时间、门票也在预算内的地点。
        </p>
        <p className="note">
          如果你一路点"换一批"到这里，说明附近合适的地方都看过了——
          把"还剩多少分钟"调大、或者放宽距离和预算再试。
          演示数据只有杭州的 59 个景点，用"杭州西湖的坐标"最稳。
        </p>
        <button className="theme-btn" type="button" onClick={onRefresh}>
          再试一次
        </button>
      </div>
    )
  }

  return (
    <div className="card">
      <h2 className="section-title">
        Top {data.places.length}
        <span className="batch-tag">第 {data.batchNo} 批</span>
      </h2>

      {data.places.map((place) => (
        <PlaceCard
          key={place.recommendationId}
          place={place}
          reaction={myFeedback[place.recommendationId]}
          onFeedback={onFeedback}
        />
      ))}

      {/*
        反馈的影响必须当场可见。画像是"下次推荐才用到"的东西，
        不给任何回执的话，用户很容易以为按钮是坏的。
      */}
      {lastAdjustments && lastAdjustments.length > 0 && (
        <p className="hint">
          已记下你的反馈。下次推荐会使用调整后的画像：
          {lastAdjustments.map((a) => (
            <span key={a.key} className="adjust-chip">
              {a.name} {a.questionnaireScore} → {a.effectiveScore}
            </span>
          ))}
          <br />
          问卷结果本身没有变——修正只作用于推荐。
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

function PlaceCard({
  place,
  reaction,
  onFeedback,
}: {
  place: RecommendedPlace
  reaction: Reaction | undefined
  onFeedback: (recommendationId: number, reaction: Reaction) => void
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

      {/*
        推荐理由是**算出来的**，不是大模型编的：
        "你很在乎自然风光（画像 50 分），这里 95 分"——两个数字都来自数据库。
        计划书第七节把"理解、总结、解释"交给 AI，把"距离、时间、评分排序"留给传统算法。
      */}
      {place.reasons.length > 0 && (
        <ul className="reasons">
          {place.reasons.map((r) => (
            <li key={r.dimensionKey}>
              你很在乎「{r.dimensionLabel}」<b>{r.userPreference}</b>，这里 <b>{r.placeValue}</b>
            </li>
          ))}
        </ul>
      )}

      <div className="feedback-row">
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
