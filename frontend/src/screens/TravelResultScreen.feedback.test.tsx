// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, api } from '../api'
import type { RecommendationResponse, TravelProfileResponse } from '../types'
import { TravelResultScreen } from './TravelResultScreen'

function placeResponse(openFrom: string | null = null, openTo: string | null = null): RecommendationResponse {
  return {
    sessionId: 42,
    batchNo: 1,
    generatedAt: '2026-09-18T10:00:00Z',
    locationLabel: '西湖附近',
    appliedContext: {
      now: '10:00:00',
      remainingMinutes: 240,
      maxDistanceKm: 10,
      maxTicketPrice: null,
      states: [],
      inferredStates: [],
      weather: null,
    },
    places: [{
      rank: 1,
      recommendationId: 91,
      placeId: 7,
      name: '西湖湿地',
      latitude: 30.24,
      longitude: 120.14,
      category: 'NATURE',
      description: '适合散步',
      scorePercent: 88,
      scoreBreakdown: { interest: 0.8, distance: 0.9, quality: 0.9, state: 1, weather: 1, finalScore: 0.65 },
      distanceKm: 2.2,
      ticketPrice: 0,
      suggestedMinutes: 90,
      openFrom,
      openTo,
      reasons: [],
    }],
  }
}

const profile: TravelProfileResponse = {
  sessionId: 42,
  status: 'SUBMITTED',
  createdAt: '2026-09-18T09:00:00Z',
  submittedAt: '2026-09-18T09:05:00Z',
  scale: 'TRAVEL',
  reused: false,
  dimensions: [],
}

function setup(recommendation = placeResponse()) {
  const recordBehavior = vi.spyOn(api, 'recordBehavior').mockResolvedValue({eventId:'test',duplicate:false})
  const getRecommendations = vi.spyOn(api, 'getRecommendations').mockResolvedValue(recommendation)
  vi.spyOn(api, 'getTravelReasons').mockResolvedValue({
    sessionId: 42,
    batchNo: 1,
    provider: 'test',
    cached: true,
    reasons: [],
  })
  vi.spyOn(api, 'giveFeedback').mockImplementation(async (_session,id,reaction) => ({ recommendationId:id,reaction,adjustments:[] }))
  vi.spyOn(api, 'removeFeedback').mockResolvedValue(undefined)
  const createFeedbackEvent = vi.spyOn(api, 'createFeedbackEvent')
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition: (success: (position: { coords: { latitude: number; longitude: number } }) => void) =>
        success({ coords: { latitude: 30.24, longitude: 120.14 } }),
    },
  })
  Element.prototype.scrollIntoView = vi.fn()
  return { recommendation, getRecommendations, createFeedbackEvent, recordBehavior }
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('TravelResultScreen 推荐反馈交互', () => {
  it('解释Key被拒时主页给出可操作错误，不必先打开抽屉', async () => {
    setup()
    vi.mocked(api.getTravelReasons).mockRejectedValue(new ApiError('测试凭据被拒',400))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button',{name:'生成 AI 解释'}))
    expect((await screen.findByRole('alert')).textContent).toContain('当前 AI Key 无法使用')
    fireEvent.click(screen.getByRole('button',{name:'检查 AI 配置'}))
    expect(screen.getByRole('dialog')).toBeTruthy()
  })
  it('解释请求长度错误显示真实原因而不是误报Key错误', async () => {
    setup()
    vi.mocked(api.getTravelReasons).mockRejectedValue(new ApiError('AI 请求超过长度限制',400,[],'AI_REQUEST_TOO_LARGE'))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button',{name:'生成 AI 解释'}))
    expect((await screen.findByRole('alert')).textContent).toContain('AI 请求超过长度限制')
    expect(screen.queryByRole('button',{name:'检查 AI 配置'})).toBeNull()
  })
  it('解释请求绑定当前显示的推荐批次', async () => {
    setup({ ...placeResponse(), batchNo: 3 })
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')

    fireEvent.click(screen.getByRole('button', { name: '生成 AI 解释' }))

    expect(api.getTravelReasons).toHaveBeenCalledWith(42, false, 'token', 3)
  })
  it('账号切换再切回时同一批次的旧解释也不能复活', async () => {
    setup()
    let resolve!: (response: Awaited<ReturnType<typeof api.getTravelReasons>>) => void
    vi.mocked(api.getTravelReasons).mockImplementation(() => new Promise(r => { resolve = r }))
    const view=render(<TravelResultScreen userId={1} profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button',{name:'生成 AI 解释'}))
    view.rerender(<TravelResultScreen userId={2} profile={profile} sessionToken="token2" onRestart={vi.fn()} />)
    view.rerender(<TravelResultScreen userId={1} profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    resolve({sessionId:42,batchNo:1,provider:'test',cached:false,reasons:[{recommendationId:91,rank:1,reason:'旧账号上下文解释'}]})
    await new Promise(r=>setTimeout(r,0))
    expect(screen.queryByText('旧账号上下文解释')).toBeNull()
  })
  it('按需解释连续点击只发一条请求，模型未返回时原推荐仍可见', async () => {
    setup()
    let resolve!: (response: Awaited<ReturnType<typeof api.getTravelReasons>>) => void
    vi.mocked(api.getTravelReasons).mockImplementation(() => new Promise(r => { resolve = r }))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    const button = screen.getByRole('button', { name: '生成 AI 解释' })
    fireEvent.click(button); fireEvent.click(button)
    expect(api.getTravelReasons).toHaveBeenCalledTimes(1)
    expect(screen.getByText('西湖湿地')).toBeTruthy()
    resolve({ sessionId:42,batchNo:1,provider:'test',cached:false,reasons:[{recommendationId:91,rank:1,reason:'自然偏好与这处风景匹配。'}] })
    expect(await screen.findByText('自然偏好与这处风景匹配。')).toBeTruthy()
  })
  it('旧批次解释成功返回也不能污染换批后的界面', async () => {
    const { getRecommendations } = setup()
    let resolve!: (response: Awaited<ReturnType<typeof api.getTravelReasons>>) => void
    vi.mocked(api.getTravelReasons).mockImplementation(() => new Promise(r => { resolve = r }))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button', { name: '生成 AI 解释' }))
    getRecommendations.mockResolvedValue({
      ...placeResponse(), batchNo: 2,
      places: [{ ...placeResponse().places[0]!, name: '新批次景点', recommendationId: 92 }],
    })
    fireEvent.click(screen.getByRole('button', { name: '换一批' }))
    await screen.findByText('新批次景点')

    resolve({
      sessionId: 42, batchNo: 1, provider: 'test', cached: false,
      reasons: [{ recommendationId: 91, rank: 1, reason: '旧批次的迟到解释' }],
    })
    await waitFor(() => expect(screen.getByRole('button', { name: '生成 AI 解释' }).hasAttribute('disabled')).toBe(false))
    expect(screen.queryByText('旧批次的迟到解释')).toBeNull()
  })
  it('旧批次解释失败不能污染换批后的界面', async () => {
    const { getRecommendations }=setup()
    let reject!: (error: Error) => void
    vi.mocked(api.getTravelReasons).mockImplementation(() => new Promise((_r,j) => { reject=j }))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button', {name:'生成 AI 解释'}))
    getRecommendations.mockResolvedValue({...placeResponse(),batchNo:2,places:[{...placeResponse().places[0]!,name:'新批次景点',recommendationId:92}]})
    fireEvent.click(screen.getByRole('button',{name:'换一批'}))
    await screen.findByText('新批次景点')
    reject(new Error('旧批次模型失败'))
    await new Promise(r=>setTimeout(r,0))
    expect(screen.queryByText('旧批次模型失败')).toBeNull()
  })
  it('首次打开默认不请求付费解释，卡片和推荐仍正常显示', async () => {
    localStorage.removeItem('travelmind.reasonMode')
    setup()
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button', { name: '打开偏好设置' }))
    expect(screen.getByRole('button', { name: '需要时生成' }).className).toContain('chosen')
    expect(api.getTravelReasons).not.toHaveBeenCalled()
  })
  it('重复找点吃的快捷入口真正换卡时记录一次换批，首次进入不虚构事件',async()=>{
    const {recordBehavior}=setup()
    const response={restaurants:['a','b','c','d','e','f'].map(poiId=>({poiId,name:poiId,type:null,typeCode:null,address:null,latitude:33.7,longitude:113.3,
      distanceMeters:100,averageCost:null,openingHoursText:null,telephone:null,imageUrl:null,rating:null,tags:null})),currentPage:1,
      searchSessionId:'search',resultSetId:'page',timestamp:'',status:'SUCCESS',radius:3000,coordinateSystem:'GCJ02',distanceType:'STRAIGHT_LINE'}
    vi.spyOn(api,'searchRestaurants').mockResolvedValue(response)
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    const shortcut=screen.getByRole('button',{name:/找点吃的/})
    fireEvent.click(shortcut); await screen.findByText('a')
    expect(recordBehavior.mock.calls.filter(c=>c[1].action==='NEXT_BATCH_REQUESTED')).toHaveLength(0)
    fireEvent.click(shortcut); await screen.findByText('d')
    await waitFor(()=>expect(recordBehavior.mock.calls.filter(c=>c[1].action==='NEXT_BATCH_REQUESTED')).toHaveLength(1))
    expect(recordBehavior.mock.calls.find(c=>c[1].action==='NEXT_BATCH_REQUESTED')![1]).toMatchObject({resultSetId:'page',visibleSources:[{targetId:'a'},{targetId:'b'},{targetId:'c'}]})
  })
  it('重复明确餐饮文本实际换批也记录事件且不依赖 AI',async()=>{
    const {recordBehavior}=setup()
    const interpret=vi.spyOn(api,'interpret')
    vi.spyOn(api,'searchRestaurants').mockResolvedValue({restaurants:['a','b','c','d','e','f'].map(poiId=>({poiId,name:poiId,type:null,typeCode:null,address:null,latitude:33.7,longitude:113.3,
      distanceMeters:100,averageCost:null,openingHoursText:null,telephone:null,imageUrl:null,rating:null,tags:null})),currentPage:1,
      searchSessionId:'search',resultSetId:'page',timestamp:'',status:'SUCCESS',radius:3000,coordinateSystem:'GCJ02',distanceType:'STRAIGHT_LINE'})
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button',{name:/找点吃的/})); await screen.findByText('a')
    fireEvent.change(screen.getByRole('textbox'),{target:{value:'附近吃什么'}})
    fireEvent.click(screen.getByRole('button',{name:'发送'})); await screen.findByText('d')
    await waitFor(()=>expect(recordBehavior.mock.calls.filter(c=>c[1].action==='NEXT_BATCH_REQUESTED')).toHaveLength(1))
    expect(interpret).not.toHaveBeenCalled()
  })
  it('按钮超时后自然语言更新成功，旧按钮重试不能把较新不喜欢改回想去',async()=>{
    const {createFeedbackEvent}=setup()
    const send=vi.spyOn(api,'giveFeedback').mockRejectedValueOnce(new TypeError('响应丢失'))
      .mockResolvedValue(Object.assign({recommendationId:91,reaction:'DISLIKE' as const,adjustments:[]},
        {feedbackState:{reaction:'DISLIKE' as const,version:2}}))
    createFeedbackEvent.mockResolvedValue(Object.assign({eventId:80,kind:'PLACE_OPINION' as const,reaction:'DISLIKE' as const,
      operations:[],unrecognized:[],summary:'已记录',shouldRecommend:false,excludeSeen:false},
      {feedbackState:{reaction:'DISLIKE' as const,version:2}}))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button',{name:'针对这里说一句'}))
    fireEvent.click(screen.getByRole('button',{name:/想去/}))
    await screen.findByRole('button',{name:'重试保存反馈'})
    fireEvent.change(screen.getByRole('textbox'),{target:{value:'这个地方不喜欢'}})
    fireEvent.click(screen.getByRole('button',{name:'发送'}))
    await waitFor(()=>expect(screen.getByRole('button',{name:/不感兴趣/}).getAttribute('aria-pressed')).toBe('true'))
    fireEvent.click(screen.getByRole('button',{name:'重试保存反馈'}))
    await waitFor(()=>expect(send).toHaveBeenCalledTimes(2))
    await waitFor(()=>expect(screen.queryByRole('button',{name:'重试保存反馈'})).toBeNull())
    expect(screen.getByRole('button',{name:/不感兴趣/}).getAttribute('aria-pressed')).toBe('true')
    expect(send.mock.calls[0]![4]).toEqual(send.mock.calls[1]![4])
  })
  it('旧按钮应答最后到达时不能覆盖较新的服务端反馈版本',async()=>{
    const {createFeedbackEvent}=setup()
    let resolve!: (value: Awaited<ReturnType<typeof api.giveFeedback>>)=>void
    vi.spyOn(api,'giveFeedback').mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
    createFeedbackEvent.mockResolvedValue(Object.assign({eventId:81,kind:'PLACE_OPINION' as const,reaction:'DISLIKE' as const,
      operations:[],unrecognized:[],summary:'已记录',shouldRecommend:false,excludeSeen:false},
      {feedbackState:{reaction:'DISLIKE' as const,version:2}}))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button',{name:'针对这里说一句'}))
    fireEvent.click(screen.getByRole('button',{name:/想去/}))
    fireEvent.change(screen.getByRole('textbox'),{target:{value:'这里不喜欢'}})
    fireEvent.click(screen.getByRole('button',{name:'发送'}))
    await waitFor(()=>expect(screen.getByRole('button',{name:/不感兴趣/}).getAttribute('aria-pressed')).toBe('true'))
    resolve({recommendationId:91,reaction:'LIKE',adjustments:[],feedbackState:{reaction:'LIKE',version:1}})
    await waitFor(()=>expect((screen.getByRole('button',{name:/想去/}) as HTMLButtonElement).disabled).toBe(false))
    expect(screen.getByRole('button',{name:/不感兴趣/}).getAttribute('aria-pressed')).toBe('true')
  })
  it('餐饮业务失败后的重试不是新的换批事件',async()=>{
    const {recordBehavior}=setup()
    const response={restaurants:['a','b','c','d'].map(poiId=>({poiId,name:poiId,type:null,typeCode:null,address:null,latitude:33.7,longitude:113.3,
      distanceMeters:100,averageCost:null,openingHoursText:null,telephone:null,imageUrl:null,rating:null,tags:null})),currentPage:1,
      searchSessionId:'search',resultSetId:'page',timestamp:'',status:'SUCCESS',radius:3000,coordinateSystem:'GCJ02',distanceType:'STRAIGHT_LINE'}
    vi.spyOn(api,'searchRestaurants').mockResolvedValueOnce(response).mockRejectedValueOnce(new TypeError('网络断开'))
      .mockResolvedValue({...response,restaurants:[],currentPage:2,resultSetId:'page2'})
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地'); fireEvent.click(screen.getByRole('button',{name:/找点吃的/})); await screen.findByText('a')
    fireEvent.click(screen.getByRole('button',{name:'换一批餐饮'})); fireEvent.click(await screen.findByRole('button',{name:'重试餐饮搜索'}))
    await screen.findByText('d')
    expect(recordBehavior.mock.calls.filter(c=>c[1].action==='NEXT_BATCH_REQUESTED')).toHaveLength(1)
  })
  it('导航和换批只记明确事件，快速忙碌换批不重复记', async () => {
    const {recordBehavior,getRecommendations}=setup()
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    const nav=await screen.findByRole('link',{name:/导航过去/}); fireEvent.click(nav)
    await waitFor(()=>expect(recordBehavior).toHaveBeenCalledWith(expect.objectContaining({sessionId:42}),expect.objectContaining({action:'NAVIGATION_REQUESTED',targetId:'7',recommendationId:91}),expect.anything()))
    getRecommendations.mockReturnValue(new Promise(()=>{}))
    const button=screen.getByRole('button',{name:'换一批'}); fireEvent.click(button); fireEvent.click(button)
    await waitFor(()=>expect(recordBehavior.mock.calls.filter(c=>c[1].action==='NEXT_BATCH_REQUESTED')).toHaveLength(1))
    expect(recordBehavior.mock.calls.every(c=>!('latitude' in c[1]))).toBe(true)
  })
  it('同地点反馈在途时阻止双击，失败重试必须复用事件头', async () => {
    setup(); let reject!: (reason: unknown)=>void
    const send=vi.spyOn(api,'giveFeedback').mockImplementationOnce(()=>new Promise((_r,j)=>{reject=j}))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    const like=await screen.findByRole('button',{name:/想去/}); fireEvent.click(like); fireEvent.click(like)
    expect(send).toHaveBeenCalledOnce()
    reject(new TypeError('网络断开'))
    fireEvent.click(await screen.findByRole('button',{name:'重试保存反馈'}))
    await waitFor(()=>expect(send).toHaveBeenCalledTimes(2))
    expect(send.mock.calls[1]![4]).toEqual(send.mock.calls[0]![4])
  })
  it('餐饮排除和导航保留逐卡来源且不调用景点反馈',async()=>{
    const {recordBehavior}=setup()
    vi.spyOn(api,'searchRestaurants').mockResolvedValue({restaurants:[{poiId:'POI',name:'测试餐饮',type:null,typeCode:null,address:null,
      latitude:33.7,longitude:113.3,distanceMeters:100,averageCost:null,openingHoursText:null,telephone:null,imageUrl:null,rating:null,tags:null}],
      currentPage:1,searchSessionId:'search',resultSetId:'page',timestamp:'',status:'SUCCESS',radius:3000,coordinateSystem:'GCJ02',distanceType:'STRAIGHT_LINE'})
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地'); fireEvent.click(screen.getByRole('button',{name:/找点吃的/}))
    await screen.findByText('测试餐饮'); fireEvent.click(screen.getByRole('link',{name:'导航过去'}))
    const dismiss=screen.getByRole('button',{name:'本次不再推荐'}); fireEvent.click(dismiss); fireEvent.click(dismiss)
    await waitFor(()=>expect(recordBehavior.mock.calls.filter(c=>c[1].action==='EXCLUDE')).toHaveLength(1))
    expect(recordBehavior.mock.calls.filter(c=>c[1].provider==='AMAP').map(c=>c[1].resultSetId)).toEqual(['page','page'])
    expect(api.giveFeedback).not.toHaveBeenCalled(); expect(api.removeFeedback).not.toHaveBeenCalled()
  })
  it('餐饮输入框不携带景点回复目标', async () => {
    setup()
    vi.spyOn(api, 'searchRestaurants').mockResolvedValue({ restaurants: [], currentPage: 1, searchSessionId: 'food', resultSetId: 'page-food', timestamp: '', status: 'NO_RESULTS', radius: 3000, coordinateSystem: 'GCJ02', distanceType: 'STRAIGHT_LINE' })
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '针对这里说一句' }))
    fireEvent.click(screen.getByRole('button', { name: /找点吃的/ }))
    expect(screen.queryByRole('button', { name: '取消回复地点' })).toBeNull()
  })
  it('餐饮模式调整距离和重新定位只刷新餐饮，不创建景点批次', async () => {
    const { getRecommendations } = setup()
    const restaurants = vi.spyOn(api, 'searchRestaurants').mockResolvedValue({ restaurants: [], currentPage: 1, searchSessionId: 'food', resultSetId: 'page-food', timestamp: '', status: 'NO_RESULTS', radius: 3000, coordinateSystem: 'GCJ02', distanceType: 'STRAIGHT_LINE' })
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    const originalCalls = getRecommendations.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: /找点吃的/ }))
    await screen.findByText('附近暂无符合距离条件的餐饮。')
    fireEvent.click(screen.getByRole('button', { name: '打开偏好设置' }))
    fireEvent.change(screen.getByLabelText('最远公里'), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: '应用位置与限制' }))
    await waitFor(() => expect(restaurants).toHaveBeenLastCalledWith(expect.objectContaining({ radius: 2000 }), 1))
    expect(getRecommendations).toHaveBeenCalledTimes(originalCalls)
    fireEvent.click(screen.getByRole('button', { name: '打开偏好设置' }))
    fireEvent.click(screen.getByRole('button', { name: '使用我的当前位置' }))
    await waitFor(() => expect(getRecommendations).toHaveBeenCalledTimes(originalCalls))
  })
  it('切回景点后迟到的餐饮响应不能覆盖当前画面', async () => {
    setup()
    let resolve!: (value: Awaited<ReturnType<typeof api.searchRestaurants>>) => void
    vi.spyOn(api,'searchRestaurants').mockReturnValue(new Promise(r=>{resolve=r}))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button',{name:/找点吃的/}))
    await screen.findByText('正在寻找附近餐饮…')
    fireEvent.click(screen.getByRole('button',{name:/附近逛逛/}))
    resolve({restaurants:[],currentPage:1,searchSessionId:'old',resultSetId:'old-page',timestamp:'',status:'NO_RESULTS',radius:3000,coordinateSystem:'GCJ02',distanceType:'STRAIGHT_LINE'})
    await waitFor(()=>expect(screen.queryByRole('region',{name:'附近餐饮'})).toBeNull())
    expect(screen.queryByText('附近暂无符合距离条件的餐饮。')).toBeNull()
  })
  it('找点吃的独立搜索，不依赖AI或触发景点推荐', async () => {
    const { getRecommendations, createFeedbackEvent } = setup()
    const interpret=vi.spyOn(api,'interpret')
    const restaurants=vi.spyOn(api,'searchRestaurants').mockResolvedValue({ restaurants:[],currentPage:1,searchSessionId:'food',resultSetId:'page-food',timestamp:'',status:'NO_RESULTS',radius:3000,coordinateSystem:'GCJ02',distanceType:'STRAIGHT_LINE' })
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    const originalCalls=getRecommendations.mock.calls.length
    fireEvent.click(screen.getByRole('button',{name:/找点吃的/}))
    await waitFor(()=>expect(restaurants).toHaveBeenCalledWith(expect.objectContaining({sessionId:42,coordinateSystem:'WGS84',sessionToken:'token'}),1))
    expect(await screen.findByText('附近暂无符合距离条件的餐饮。')).toBeTruthy()
    expect(screen.getByText('3 公里')).toBeTruthy()
    expect(getRecommendations).toHaveBeenCalledTimes(originalCalls)
    expect(interpret).not.toHaveBeenCalled(); expect(createFeedbackEvent).not.toHaveBeenCalled()
  })
  beforeEach(() => {
    localStorage.clear()
    Object.defineProperty(window, 'isSecureContext', {
      configurable: true,
      value: true,
    })
  })

  it('HTTPS 环境请求高精度 GPS，并用真实坐标发起推荐', async () => {
    const { getRecommendations } = setup()
    const getCurrentPosition = vi.fn((success: PositionCallback) => success({
      coords: { latitude: 30.25123, longitude: 120.16123 },
    } as GeolocationPosition))
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition },
    })

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    await waitFor(() => expect(getRecommendations).toHaveBeenCalledWith(
      42,
      expect.objectContaining({ latitude: 30.25123, longitude: 120.16123 }),
      'token',
    ))
    expect(getCurrentPosition).toHaveBeenCalledWith(
      expect.any(Function),
      expect.any(Function),
      expect.objectContaining({ enableHighAccuracy: true, maximumAge: 60_000 }),
    )
  })

  it('HTTP 局域网环境解释浏览器限制且不请求 GPS', async () => {
    setup()
    Object.defineProperty(window, 'isSecureContext', {
      configurable: true,
      value: false,
    })
    const getCurrentPosition = vi.fn()
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition },
    })

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    expect(await screen.findByText(/手机浏览器只允许网页在 HTTPS 环境读取 GPS/)).toBeTruthy()
    expect(getCurrentPosition).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '打开位置设置' })).toBeTruthy()
  })

  it('用户拒绝定位权限时说明原因并提供位置设置入口', async () => {
    setup()
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (_success: PositionCallback, error: PositionErrorCallback) => error({
          code: 1,
          message: 'denied',
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3,
        }),
      },
    })

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    expect(await screen.findByText(/没有获得定位权限/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '打开位置设置' })).toBeTruthy()
  })

  it('定位超时时允许重新定位或改用测试位置', async () => {
    setup()
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (_success: PositionCallback, error: PositionErrorCallback) => error({
          code: 3,
          message: 'timeout',
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3,
        }),
      },
    })

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    expect(await screen.findByText(/定位请求超时/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '重新定位' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '杭州西湖测试位置' })).toBeTruthy()
  })

  it('GPS 成功但没有地名时显示可核对的坐标', async () => {
    const response = { ...placeResponse(), locationLabel: null }
    setup(response)
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (success: PositionCallback) => success({
          coords: { latitude: 30.25123, longitude: 120.16123 },
        } as GeolocationPosition),
      },
    })

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    expect(await screen.findByText('当前位置 · 30.2512, 120.1612')).toBeTruthy()
  })

  it('杭州西湖测试位置可从位置设置直接使用', async () => {
    const response = { ...placeResponse(), locationLabel: null }
    const { getRecommendations } = setup(response)

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button', { name: '打开偏好设置' }))
    fireEvent.click(screen.getByRole('button', { name: '杭州西湖测试位置' }))

    await waitFor(() => expect(getRecommendations).toHaveBeenLastCalledWith(
      42,
      expect.objectContaining({ latitude: 30.242, longitude: 120.14 }),
      'token',
    ))
    expect(await screen.findByText('杭州西湖')).toBeTruthy()
  })

  it('平顶山市中心测试位置可从位置设置直接使用', async () => {
    const response = { ...placeResponse(), locationLabel: null }
    const { getRecommendations } = setup(response)

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button', { name: '打开偏好设置' }))
    fireEvent.click(screen.getByRole('button', { name: '平顶山市中心测试位置' }))

    await waitFor(() => expect(getRecommendations).toHaveBeenLastCalledWith(
      42,
      expect.objectContaining({ latitude: 33.735, longitude: 113.3077 }),
      'token',
    ))
    expect(await screen.findByText('平顶山市中心')).toBeTruthy()
  })

  it('无附近结果时说明当前覆盖杭州和平顶山且不会突破距离限制', async () => {
    setup({ ...placeResponse(), places: [] })

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    expect(await screen.findByText(/当前地点库覆盖杭州和平顶山/)).toBeTruthy()
    expect(screen.getByText(/不会为了凑满 Top 3 而突破/)).toBeTruthy()
  })

  it('手动经纬度会更新位置标识并按输入坐标推荐', async () => {
    const response = { ...placeResponse(), locationLabel: null }
    const { getRecommendations } = setup(response)

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button', { name: '打开偏好设置' }))
    fireEvent.change(screen.getByLabelText('纬度'), { target: { value: '30.2741' } })
    fireEvent.change(screen.getByLabelText('经度'), { target: { value: '120.1551' } })
    fireEvent.click(screen.getByRole('button', { name: '应用位置与限制' }))

    await waitFor(() => expect(getRecommendations).toHaveBeenLastCalledWith(
      42,
      expect.objectContaining({ latitude: 30.2741, longitude: 120.1551 }),
      'token',
    ))
    expect(await screen.findByText('手动位置 · 30.2741, 120.1551')).toBeTruthy()
  })

  it('再次点击已选中的不感兴趣会取消反馈', async () => {
    setup()
    const giveFeedback = vi.mocked(api.giveFeedback)
    const removeFeedback = vi.mocked(api.removeFeedback)

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    const dislike = await screen.findByRole('button', { name: '👎 不感兴趣' })

    fireEvent.click(dislike)
    await waitFor(() => expect(dislike.getAttribute('aria-pressed')).toBe('true'))
    fireEvent.click(dislike)

    await waitFor(() => expect(dislike.getAttribute('aria-pressed')).toBe('false'))
    expect(giveFeedback).toHaveBeenCalledTimes(1)
    expect(removeFeedback).toHaveBeenCalledWith(42, 91, 'token', expect.objectContaining({eventId:expect.any(String),occurredAt:expect.any(String)}))
  })

  it('普通推荐失败后保留同一次请求并允许原地重试', async () => {
    const { recommendation, getRecommendations } = setup()
    getRecommendations
      .mockRejectedValueOnce(new Error('服务暂时不可用'))
      .mockResolvedValueOnce(recommendation)

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    expect(await screen.findByText('服务暂时不可用')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试推荐' }))

    await waitFor(() => expect(getRecommendations).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('heading', { name: '西湖湿地' })).toBeTruthy()
  })

  it('点击卡片后发送 feedback API，并把 excludeSeen 传给推荐刷新', async () => {
    const { getRecommendations, createFeedbackEvent } = setup()
    createFeedbackEvent.mockResolvedValue({
      eventId: 55,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [],
      unrecognized: [],
      summary: '换一批看看',
      shouldRecommend: true,
      excludeSeen: true,
    })
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    const replyButton = await screen.findByRole('button', { name: '针对这里说一句' })
    fireEvent.click(replyButton)
    expect(screen.getByRole('status').textContent).toContain('西湖湿地')
    const input = screen.getByRole('textbox', { name: '继续告诉 TravelMind 你的要求' })
    fireEvent.change(input, { target: { value: '换一个吧' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => expect(createFeedbackEvent).toHaveBeenCalledWith(
      42,
      expect.objectContaining({ text: '换一个吧', recommendationId: 91 }),
      'token',
    ))
    await waitFor(() => expect(getRecommendations).toHaveBeenCalledWith(
      42,
      expect.objectContaining({ excludeSeen: true }),
      'token',
    ))
  })

  it('反馈失败时保留原文和回复目标，并允许重试', async () => {
    const { createFeedbackEvent } = setup()
    createFeedbackEvent
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({
        eventId: 56,
        kind: 'PLACE_OPINION',
        reaction: 'LIKE',
        operations: [],
        unrecognized: [],
        summary: '已记下',
        shouldRecommend: false,
        excludeSeen: false,
      })
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '针对这里说一句' }))
    const input = screen.getByRole('textbox', { name: '继续告诉 TravelMind 你的要求' })
    fireEvent.change(input, { target: { value: '这里太吵了' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    expect((await screen.findByText(/原话和回复地点都还在/)).textContent).toContain('原话和回复地点都还在')
    expect((input as HTMLTextAreaElement).value).toBe('这里太吵了')
    expect(screen.getByRole('status').textContent).toContain('西湖湿地')
    fireEvent.click(screen.getByRole('button', { name: '重试这条反馈' }))
    await waitFor(() => expect(createFeedbackEvent).toHaveBeenCalledTimes(2))
  })

  it('反馈确认先进入聊天流，推荐刷新失败时只重试推荐且不重复提交反馈', async () => {
    const { getRecommendations, createFeedbackEvent } = setup()
    getRecommendations
      .mockResolvedValueOnce(placeResponse())
      .mockRejectedValueOnce(new Error('refresh failed'))
    createFeedbackEvent.mockResolvedValue({
      eventId: 57,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [],
      unrecognized: [],
      summary: '我把范围收近一些',
      shouldRecommend: true,
      excludeSeen: false,
    })
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '针对这里说一句' }))
    const input = screen.getByRole('textbox', { name: '继续告诉 TravelMind 你的要求' })
    fireEvent.change(input, { target: { value: '这家太远了' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    expect((await screen.findByText('我把范围收近一些')).textContent).toContain('我把范围收近一些')
    const retry = await screen.findByRole('button', { name: '仅重试推荐' })
    expect(retry).toBeTruthy()
    fireEvent.click(retry)
    await waitFor(() => expect(createFeedbackEvent).toHaveBeenCalledTimes(1))
  })

  it('取消回复后，晚到的反馈响应不会覆盖当前输入上下文', async () => {
    const { createFeedbackEvent } = setup()
    let resolveFeedback!: (value: Awaited<ReturnType<typeof api.createFeedbackEvent>>) => void
    createFeedbackEvent.mockImplementation(() => new Promise((resolve) => { resolveFeedback = resolve }))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '针对这里说一句' }))
    const input = screen.getByRole('textbox', { name: '继续告诉 TravelMind 你的要求' })
    fireEvent.change(input, { target: { value: '这家太远了' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(createFeedbackEvent).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: '取消回复地点' }))
    fireEvent.change(input, { target: { value: '我想找安静的地方' } })
    resolveFeedback({
      eventId: 58,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [{ op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 2000 }],
      unrecognized: [],
      summary: '旧反馈',
      shouldRecommend: true,
      excludeSeen: false,
    })
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect((input as HTMLTextAreaElement).value).toBe('我想找安静的地方')
    expect(screen.queryByText('旧反馈')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('反馈进行中修改定位后，晚到响应不会按旧位置刷新推荐', async () => {
    const { createFeedbackEvent, getRecommendations } = setup()
    let resolveFeedback!: (value: Awaited<ReturnType<typeof api.createFeedbackEvent>>) => void
    createFeedbackEvent.mockImplementation(() => new Promise((resolve) => { resolveFeedback = resolve }))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '针对这里说一句' }))
    const input = screen.getByRole('textbox', { name: '继续告诉 TravelMind 你的要求' })
    fireEvent.change(input, { target: { value: '这家太远了' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(createFeedbackEvent).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: '打开偏好设置' }))
    fireEvent.change(screen.getByLabelText('纬度'), { target: { value: '31.0000' } })
    const callsBeforeLateResponse = getRecommendations.mock.calls.length
    resolveFeedback({
      eventId: 59,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [],
      unrecognized: [],
      summary: '旧位置反馈',
      shouldRecommend: true,
      excludeSeen: false,
    })
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(screen.queryByText('旧位置反馈')).toBeNull()
    expect(getRecommendations.mock.calls.length).toBe(callsBeforeLateResponse)
  })

  it('反馈已确认但推荐刷新仍在途中时，修改定位会丢弃旧位置推荐', async () => {
    const { createFeedbackEvent, getRecommendations } = setup()
    let resolveRefresh!: (value: RecommendationResponse) => void
    getRecommendations
      .mockResolvedValueOnce(placeResponse())
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRefresh = resolve }))
    createFeedbackEvent.mockResolvedValue({
      eventId: 60,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [],
      unrecognized: [],
      summary: '反馈已确认',
      shouldRecommend: true,
      excludeSeen: false,
    })
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '针对这里说一句' }))
    const input = screen.getByRole('textbox', { name: '继续告诉 TravelMind 你的要求' })
    fireEvent.change(input, { target: { value: '这家太远了' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText('反馈已确认')
    await waitFor(() => expect(getRecommendations).toHaveBeenCalledTimes(2))

    fireEvent.click(screen.getByRole('button', { name: '打开偏好设置' }))
    fireEvent.change(screen.getByLabelText('纬度'), { target: { value: '31.0000' } })
    resolveRefresh({
      ...placeResponse(),
      batchNo: 2,
      places: [{ ...placeResponse().places[0]!, recommendationId: 92, name: '旧位置推荐' }],
    })
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(screen.queryByText('旧位置推荐')).toBeNull()
  })

  it('以欢迎区、真实出行条件和 Top 推荐组织对话主页', async () => {
    setup()

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    expect(screen.getByRole('heading', { name: '现在想去哪里？' })).toBeTruthy()
    expect(screen.getByRole('region', { name: '快捷旅行需求' })).toBeTruthy()
    expect(await screen.findByText('本次出行')).toBeTruthy()
    expect(screen.getByText('西湖附近')).toBeTruthy()
    expect(screen.getByText('4 小时')).toBeTruthy()
    expect(screen.getByText('预算未设置')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Top 1 推荐' })).toBeTruthy()
    expect(screen.getByText('全天开放')).toBeTruthy()
  })

  it('营业时间按接口契约区分全天开放、完整时段和不完整数据', async () => {
    setup(placeResponse('09:00', '17:00'))
    const view = render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    expect(await screen.findByText('09:00 – 17:00')).toBeTruthy()

    view.unmount()
    vi.restoreAllMocks()
    setup(placeResponse('09:00', null))
    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)
    expect(await screen.findByText('营业时间未知')).toBeTruthy()
  })

  it('欢迎区快捷需求继续通过现有 reducer 叠加条件并刷新推荐', async () => {
    const { getRecommendations } = setup()

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    await screen.findByText('西湖湿地')
    fireEvent.click(screen.getByRole('button', { name: '安静一点' }))

    await waitFor(() => expect(getRecommendations).toHaveBeenLastCalledWith(
      42,
      expect.objectContaining({ states: ['QUIET'] }),
      'token',
    ))
  })

  it('换一批继续使用现有 excludeSeen 推荐请求', async () => {
    const { getRecommendations } = setup()

    render(<TravelResultScreen profile={profile} sessionToken="token" onRestart={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: '换一批' }))

    await waitFor(() => expect(getRecommendations).toHaveBeenLastCalledWith(
      42,
      expect.objectContaining({ excludeSeen: true }),
      'token',
    ))
  })
})
