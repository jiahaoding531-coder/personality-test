import { describe, expect, it, vi } from 'vitest'
import { createRestaurantSearch, type RestaurantContext, type RestaurantSearchResponse } from './restaurantSearch'

const context = { sessionId: 42, latitude: 33.735, longitude: 113.3077, coordinateSystem: 'WGS84' as const, radius: 3000 }
const response = (c: RestaurantContext, page: number, ids: string[]): RestaurantSearchResponse => ({
  restaurants: ids.map(poiId => ({ poiId, name: poiId, type: null, typeCode: null, address: null,
    latitude: 33.7, longitude: 113.3, distanceMeters: 100, averageCost: null, openingHoursText: null,
    telephone: null, imageUrl: null, rating: null, tags: null })),
  currentPage: page, searchSessionId: c.searchSessionId!, resultSetId: `page-${page}`, timestamp: '',
  status: 'SUCCESS', radius: 3000, coordinateSystem: 'GCJ02', distanceType: 'STRAIGHT_LINE',
})
describe('餐饮逐卡来源', () => {
  it('分页同一逻辑ID且跨页混合卡片保留各自来源', async () => {
    const fetch = vi.fn(async (c: RestaurantContext, page: number) => response(c, page, page === 1 ? ['a','b','c','d'] : ['e','f','g']))
    const model = createRestaurantSearch(fetch); model.setContext(context)
    await model.next(); await model.next()
    expect(fetch.mock.calls[0]![0].searchSessionId).toBeTruthy()
    expect(fetch.mock.calls[1]![0].searchSessionId).toBe(fetch.mock.calls[0]![0].searchSessionId)
    expect(model.getSnapshot().visible.map(r => r.source.resultSetId)).toEqual(['page-1','page-2','page-2'])
    expect(model.getSnapshot().visible.map(r => r.source.page)).toEqual([1,2,2])
  })
  it('重复设置同条件不重建但位置和账号变化必须重建搜索', async () => {
    const fetch = vi.fn(async (c: RestaurantContext, page: number) => response(c, page, ['a']))
    const model = createRestaurantSearch(fetch); model.setContext(context); await model.next()
    const first = model.getSnapshot().searchSessionId
    model.setContext({...context}); expect(model.getSnapshot().searchSessionId).toBe(first)
    model.setContext({...context, latitude: 34}); expect(model.getSnapshot().searchSessionId).not.toBe(first)
    const second = model.getSnapshot().searchSessionId
    model.setContext({...context, latitude: 34, userId: 2}); expect(model.getSnapshot().searchSessionId).not.toBe(second)
    expect(model.getSnapshot().page).toBe(0); expect(model.getSnapshot().excluded).toEqual([])
  })
  it('410需要显式重开第一页不能混用旧候选', async () => {
    const fetch = vi.fn(async (c: RestaurantContext, page: number) => response(c, page, ['a','b','c','d']))
    const model = createRestaurantSearch(fetch); model.setContext(context); await model.next()
    const old = model.getSnapshot().searchSessionId
    fetch.mockRejectedValueOnce(Object.assign(new Error('搜索上下文已失效，请重新搜索'),{status:410,code:'RESTAURANT_CONTEXT_EXPIRED'}))
    await model.next(); expect(model.getSnapshot().error).toContain('重新搜索')
    expect(model.getSnapshot().contextExpired).toBe(true)
    model.restart(); await model.next()
    expect(fetch.mock.calls.at(-1)?.[1]).toBe(1)
    expect(model.getSnapshot().searchSessionId).not.toBe(old)
  })
})
