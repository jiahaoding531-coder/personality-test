// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '../api'
import { TravelResultScreen } from './TravelResultScreen'
import type { TravelProfileResponse, RecommendationResponse } from '../types'
import type { RestaurantSearchResponse } from '../travel/restaurantSearch'

const profile: TravelProfileResponse = { sessionId:42,status:'SUBMITTED',createdAt:'',submittedAt:'',scale:'TRAVEL',reused:true,dimensions:[] }
const candidate = (id:string, district:string) => ({poiId:id,name:'人民公园',address:`${district}公园路1号`,province:'河南省',city:'平顶山市',district,
  latitude:33.73512345,longitude:113.30771234,coordinateSystem:'GCJ02',source:'AMAP_POI'})
const response = {sessionId:42,batchNo:1,places:[],generatedAt:'',locationLabel:'设备位置',
  appliedContext:{now:'10:00',remainingMinutes:240,maxDistanceKm:10,maxTicketPrice:null,states:[],inferredStates:[],weather:null}} as RecommendationResponse

beforeEach(()=>{
  Element.prototype.scrollIntoView=vi.fn()
  Object.defineProperty(navigator,'geolocation',{configurable:true,value:{getCurrentPosition:(ok:PositionCallback)=>ok({coords:{latitude:30.24123456,longitude:120.14123456}} as GeolocationPosition)}})
  vi.spyOn(api,'getRecommendations').mockResolvedValue(response)
  vi.spyOn(api,'recordBehavior').mockResolvedValue({eventId:'test',duplicate:false})
  vi.spyOn(api,'searchRestaurants').mockImplementation(async c=>({restaurants:[],currentPage:1,searchSessionId:c.searchSessionId!,resultSetId:'page',timestamp:'',status:'NO_RESULTS',radius:c.radius,coordinateSystem:'GCJ02',distanceType:'STRAIGHT_LINE'}))
  vi.stubGlobal('fetch',vi.fn().mockImplementation(async()=>new Response(JSON.stringify({candidates:[candidate('a','新华区'),candidate('b','卫东区')]}))))
})
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals()})
async function openSearch(){
  fireEvent.click(screen.getByRole('button',{name:'打开偏好设置'}))
  fireEvent.change(screen.getByRole('textbox',{name:'搜索地点（仅餐饮）'}),{target:{value:'人民公园'}})
  fireEvent.click(screen.getByRole('button',{name:'搜索地名'}))
}
it('重名候选可区分；取消不改变位置，确认后只改变餐饮中心且保留原始精度',async()=>{
  render(<TravelResultScreen profile={profile} userId={1} onRestart={vi.fn()}/> )
  await waitFor(()=>expect(api.getRecommendations).toHaveBeenCalledTimes(1))
  await openSearch()
  const choices=await screen.findAllByRole('radio')
  expect(choices[0]!.parentElement!.textContent).toContain('新华区公园路1号')
  expect(choices[1]!.parentElement!.textContent).toContain('卫东区')
  fireEvent.click(choices[0]!)
  fireEvent.click(screen.getByRole('button',{name:'取消选择'}))
  expect(api.searchRestaurants).not.toHaveBeenCalled()
  expect(api.getRecommendations).toHaveBeenCalledTimes(1)
  fireEvent.change(screen.getByRole('textbox',{name:'搜索地点（仅餐饮）'}),{target:{value:'人民公园'}})
  fireEvent.click(screen.getByRole('button',{name:'搜索地名'}))
  fireEvent.click((await screen.findAllByRole('radio'))[1]!)
  fireEvent.click(screen.getByRole('button',{name:'设为餐饮搜索中心'}))
  await waitFor(()=>expect(api.searchRestaurants).toHaveBeenCalledWith(expect.objectContaining({latitude:33.73512345,longitude:113.30771234,coordinateSystem:'GCJ02'}),1))
  expect(api.getRecommendations).toHaveBeenCalledTimes(1)
  expect(screen.getByText(/餐饮搜索中心：人民公园/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button',{name:/附近逛逛/}))
  await waitFor(()=>expect(api.getRecommendations).toHaveBeenCalledTimes(2))
  expect(vi.mocked(api.getRecommendations).mock.calls[1]![1]).toMatchObject({latitude:30.24123456,longitude:120.14123456})
})
it('关键词变化后旧响应不能成为候选，切换账号或会话使搜索作废',async()=>{
  let resolve!:(value:Response)=>void
  vi.mocked(fetch).mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
  const view=render(<TravelResultScreen profile={profile} userId={1} onRestart={vi.fn()}/> )
  await openSearch()
  fireEvent.change(screen.getByRole('textbox',{name:'搜索地点（仅餐饮）'}),{target:{value:'西湖'}})
  await act(async()=>resolve(new Response(JSON.stringify({candidates:[candidate('old','旧地区')]}))))
  expect(screen.queryByText(/旧地区/)).toBeNull()
  fireEvent.click(screen.getByRole('button',{name:'搜索地名'}))
  await screen.findAllByRole('radio')
  view.rerender(<TravelResultScreen profile={{...profile,sessionId:43}} userId={2} onRestart={vi.fn()}/> )
  expect(screen.queryAllByRole('radio')).toHaveLength(0)
  expect((screen.getByRole('textbox',{name:'搜索地点（仅餐饮）'}) as HTMLInputElement).value).toBe('')
})

it('账号切换再返回时在途地名响应不能复活，已确认中心也不会跨会话沿用',async()=>{
  let resolve!:(value:Response)=>void
  vi.mocked(fetch).mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
  const view=render(<TravelResultScreen profile={profile} userId={1} onRestart={vi.fn()}/> )
  await openSearch()
  view.rerender(<TravelResultScreen profile={profile} userId={2} onRestart={vi.fn()}/> )
  view.rerender(<TravelResultScreen profile={profile} userId={1} onRestart={vi.fn()}/> )
  await act(async()=>resolve(new Response(JSON.stringify({candidates:[candidate('old','过期账号')]}))))
  expect(screen.queryAllByRole('radio')).toHaveLength(0)
  fireEvent.change(screen.getByRole('textbox',{name:'搜索地点（仅餐饮）'}),{target:{value:'人民公园'}})
  fireEvent.click(screen.getByRole('button',{name:'搜索地名'}))
  fireEvent.click((await screen.findAllByRole('radio'))[0]!)
  fireEvent.click(screen.getByRole('button',{name:'设为餐饮搜索中心'}))
  await waitFor(()=>expect(api.searchRestaurants).toHaveBeenCalledTimes(1))
  expect(screen.getByText(/餐饮搜索中心：人民公园/)).toBeTruthy()
  view.rerender(<TravelResultScreen profile={{...profile,sessionId:43}} userId={1} onRestart={vi.fn()}/> )
  expect(screen.queryByText(/餐饮搜索中心：人民公园/)).toBeNull()
  expect(vi.mocked(api.searchRestaurants).mock.calls.at(-1)![0]).toMatchObject({sessionId:43,coordinateSystem:'WGS84',latitude:30.24123456})
})

it('先确认餐饮中心再返回的设备GPS仍可用于景点，但不能覆盖已确认餐饮中心',async()=>{
  let gps!:PositionCallback
  Object.defineProperty(navigator,'geolocation',{configurable:true,value:{getCurrentPosition:(ok:PositionCallback)=>{gps=ok}}})
  render(<TravelResultScreen profile={profile} userId={1} onRestart={vi.fn()}/> )
  await openSearch()
  fireEvent.click((await screen.findAllByRole('radio'))[0]!)
  fireEvent.click(screen.getByRole('button',{name:'设为餐饮搜索中心'}))
  await waitFor(()=>expect(api.searchRestaurants).toHaveBeenCalledTimes(1))
  await act(async()=>gps({coords:{latitude:30.24123456,longitude:120.14123456}} as GeolocationPosition))
  expect(api.searchRestaurants).toHaveBeenCalledTimes(1)
  expect(screen.getByText(/餐饮搜索中心：人民公园/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button',{name:/附近逛逛/}))
  await waitFor(()=>expect(api.getRecommendations).toHaveBeenCalledWith(42,expect.objectContaining({latitude:30.24123456,longitude:120.14123456}),undefined))
})

it('位置确认后清除旧餐饮分页、排除并作废在途请求，取消保留已确认中心',async()=>{
  const restaurantPage=(ids:string[],page:number,searchSessionId:string):RestaurantSearchResponse=>({
    restaurants:ids.map(poiId=>({poiId,name:`餐饮${poiId}`,address:'公开测试地址',type:'餐饮服务',typeCode:'050000',latitude:33.73,longitude:113.3,distanceMeters:100,
      averageCost:null,openingHoursText:null,telephone:null,imageUrl:null,rating:null,tags:null})),
    currentPage:page,searchSessionId,resultSetId:`result-${page}`,timestamp:'',status:'SUCCESS',radius:3000,coordinateSystem:'GCJ02',distanceType:'STRAIGHT_LINE'})
  let late!:(page:RestaurantSearchResponse)=>void
  vi.mocked(api.searchRestaurants).mockImplementation(async(context,page)=>{
    if(page===2) return new Promise(resolve=>{late=resolve})
    return restaurantPage(['A','B','C'],1,context.searchSessionId!)
  })
  render(<TravelResultScreen profile={profile} userId={1} onRestart={vi.fn()}/> )
  await waitFor(()=>expect((screen.getByRole('button',{name:/找点吃的/}) as HTMLButtonElement).disabled).toBe(false))
  fireEvent.click(screen.getByRole('button',{name:/找点吃的/}))
  await screen.findByText('餐饮A')
  fireEvent.click(screen.getAllByRole('button',{name:'本次不再推荐'})[0]!)
  fireEvent.click(screen.getByRole('button',{name:'换一批餐饮'}))
  await waitFor(()=>expect(api.searchRestaurants).toHaveBeenCalledTimes(2))
  const oldContext=vi.mocked(api.searchRestaurants).mock.calls[0]![0]
  expect(oldContext.coordinateSystem).toBe('WGS84')
  await openSearch()
  fireEvent.click((await screen.findAllByRole('radio'))[0]!)
  fireEvent.click(screen.getByRole('button',{name:'设为餐饮搜索中心'}))
  await screen.findByText('餐饮A') // 旧中心排除过的 A 在新中心可以重新出现。
  const [newContext,newPage]=vi.mocked(api.searchRestaurants).mock.calls[2]!
  expect(newPage).toBe(1)
  expect(newContext.coordinateSystem).toBe('GCJ02')
  expect(newContext.searchSessionId).not.toBe(oldContext.searchSessionId)
  await act(async()=>late(restaurantPage(['过期'],2,oldContext.searchSessionId!)))
  expect(screen.queryByText('餐饮过期')).toBeNull()
  expect(screen.getAllByRole('link',{name:'导航过去'})[0]!.getAttribute('href')).toContain('position=113.3%2C33.73')
  await openSearch()
  await screen.findAllByRole('radio')
  fireEvent.click(screen.getByRole('button',{name:'取消选择'}))
  expect(api.searchRestaurants).toHaveBeenCalledTimes(3)
  expect(screen.getByText(/餐饮搜索中心：人民公园/)).toBeTruthy()
})
