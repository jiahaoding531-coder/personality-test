import { describe, expect, it } from 'vitest'

import {
  createEmptyTravelQueryState,
  createTravelQueryChips,
  describeTravelQueryOperations,
  reduceTravelQuery,
  reduceTravelQueryOperations,
  toRecommendationFilters,
} from './travelQueryState'

function reduce(...operations: Parameters<typeof reduceTravelQuery>[1][]) {
  return operations.reduce(reduceTravelQuery, createEmptyTravelQueryState())
}

describe('reduceTravelQuery', () => {
  it('叠加核心意图、偏好和时间限制', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_PREFERENCE', value: 'QUIET' },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 60 },
    )

    expect(state.intents).toEqual(['HUNGRY'])
    expect(state.preferences).toEqual(['QUIET'])
    expect(state.constraints.durationMinutes).toBe(60)
  })

  it('重复追加同一意图时不产生重复项', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_INTENT', value: 'HUNGRY' },
    )

    expect(state.intents).toEqual(['HUNGRY'])
  })

  it('保留多个核心意图的先后顺序', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_INTENT', value: 'WANT_WALK' },
    )

    expect(state.intents).toEqual(['HUNGRY', 'WANT_WALK'])
  })

  it('把 1 小时改成 2 小时时只替换时间', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_PREFERENCE', value: 'QUIET' },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 60 },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 120 },
    )

    expect(state.intents).toEqual(['HUNGRY'])
    expect(state.preferences).toEqual(['QUIET'])
    expect(state.constraints.durationMinutes).toBe(120)
  })

  it('更新预算时不影响吃饭意图', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'SET_CONSTRAINT', key: 'budgetMax', value: 100 },
      { op: 'SET_CONSTRAINT', key: 'budgetMax', value: 200 },
    )

    expect(state.intents).toEqual(['HUNGRY'])
    expect(state.constraints.budgetMax).toBe(200)
  })

  it('“不要安静的”只删除安静偏好', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_PREFERENCE', value: 'QUIET' },
      { op: 'REMOVE_PREFERENCE', value: 'QUIET' },
    )

    expect(state.intents).toEqual(['HUNGRY'])
    expect(state.preferences).toEqual([])
  })

  it('改变核心意图时保留偏好和限制', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_PREFERENCE', value: 'QUIET' },
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 1000 },
      { op: 'REPLACE_INTENTS', values: ['WANT_WALK'] },
    )

    expect(state.intents).toEqual(['WANT_WALK'])
    expect(state.preferences).toEqual(['QUIET'])
    expect(state.constraints.maxDistanceMeters).toBe(1000)
  })

  it('删除某个意图时不影响其他意图', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_INTENT', value: 'WANT_WALK' },
      { op: 'REMOVE_INTENT', value: 'HUNGRY' },
    )

    expect(state.intents).toEqual(['WANT_WALK'])
  })

  it('CLEAR_TRAVEL_INTENT 清空本次想法的所有层', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_PREFERENCE', value: 'QUIET' },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 60 },
      { op: 'SET_CONSTRAINT', key: 'budgetMax', value: 100 },
      { op: 'MERGE_BIASES', values: { HIDDEN_GEMS: 0.6 } },
      { op: 'CLEAR_TRAVEL_INTENT' },
    )

    expect(state).toEqual(createEmptyTravelQueryState())
  })

  it('用 null 只清除指定的限制', () => {
    const state = reduce(
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 60 },
      { op: 'SET_CONSTRAINT', key: 'budgetMax', value: 100 },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: null },
    )

    expect(state.intents).toEqual(['HUNGRY'])
    expect(state.constraints.durationMinutes).toBeNull()
    expect(state.constraints.budgetMax).toBe(100)
  })

  it('删除一个地点倾向时保留其他倾向', () => {
    const state = reduce(
      { op: 'MERGE_BIASES', values: { HIDDEN_GEMS: 0.6, CROWD_TOLERANCE: -0.5 } },
      { op: 'REMOVE_BIAS', key: 'HIDDEN_GEMS' },
    )

    expect(state.biases).toEqual({ CROWD_TOLERANCE: -0.5 })
  })

  it('把结构化状态翻译成现有推荐接口的输入', () => {
    const query = reduceTravelQueryOperations(createEmptyTravelQueryState(), [
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'ADD_INTENT', value: 'WANT_WALK' },
      { op: 'ADD_PREFERENCE', value: 'QUIET' },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 60 },
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 1500 },
      { op: 'SET_CONSTRAINT', key: 'budgetMax', value: 100 },
      { op: 'MERGE_BIASES', values: { HIDDEN_GEMS: 0.6 } },
    ])

    expect(toRecommendationFilters(query)).toEqual({
      states: ['HUNGRY', 'WANT_WALK', 'QUIET'],
      remainingMinutes: 60,
      maxDistanceKm: 1.5,
      maxTicketPrice: 100,
      biases: { HIDDEN_GEMS: 0.6 },
    })
  })

  it('未显式设置时间时使用引擎的 240 分钟默认值', () => {
    expect(toRecommendationFilters(createEmptyTravelQueryState()).remainingMinutes).toBe(240)
  })
})

describe('describeTravelQueryOperations', () => {
  it.each([
    [
      [{ op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 120 }],
      '已把时间改为 2 小时',
    ],
    [
      [{ op: 'SET_CONSTRAINT', key: 'budgetMax', value: 200 }],
      '已把预算改为 200 元以内',
    ],
    [
      [{ op: 'REMOVE_PREFERENCE', value: 'QUIET' }],
      '已取消「想安静」',
    ],
    [
      [{ op: 'REPLACE_INTENTS', values: ['WANT_WALK'] }],
      '已把主要活动改为「想散步」',
    ],
    [
      [{ op: 'CLEAR_TRAVEL_INTENT' }],
      '已清空这次的旅行想法',
    ],
  ] as const)('把状态变化明确说给用户：%s', (operations, expected) => {
    expect(describeTravelQueryOperations(operations)).toBe(expected)
  })

  it('按执行顺序复述一句话里的多个变化', () => {
    expect(describeTravelQueryOperations([
      { op: 'ADD_PREFERENCE', value: 'QUIET' },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 60 },
    ])).toBe('已加上「想安静」；已把时间改为 1 小时')
  })

  it('把听懂但暂时用不上的条件如实告诉用户', () => {
    expect(describeTravelQueryOperations(
      [{ op: 'ADD_INTENT', value: 'HUNGRY' }],
      ['想找个能带狗的地方'],
    )).toBe('已加上「想吃饭」；“想找个能带狗的地方”暂时没用上')
  })
})

describe('createTravelQueryChips', () => {
  it('把每个真实参与排序的地点倾向显示成可单独删除的 Chip', () => {
    const query = reduce(
      { op: 'MERGE_BIASES', values: { HIDDEN_GEMS: 0.6, CROWD_TOLERANCE: -0.5 } },
    )

    expect(createTravelQueryChips(query)).toEqual([
      {
        id: 'bias-HIDDEN_GEMS',
        label: '偏爱小众独特',
        removeOperation: { op: 'REMOVE_BIAS', key: 'HIDDEN_GEMS' },
      },
      {
        id: 'bias-CROWD_TOLERANCE',
        label: '避开人群氛围',
        removeOperation: { op: 'REMOVE_BIAS', key: 'CROWD_TOLERANCE' },
      },
    ])
  })
})
