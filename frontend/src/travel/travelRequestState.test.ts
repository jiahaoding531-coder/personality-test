import { describe, expect, it } from 'vitest'

import {
  chooseAiRetryTarget,
  createLatestRequestGate,
  createSingleFlightGate,
} from './travelRequestState'

describe('chooseAiRetryTarget', () => {
  it('保存 Key 后优先重试刚失败的自然语言，而不是推荐理由', () => {
    expect(chooseAiRetryTarget('noServerAi', true)).toBe('naturalLanguage')
    expect(chooseAiRetryTarget('keyRejected', true)).toBe('naturalLanguage')
  })

  it('没有自然语言失败时才重试已有推荐的理由', () => {
    expect(chooseAiRetryTarget('idle', true)).toBe('reasons')
    expect(chooseAiRetryTarget('idle', false)).toBeNull()
  })
})

describe('createLatestRequestGate', () => {
  it('只把最后发起的推荐请求视为当前请求', () => {
    const gate = createLatestRequestGate()
    const first = gate.next()
    const second = gate.next()

    expect(gate.isCurrent(first)).toBe(false)
    expect(gate.isCurrent(second)).toBe(true)
  })
})

describe('createSingleFlightGate', () => {
  it('执行中只保留最后一个待处理请求，结束后允许新一轮', () => {
    const gate = createSingleFlightGate<string>()

    expect(gate.tryStart('第一条')).toBe(true)
    expect(gate.tryStart('第二条')).toBe(false)
    expect(gate.tryStart('最后一条')).toBe(false)
    expect(gate.takeLatest()).toBe('最后一条')
    expect(gate.takeLatest()).toBeNull()
    gate.finish()
    expect(gate.tryStart('新一轮')).toBe(true)
  })
})
