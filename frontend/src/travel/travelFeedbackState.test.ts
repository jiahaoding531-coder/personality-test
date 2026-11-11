import { describe, expect, it } from 'vitest'

import type { FeedbackEventResponse } from '../types'
import type { RecommendationReplyTarget } from '../types'
import {
  applyFeedbackOperations,
  clearReplyTarget,
  interpretFeedbackOperations,
  selectReplyTarget,
} from './travelFeedbackState'
import {
  createEmptyTravelQueryState,
  interpretOperations,
  type TravelQueryState,
} from './travelQueryState'

describe('applyFeedbackOperations', () => {
  it('反馈响应只通过 reducer 合并并保留无关条件', () => {
    const existingQuery: TravelQueryState = {
      intents: ['HUNGRY'],
      preferences: ['QUIET'],
      constraints: { durationMinutes: 60, maxDistanceMeters: 5000, budgetMax: 100 },
      biases: {},
    }
    const response: FeedbackEventResponse = {
      eventId: 55,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [{ op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 2000 }],
      unrecognized: [],
      summary: '我把距离收近一些',
      shouldRecommend: true,
      excludeSeen: false,
    }

    const result = applyFeedbackOperations(existingQuery, response)

    expect(result.query.intents).toEqual(['HUNGRY'])
    expect(result.query.preferences).toEqual(['QUIET'])
    expect(result.query.constraints.maxDistanceMeters).toBe(2000)
    expect(result.message).toContain('距离')
  })

  it('反馈响应支持取消约束、删除偏好和清空旅行意图协议', () => {
    const query: TravelQueryState = {
      intents: ['HUNGRY'],
      preferences: ['QUIET'],
      constraints: { durationMinutes: 60, maxDistanceMeters: 5000, budgetMax: 100 },
      biases: { WALKING: -0.5 },
    }
    const response: FeedbackEventResponse = {
      eventId: 56,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [
        { op: 'SET_CONSTRAINT', key: 'budgetMax', value: null },
        { op: 'REMOVE_BIAS', key: 'WALKING' },
        { op: 'CLEAR_TRAVEL_INTENT' },
      ],
      unrecognized: [],
      summary: '清空当前条件',
      shouldRecommend: true,
      excludeSeen: false,
    }

    expect(applyFeedbackOperations(query, response).query).toEqual(createEmptyTravelQueryState())
  })
})

describe('推荐回复目标', () => {
  const first: RecommendationReplyTarget = { recommendationId: 11, placeName: '灵隐寺' }
  const second: RecommendationReplyTarget = { recommendationId: 22, placeName: '西溪湿地' }

  it('选择第二张卡片会替换回复目标，取消后回到普通输入', () => {
    expect(selectReplyTarget(first, second)).toEqual(second)
    expect(clearReplyTarget(second)).toBeNull()
  })
})

describe('operation protocol validation', () => {
  it('普通 interpret 和反馈响应都丢弃非法 operation', () => {
    const malformed = {
      operations: [
        { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 2000 },
        { op: 'SET_CONSTRAINT', key: 'not-a-constraint', value: 100 },
        { op: 'MERGE_BIASES', values: { WALKING: Number.NaN } },
        { op: 'REMOVE_BIAS', key: '' },
        { op: 'CLEAR_TRAVEL_INTENT', extra: 'ignored' },
      ],
      states: [],
      biases: {},
      remainingMinutes: null,
      maxDistanceKm: null,
      maxTicketPrice: null,
    }

    const ordinary = interpretOperations(malformed)
    const feedback = interpretFeedbackOperations(malformed.operations)

    expect(ordinary).toEqual([
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 2000 },
      { op: 'CLEAR_TRAVEL_INTENT' },
    ])
    expect(feedback).toEqual(ordinary)
  })

  it('按后端协议校验维度白名单、偏向范围、约束范围和空意图', () => {
    const operations = [
      { op: 'REPLACE_INTENTS', values: [] },
      { op: 'MERGE_BIASES', values: { UNKNOWN: 0.5, NATURE: 2, FOOD: -2, walking: 0.5 } },
      { op: 'REMOVE_BIAS', key: 'UNKNOWN' },
      { op: 'REMOVE_BIAS', key: '   ' },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 14 },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: 1441 },
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: -1 },
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 499 },
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 500 },
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 50001 },
      { op: 'SET_CONSTRAINT', key: 'budgetMax', value: -1 },
      { op: 'SET_CONSTRAINT', key: 'budgetMax', value: 1001 },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: null },
    ]

    expect(interpretFeedbackOperations(operations)).toEqual([
      { op: 'MERGE_BIASES', values: { WALKING: 0.5 } },
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 500 },
      { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: null },
    ])
  })

  it('畸形 operation 不得污染当前查询状态', () => {
    const query: TravelQueryState = {
      intents: ['HUNGRY'],
      preferences: ['QUIET'],
      constraints: { durationMinutes: 60, maxDistanceMeters: 5000, budgetMax: 100 },
      biases: { NATURE: 0.5 },
    }
    const response: FeedbackEventResponse = {
      eventId: 57,
      kind: 'SESSION_ADJUSTMENT',
      reaction: null,
      operations: [
        { op: 'REPLACE_INTENTS', values: [] },
        { op: 'MERGE_BIASES', values: { UNKNOWN: 0.5, NATURE: 2 } },
        { op: 'REMOVE_BIAS', key: 'UNKNOWN' },
        { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 50001 },
      ],
      unrecognized: [],
      summary: '无效条件',
      shouldRecommend: false,
      excludeSeen: false,
    }

    expect(applyFeedbackOperations(query, response).query).toEqual(query)
  })

  it('旧 interpret 字段回退路径也经过同一套范围和白名单校验', () => {
    expect(interpretOperations({
      operations: [],
      states: [{ key: 'HUNGRY' }, { key: 'NOT_A_STATE' }],
      biases: { UNKNOWN: 0.5, nature: 2, WALKING: 0.5 },
      remainingMinutes: 14,
      maxDistanceKm: 0.499,
      maxTicketPrice: 1001,
    })).toEqual([
      { op: 'ADD_INTENT', value: 'HUNGRY' },
      { op: 'MERGE_BIASES', values: { WALKING: 0.5 } },
    ])

    expect(interpretOperations({
      operations: [],
      states: [],
      biases: {},
      remainingMinutes: null,
      maxDistanceKm: 0.5,
      maxTicketPrice: null,
    })).toEqual([
      { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: 500 },
    ])
  })
})
