import { Fragment, useCallback, useEffect, useRef, useState } from 'react'

import { api } from '../api'
import { BarChart } from '../components/BarChart'
import type {
  AppliedContext,
  DimensionAdjustment,
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
}

const EMPTY_FORM: ContextForm = {
  latitude: '',
  longitude: '',
  remainingMinutes: '240',
  maxDistanceKm: '',
  maxTicketPrice: '',
  states: [],
}

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
  const [busy, setBusy] = useState(false)
  const [showManual, setShowManual] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const [myFeedback, setMyFeedback] = useState<Record<number, Reaction>>({})
  const [lastAdjustments, setLastAdjustments] = useState<DimensionAdjustment[] | null>(null)

  const sessionId = profile.sessionId

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
        if (options.excludeSeen) {
          body.excludeSeen = true
        }

        const data = await api.getRecommendations(sessionId, body, sessionToken)
        setRec({ kind: 'done', data })
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
}: {
  place: RecommendedPlace
  showDistance: boolean
  showState: boolean
  showWeather: boolean
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
        {open ? '收起' : '为什么是它？'}
      </button>

      {open && (
        <div className="why-body">
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

function PlaceCard({
  place,
  reaction,
  onFeedback,
  showDistance,
  showState,
  showWeather,
}: {
  place: RecommendedPlace
  reaction: Reaction | undefined
  onFeedback: (recommendationId: number, reaction: Reaction) => void
  /** 这几个只影响「为什么是它」里列出哪几项，原样透传给 WhyPanel */
  showDistance: boolean
  showState: boolean
  showWeather: boolean
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
      />

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
