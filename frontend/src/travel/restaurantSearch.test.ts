import { describe, expect, it, vi } from 'vitest'
import { createRestaurantSearch, isRestaurantIntent, type Restaurant, type RestaurantSearchResponse } from './restaurantSearch'

const context = { sessionId: 42, sessionToken: 'test', latitude: 33.735, longitude: 113.3077, coordinateSystem: 'WGS84' as const, radius: 3000 }
function poi(id: string): Restaurant {
  return { poiId: id, name: id, type: '餐饮服务', typeCode: '050000', address: null, latitude: 33.7, longitude: 113.3, distanceMeters: 100, averageCost: null, openingHoursText: null, telephone: null, imageUrl: null, rating: null, tags: null }
}
function response(ids: string[], page = 1): RestaurantSearchResponse {
  return { restaurants: ids.map(poi), currentPage: page, searchSessionId: 'search', resultSetId: `page-${page}`, timestamp: '', status: ids.length ? 'SUCCESS' : 'NO_RESULTS', coordinateSystem: 'GCJ02', distanceType: 'STRAIGHT_LINE', radius: 3000 }
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

describe('独立餐饮候选状态', () => {
  it('每批三个，先消费候选，不足再请求第二页', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response(['a','b','c','d'])).mockResolvedValueOnce(response(['e','f','g'],2))
    const model = createRestaurantSearch(fetch); model.setContext(context)
    await model.next(); expect(model.getSnapshot().visible.map(r=>r.poiId)).toEqual(['a','b','c'])
    await model.next(); expect(fetch.mock.calls[1]?.[1]).toBe(2)
    expect(model.getSnapshot().visible.map(r=>r.poiId)).toEqual(['d','e','f'])
  })
  it('已有三个候选不访问网络，重复页明确暂无更多', async () => {
    const fetch=vi.fn().mockResolvedValueOnce(response(['a','b','c','d','e','f'])).mockResolvedValue(response(['a','b','c','d','e','f'],2))
    const model=createRestaurantSearch(fetch); model.setContext(context)
    await model.next(); await model.next(); expect(fetch).toHaveBeenCalledTimes(1)
    await model.next(); expect(model.getSnapshot().exhausted).toBe(true)
    expect(model.getSnapshot().visible).toEqual([])
  })
  it('快速换批单飞，不跳页', async () => {
    const wait=deferred<RestaurantSearchResponse>(); const fetch=vi.fn(()=>wait.promise)
    const model=createRestaurantSearch(fetch); model.setContext(context)
    const first=model.next(); void model.next(); void model.next()
    expect(fetch).toHaveBeenCalledTimes(1); wait.resolve(response(['a','b','c'])); await first
    expect(model.getSnapshot().page).toBe(1)
  })
  it('连续变更位置使旧响应无效，并从第一页重置', async () => {
    const old=deferred<RestaurantSearchResponse>(); const fetch=vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue(response(['new']))
    const model=createRestaurantSearch(fetch); model.setContext(context); const pending=model.next()
    model.setContext({...context,latitude:34}); model.setContext({...context,latitude:35})
    await model.next(); old.resolve(response(['old'])); await pending
    expect(model.getSnapshot().visible.map(r=>r.poiId)).toEqual(['new'])
    expect(fetch.mock.calls[1]?.[1]).toBe(1)
  })
  it('离开餐饮时旧响应不可复活', async () => {
    const old=deferred<RestaurantSearchResponse>(); const model=createRestaurantSearch(()=>old.promise)
    model.setContext(context); const pending=model.next(); model.setContext(null)
    old.resolve(response(['old'])); await pending
    expect(model.getSnapshot().visible).toEqual([])
  })
  it('排除不调用反馈，重返页面重复地点仍不会展示', async () => {
    const fetch=vi.fn().mockResolvedValueOnce(response(['a','b','c'])).mockResolvedValue(response(['a','b','c'],2))
    const model=createRestaurantSearch(fetch); model.setContext(context); await model.next(); model.dismiss('a')
    expect(model.getSnapshot().visible.map(r=>r.poiId)).toEqual(['b','c'])
    await model.next(); expect(model.getSnapshot().visible).toEqual([])
  })
  it('失败不推进页码，重试保留候选', async () => {
    const fetch=vi.fn().mockRejectedValueOnce(new Error('网络失败')).mockResolvedValue(response(['a']))
    const model=createRestaurantSearch(fetch); model.setContext(context); await model.next()
    expect(model.getSnapshot().error).toContain('网络'); expect(model.getSnapshot().page).toBe(0)
    await model.next(); expect(fetch.mock.calls[1]?.[1]).toBe(1); expect(model.getSnapshot().visible).toHaveLength(1)
  })
  it('未知坐标不发送搜索请求', async () => {
    const fetch=vi.fn(); const model=createRestaurantSearch(fetch); model.setContext({...context,coordinateSystem:'UNKNOWN'})
    await model.next(); expect(fetch).not.toHaveBeenCalled(); expect(model.getSnapshot().error).toContain('坐标')
  })
  it('只分流明确餐饮意图，否定和模糊情绪不分流', () => {
    for(const text of ['附近吃什么','找饭店','吃饭','附近美食','找点吃的']) expect(isRestaurantIntent(text)).toBe(true)
    for(const text of ['我饿了','不知道去哪','不想吃饭','吃完饭去散步','附近有什么景点']) expect(isRestaurantIntent(text)).toBe(false)
  })
})
