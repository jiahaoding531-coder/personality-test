import { afterEach, describe, expect, it, vi } from 'vitest'

import { api, ApiError } from './api'
import type { TravelQueryState } from './travel/travelQueryState'

describe('餐饮来源请求', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('地名搜索使用同源受保护接口、CSRF和会话令牌，但不携带 AI Key',async()=>{
    vi.stubGlobal('document',{cookie:'XSRF-TOKEN=csrf'})
    vi.stubGlobal('localStorage',{getItem:()=> '不应发送的测试模型密钥'})
    const fetch=vi.fn().mockResolvedValue(new Response('{"candidates":[]}'))
    vi.stubGlobal('fetch',fetch)
    await api.searchLocations({sessionId:42,sessionToken:'session'},'杭州西湖')
    const [url,init]=fetch.mock.calls[0]!
    expect(url).toBe('/api/travel/sessions/42/locations/search')
    expect(JSON.parse(init.body)).toEqual({keyword:'杭州西湖'})
    const headers=new Headers(init.headers)
    expect(headers.get('X-Session-Token')).toBe('session')
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf')
    expect(headers.has('X-AI-Key')).toBe(false)
    expect(init.credentials).toBe('include')
  })
  it('请求逐页携带稳定搜索ID', async () => {
    vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=test' })
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    await api.searchRestaurants({ sessionId: 42, latitude: 33.7, longitude: 113.3,
      coordinateSystem: 'WGS84', radius: 3000, searchSessionId: 'stable' }, 2)
    expect(JSON.parse(String(fetch.mock.calls[0]![1].body))).toMatchObject({ page: 2, searchSessionId: 'stable' })
  })
  it('行为请求保持CSRF会话认证且不携带用户ID或坐标', async () => {
    vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=test' })
    const fetch=vi.fn().mockResolvedValue(new Response('{"duplicate":false}',{status:201})); vi.stubGlobal('fetch',fetch)
    await api.recordBehavior({userId:1,sessionId:42,sessionToken:'session',generation:1},{eventId:'id',occurredAt:'time',schemaVersion:1,
      targetType:'RESTAURANT',provider:'AMAP',targetId:'POI',action:'NAVIGATION_REQUESTED',searchSessionId:'search',resultSetId:'page'})
    const [path,init]=fetch.mock.calls[0]!; expect(path).toBe('/api/travel/sessions/42/behavior-events')
    expect(init.keepalive).toBe(true); expect(new Headers(init.headers).get('X-Session-Token')).toBe('session')
    expect(new Headers(init.headers).get('X-XSRF-TOKEN')).toBe('test')
    expect(JSON.parse(String(init.body))).not.toHaveProperty('userId')
  })
  it('明确反馈只通过业务请求携带身份头', async () => {
    vi.stubGlobal('document', {cookie:''}); const fetch=vi.fn().mockResolvedValue(new Response('{}')); vi.stubGlobal('fetch',fetch)
    await api.giveFeedback(42,1,'LIKE','session',{eventId:'id',occurredAt:'time'})
    const headers=new Headers(fetch.mock.calls[0]![1].headers)
    expect(headers.get('X-Behavior-Event-Id')).toBe('id'); expect(headers.get('X-Behavior-Occurred-At')).toBe('time')
    expect(fetch).toHaveBeenCalledOnce()
  })
})

describe('api.getTravelReasons', () => {
  afterEach(() => vi.unstubAllGlobals())

  it.each([
    { batchNo: undefined, path: '/api/travel/sessions/42/recommendations/reasons?regenerate=false' },
    { batchNo: 7, path: '/api/travel/sessions/42/recommendations/reasons?regenerate=false&batchNo=7' },
  ])('解释请求携带指定批次且兼容省略批次：$batchNo', async ({ batchNo, path }) => {
    vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=csrf-token' })
    vi.stubGlobal('localStorage', { getItem: () => null })
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      sessionId: 42, batchNo: batchNo ?? 1, provider: 'test', cached: false, reasons: [],
    })))
    vi.stubGlobal('fetch', fetch)

    await api.getTravelReasons(42, false, 'session-token', batchNo)

    expect(fetch).toHaveBeenCalledOnce()
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(path)
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('include')
    const headers = new Headers(init.headers)
    expect(headers.get('X-Session-Token')).toBe('session-token')
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-token')
  })
})

describe('api.createFeedbackEvent', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('发送反馈路径、会话/BYOK/CSRF 请求头和后端快照 query', async () => {
    const storage = new Map([
      ['travelmind.aiProvider', 'DEEPSEEK'],
      ['travelmind.aiKey', 'test-key'],
    ])
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
    })
    vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=csrf-token' })
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      eventId: 55,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [],
      unrecognized: [],
      summary: '已记录',
      shouldRecommend: false,
      excludeSeen: false,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const query: TravelQueryState = {
      intents: ['HUNGRY'],
      preferences: ['QUIET'],
      constraints: { durationMinutes: 60, maxDistanceMeters: 5000, budgetMax: 100 },
      biases: { NATURE: 0.5 },
    }

    await api.createFeedbackEvent(42, {
      text: '这家太远了',
      recommendationId: 37,
      query,
      latitude: 30.25,
      longitude: 120.16,
    }, 'session-token')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/travel/sessions/42/feedback-events')
    const headers = new Headers(init.headers)
    expect(headers.get('X-Session-Token')).toBe('session-token')
    expect(headers.get('X-AI-Provider')).toBe('DEEPSEEK')
    expect(headers.get('X-AI-Key')).toBe('test-key')
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-token')
    expect(JSON.parse(String(init.body))).toEqual({
      text: '这家太远了',
      recommendationId: 37,
      latitude: 30.25,
      longitude: 120.16,
      query: {
        intents: ['HUNGRY'],
        preferences: ['QUIET'],
        durationMinutes: 60,
        maxDistanceMeters: 5000,
        budgetMax: 100,
        biases: { NATURE: 0.5 },
      },
    })
  })
})

describe('统一错误协议', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it.each(['设置','撤销'])('行为反馈%s请求超时后可用原事件身份重试', async action => {
    vi.useFakeTimers(); vi.stubGlobal('document',{cookie:''})
    const signals: AbortSignal[]=[]
    vi.stubGlobal('fetch',vi.fn((_url,init:RequestInit)=>new Promise<Response>((_resolve,reject)=>{
      if(init.signal) { signals.push(init.signal); init.signal.addEventListener('abort',()=>reject(new TypeError('超时'))) }
    })))
    const metadata={eventId:'11111111-1111-4111-8111-111111111111',occurredAt:'2026-10-09T00:00:00Z'}
    const result=(action==='设置'?api.giveFeedback(42,91,'LIKE','token',metadata):api.removeFeedback(42,91,'token',metadata))
      .catch((error:unknown)=>error)
    await vi.advanceTimersByTimeAsync(10000)
    expect(signals[0]?.aborted).toBe(true)
    expect(await result).toMatchObject({status:0})
  })

  it('保留后端机器错误码，供首次使用分流做精确判断', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      timestamp: '2026-09-19T00:00:00Z',
      status: 404,
      error: 'Not Found',
      code: 'TRAVEL_PROFILE_REQUIRED',
      message: '尚未建立旅行画像',
      path: '/api/travel/sessions/42/profile',
      fieldErrors: [],
    }), { status: 404, headers: { 'Content-Type': 'application/json' } })))

    const error = await api.getTravelProfile(42).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('TRAVEL_PROFILE_REQUIRED')
  })
})
