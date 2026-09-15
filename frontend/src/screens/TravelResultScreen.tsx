import { useCallback, useState } from 'react'

import { api } from '../api'
import { BarChart } from '../components/BarChart'
import type {
  RecommendationRequest,
  RecommendationResponse,
  RecommendedPlace,
  TravelProfileResponse,
} from '../types'

/**
 * 旅行测试的结果页：8 维画像 + 定位 + Top 3 推荐。
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

type RecState =
  | { kind: 'idle' }
  | { kind: 'loading' }
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
  const [lat, setLat] = useState('')
  const [lng, setLng] = useState('')
  const [minutes, setMinutes] = useState('240')
  const [rec, setRec] = useState<RecState>({ kind: 'idle' })
  const [geoMessage, setGeoMessage] = useState<string | null>(null)
  const [locating, setLocating] = useState(false)

  const sessionId = profile.sessionId

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
        setLat(pos.coords.latitude.toFixed(4))
        setLng(pos.coords.longitude.toFixed(4))
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
  }, [])

  const useDemoLocation = useCallback(() => {
    setLat(DEMO_LAT)
    setLng(DEMO_LNG)
    setGeoMessage('已填入杭州西湖的坐标（59 个演示景点都在杭州）。')
  }, [])

  const fetchRecommendations = useCallback(async () => {
    const latitude = Number(lat)
    const longitude = Number(lng)

    // 前端先挡一道，省一次必然失败的往返。后端也会校验（400）。
    if (!lat || !lng || !Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      setRec({ kind: 'error', message: '请先填写经纬度，或点上面的按钮快速填入。' })
      return
    }

    setRec({ kind: 'loading' })
    try {
      const body: RecommendationRequest = { latitude, longitude }
      const parsedMinutes = Number(minutes)
      if (Number.isFinite(parsedMinutes) && parsedMinutes > 0) {
        body.remainingMinutes = parsedMinutes
      }
      const data = await api.getRecommendations(sessionId, body, sessionToken)
      setRec({ kind: 'done', data })
    } catch (e: unknown) {
      setRec({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
    }
  }, [lat, lng, minutes, sessionId, sessionToken])

  return (
    <>
      <div className="card">
        <h1>你的旅行偏好</h1>
        <p className="lede">
          8 个维度各 1 道题，所以分数只有 0 / 25 / 50 / 75 / 100 五档。
          这份画像只是一开始的猜测——真正让它变准的，是你对推荐的反馈（下一步做）。
        </p>

        <BarChart dimensions={profile.dimensions} />

        <p className="note">
          这是初始画像，不是固定结论。同一座城市，和不同的人去、在不同季节去，
          你想要的东西都会变。
        </p>
      </div>

      <div className="card">
        <h2 className="section-title">现在，你附近最值得去哪</h2>
        <p className="lede">
          推荐要结合你的位置——不知道你在哪，"最值得做什么"就无从谈起。
          {lat && lng ? ' 定位已经填好了。' : ' 先告诉我在哪。'}
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

        {/* 结构和 AuthScreen 的表单保持一致（div.field > label + input），
            这样直接复用已有的 .field 样式，不用为这里再写一套输入框样式 */}
        <div className="field-row">
          <div className="field">
            <label htmlFor="travel-lat">纬度</label>
            <input
              id="travel-lat"
              value={lat}
              onChange={(e) => setLat(e.target.value)}
              placeholder="30.2420"
              inputMode="decimal"
            />
          </div>
          <div className="field">
            <label htmlFor="travel-lng">经度</label>
            <input
              id="travel-lng"
              value={lng}
              onChange={(e) => setLng(e.target.value)}
              placeholder="120.1400"
              inputMode="decimal"
            />
          </div>
          <div className="field">
            <label htmlFor="travel-minutes">还剩多少分钟</label>
            <input
              id="travel-minutes"
              value={minutes}
              onChange={(e) => setMinutes(e.target.value)}
              placeholder="240"
              inputMode="numeric"
            />
          </div>
        </div>

        <button
          className="btn btn-primary"
          type="button"
          onClick={() => void fetchRecommendations()}
          disabled={rec.kind === 'loading'}
        >
          {rec.kind === 'loading' ? '正在计算…' : '给我 Top 3 推荐'}
        </button>

        {rec.kind === 'error' && <p className="hint hint-error">{rec.message}</p>}
      </div>

      {rec.kind === 'done' && <RecommendationList data={rec.data} />}

      <div className="nav-row">
        <button className="theme-btn" type="button" onClick={onRestart}>
          重新测一次
        </button>
      </div>
    </>
  )
}

function RecommendationList({ data }: { data: RecommendationResponse }) {
  if (data.places.length === 0) {
    return (
      <div className="card">
        <h2 className="section-title">附近没有合适的推荐</h2>
        <p className="lede">
          这不是出错了，而是几个条件同时没满足：10 公里内没有<b>正在营业</b>、
          且停留时长装得进你还剩下的时间的景点。
        </p>
        <p className="note">
          演示数据只有杭州的 59 个景点。换"杭州西湖的坐标"，
          或者把"还剩多少分钟"调大一点再试。
        </p>
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
        <PlaceCard key={place.placeId} place={place} />
      ))}
    </div>
  )
}

function PlaceCard({ place }: { place: RecommendedPlace }) {
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
        "你很在乎自然风光（画像 100 分），这里 95 分"——两个数字都来自数据库。
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
    </article>
  )
}
