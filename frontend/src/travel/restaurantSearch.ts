import type { CoordinateSystem } from './travelLocation'

export interface Restaurant {
  poiId: string; name: string; type: string | null; typeCode: string | null; address: string | null
  latitude: number; longitude: number; distanceMeters: number
  averageCost: number | null; openingHoursText: string | null; telephone: string | null
  imageUrl: string | null; rating: number | null; tags: string[] | null
}
export interface RestaurantSearchResponse {
  restaurants: Restaurant[]; currentPage: number; searchSessionId: string; timestamp: string
  resultSetId: string
  status: string; radius: number; coordinateSystem: string; distanceType: string
}
export interface RestaurantContext {
  sessionId: number; sessionToken?: string; latitude: number; longitude: number
  coordinateSystem: CoordinateSystem; radius: number
  userId?: number | null; searchSessionId?: string
}
export interface RestaurantSource { searchSessionId: string; resultSetId: string; page: number }
export interface RestaurantCandidate extends Restaurant { source: RestaurantSource }
export interface RestaurantState {
  page: number; candidates: RestaurantCandidate[]; visible: RestaurantCandidate[]; shown: string[]; excluded: string[]
  loading: boolean; exhausted: boolean; error: string | null; searchSessionId: string | null
  contextExpired: boolean
  lastSource: RestaurantSource | null
}
const empty = (): RestaurantState => ({ page: 0, candidates: [], visible: [], shown: [], excluded: [], loading: false, exhausted: false, error: null, searchSessionId: null, contextExpired: false, lastSource: null })

/** 仅明确短句分流；否定、先后计划、模糊情绪交回原对话能力。 */
export function isRestaurantIntent(text: string) {
  return /^(?:我想|我要|想|请|帮我)?(?:附近吃什么|附近有什么饭店|附近有什么餐厅|找饭店|找餐厅|吃饭|附近美食|找点吃的|推荐饭店|推荐餐厅)[。！!？?\s]*$/.test(text.trim())
}

/** 独立状态机：序号作废旧请求，同一页单飞，不依赖景点状态或反馈接口。 */
export function createRestaurantSearch(fetchPage: (context: RestaurantContext, page: number) => Promise<RestaurantSearchResponse>) {
  let state = empty()
  let context: RestaurantContext | null = null
  let generation = 0
  const listeners = new Set<() => void>()
  const update = (patch: Partial<RestaurantState>) => { state = { ...state, ...patch }; listeners.forEach(fn => fn()) }
  const model = {
    getSnapshot: () => state,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } },
    setContext: (next: RestaurantContext | null) => {
      if (JSON.stringify(context) === JSON.stringify(next)) return
      context = next; generation++; state = { ...empty(), searchSessionId: next ? crypto.randomUUID() : null }; listeners.forEach(fn => fn())
    },
    restart: () => { generation++; state = { ...empty(), searchSessionId: context ? crypto.randomUUID() : null }; listeners.forEach(fn => fn()) },
    dismiss: (id: string) => update({ excluded: [...new Set([...state.excluded, id])], visible: state.visible.filter(p => p.poiId !== id), candidates: state.candidates.filter(p => p.poiId !== id) }),
    next: async () => {
      if (!context || state.loading || state.exhausted || state.contextExpired) return
      if (context.coordinateSystem === 'UNKNOWN' || !Number.isFinite(context.latitude) || !Number.isFinite(context.longitude)) {
        update({ error: '位置坐标或坐标系尚未确认，请打开位置设置并重新定位。' }); return
      }
      const sequence = generation
      const current = context
      update({ loading: true, error: null })
      try {
        let candidates = [...state.candidates]
        let page = state.page
        let exhausted = false
        let lastSource = state.lastSource
        if (candidates.length < 3) {
          if (page >= 25) exhausted = true
          else {
            const response = await fetchPage({ ...current, searchSessionId: state.searchSessionId! }, page + 1)
            if (sequence !== generation) return
            if (response.currentPage !== page + 1) throw new Error('餐饮分页响应与请求不一致，请确认后端已更新。')
            if (!response.resultSetId || !response.searchSessionId) throw new Error('餐饮来源信息缺失，请确认后端已更新。')
            lastSource = { searchSessionId: response.searchSessionId, resultSetId: response.resultSetId, page: response.currentPage }
            const known = new Set([...state.shown, ...state.excluded, ...candidates.map(p => p.poiId)])
            const fresh = response.restaurants.filter(p => { if (known.has(p.poiId)) return false; known.add(p.poiId); return true })
            candidates.push(...fresh.map(r => ({ ...r, source: { searchSessionId: response.searchSessionId, resultSetId: response.resultSetId, page: response.currentPage } })))
            page = response.currentPage
            // 不声称知道高德总数；本页没有新候选则结束本轮，避免循环请求。
            exhausted = fresh.length === 0
          }
        }
        if (sequence !== generation) return
        const visible = candidates.splice(0, 3)
        update({ visible, candidates, page, lastSource, shown: [...state.shown, ...visible.map(p => p.poiId)], exhausted, loading: false })
      } catch (error) {
        if (sequence !== generation) return
        update({ loading: false, contextExpired: typeof error === 'object' && error !== null && 'status' in error && error.status === 410,
          error: error instanceof Error ? error.message : '餐饮搜索失败，请重试。' })
      }
    },
  }
  return model
}
