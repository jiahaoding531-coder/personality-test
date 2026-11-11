import { afterEach, describe, expect, it, vi } from 'vitest'
import { createBehaviorClient, createBehaviorMetadata } from './behaviorEvents'
import type { BehaviorDraft, BehaviorEnvelope, BehaviorScope } from './behaviorTypes'

const scope: BehaviorScope = { userId: 1, sessionId: 42, sessionToken: 'test', generation: 1 }
const draft: BehaviorDraft = { targetType: 'RESTAURANT', provider: 'AMAP', targetId: 'POI',
  action: 'NAVIGATION_REQUESTED', searchSessionId: 'search', resultSetId: 'page' }
const receipt = (event: BehaviorEnvelope) => ({ eventId: event.eventId, duplicate: false })
afterEach(() => vi.useRealTimers())
describe('有界行为发送', () => {
  it('排队新动作不能绕过失败动作的退避等待', async () => {
    vi.useFakeTimers()
    const send=vi.fn().mockRejectedValueOnce(new TypeError('网络失败')).mockImplementation(async (_s,e)=>receipt(e))
    const client=createBehaviorClient(send,scope)
    client.record(draft); await vi.advanceTimersByTimeAsync(0)
    client.record({...draft,action:'EXCLUDE'})
    await vi.advanceTimersByTimeAsync(999)
    expect(send).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(send).toHaveBeenCalledTimes(3)
    expect(send.mock.calls[1]![1].eventId).toBe(send.mock.calls[0]![1].eventId)
    client.dispose()
  })
  it('网络重试复用同一身份且最多额外三次', async () => {
    vi.useFakeTimers(); const send = vi.fn().mockRejectedValue(new TypeError('网络失败'))
    const client = createBehaviorClient(send,scope); expect(client.record(draft)).toBeUndefined()
    await vi.runAllTimersAsync(); expect(send).toHaveBeenCalledTimes(4)
    expect(new Set(send.mock.calls.map(c=>c[1].eventId)).size).toBe(1)
    expect(new Set(send.mock.calls.map(c=>c[1].occurredAt)).size).toBe(1)
    client.dispose()
  })
  it('永久错误停止重试临时5xx可重试', async () => {
    vi.useFakeTimers()
    for(const status of [400,401,403,404,409,410,422]) {
      const send=vi.fn().mockRejectedValue({status}); const client=createBehaviorClient(send,scope)
      client.record(draft); await vi.runAllTimersAsync(); expect(send).toHaveBeenCalledOnce(); client.dispose()
    }
    const send=vi.fn().mockRejectedValueOnce({status:503}).mockImplementation(async (_s,e)=>receipt(e))
    const client=createBehaviorClient(send,scope); client.record(draft); await vi.runAllTimersAsync()
    expect(send).toHaveBeenCalledTimes(2); client.dispose()
  })
  it('包括在途最多100条且每次真实动作身份独立', async () => {
    vi.useFakeTimers(); const send=vi.fn(async (_s,e)=>receipt(e)); const client=createBehaviorClient(send,scope)
    for(let i=0;i<150;i++) client.record(draft)
    await vi.runAllTimersAsync(); expect(send).toHaveBeenCalledTimes(100)
    expect(new Set(send.mock.calls.map(c=>c[1].eventId)).size).toBe(100); client.dispose()
  })
  it('每次请求十秒超时且排队超过120秒不发送', async () => {
    vi.useFakeTimers(); const send=vi.fn((_s:BehaviorScope,_e:BehaviorEnvelope)=>new Promise<never>(()=>{})); const client=createBehaviorClient(send,scope)
    for(let i=0;i<10;i++) client.record(draft)
    await vi.advanceTimersByTimeAsync(150000)
    expect(send.mock.calls.length).toBeLessThanOrEqual(12)
    expect(send.mock.calls.every(c=>new Date(c[1].occurredAt).getTime()===Date.now()-150000)).toBe(true)
    const count=send.mock.calls.length; await vi.runAllTimersAsync(); expect(send).toHaveBeenCalledTimes(count); client.dispose()
  })
  it('切换账号销毁清理定时器并中止旧请求', async () => {
    vi.useFakeTimers(); let signal: AbortSignal | undefined
    const send=vi.fn((_s,_e,s?:AbortSignal)=>{signal=s; return new Promise<never>(()=>{})})
    const client=createBehaviorClient(send,scope); client.record(draft); client.record(draft)
    client.dispose(); await vi.runAllTimersAsync(); expect(send).toHaveBeenCalledOnce(); expect(signal?.aborted).toBe(true)
    client.record(draft); expect(send).toHaveBeenCalledOnce()
    const next=vi.fn(async (_s,e)=>receipt(e)); const newClient=createBehaviorClient(next,{...scope,userId:2,generation:2})
    newClient.record(draft); await vi.runAllTimersAsync(); expect(next.mock.calls[0]![0].userId).toBe(2); newClient.dispose()
  })
  it('反馈元数据每个命令独立，不包含GPS或用户身份', () => {
    const first=createBehaviorMetadata(); const second=createBehaviorMetadata()
    expect(first.eventId).not.toBe(second.eventId); expect(Object.keys(first).sort()).toEqual(['eventId','occurredAt'])
  })
})
