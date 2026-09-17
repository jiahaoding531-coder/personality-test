import type { TravelState } from '../types'

export type TravelIntent = Extract<TravelState, 'HUNGRY' | 'WANT_WALK'>
export type TravelPreference = Exclude<TravelState, TravelIntent>

export interface TravelConstraints {
  durationMinutes: number | null
  maxDistanceMeters: number | null
  budgetMax: number | null
}

export interface TravelQueryState {
  intents: TravelIntent[]
  preferences: TravelPreference[]
  constraints: TravelConstraints
  biases: Record<string, number>
}

export type TravelQueryOperation =
  | { op: 'ADD_INTENT'; value: TravelIntent }
  | { op: 'REMOVE_INTENT'; value: TravelIntent }
  | { op: 'REPLACE_INTENTS'; values: readonly TravelIntent[] }
  | { op: 'ADD_PREFERENCE'; value: TravelPreference }
  | { op: 'REMOVE_PREFERENCE'; value: TravelPreference }
  | { op: 'SET_CONSTRAINT'; key: keyof TravelConstraints; value: number | null }
  | { op: 'MERGE_BIASES'; values: Record<string, number> }
  | { op: 'REMOVE_BIAS'; key: string }
  | { op: 'CLEAR_TRAVEL_INTENT' }

export function createEmptyTravelQueryState(): TravelQueryState {
  return {
    intents: [],
    preferences: [],
    constraints: {
      durationMinutes: null,
      maxDistanceMeters: null,
      budgetMax: null,
    },
    biases: {},
  }
}

export function reduceTravelQuery(
  state: TravelQueryState,
  operation: TravelQueryOperation,
): TravelQueryState {
  switch (operation.op) {
    case 'ADD_INTENT':
      return state.intents.includes(operation.value)
        ? state
        : { ...state, intents: [...state.intents, operation.value] }
    case 'REMOVE_INTENT':
      return { ...state, intents: state.intents.filter((item) => item !== operation.value) }
    case 'REPLACE_INTENTS':
      return { ...state, intents: [...new Set(operation.values)] }
    case 'ADD_PREFERENCE':
      return state.preferences.includes(operation.value)
        ? state
        : { ...state, preferences: [...state.preferences, operation.value] }
    case 'REMOVE_PREFERENCE':
      return {
        ...state,
        preferences: state.preferences.filter((item) => item !== operation.value),
      }
    case 'SET_CONSTRAINT':
      return {
        ...state,
        constraints: { ...state.constraints, [operation.key]: operation.value },
      }
    case 'MERGE_BIASES':
      return { ...state, biases: { ...state.biases, ...operation.values } }
    case 'REMOVE_BIAS': {
      const { [operation.key]: _removed, ...biases } = state.biases
      return { ...state, biases }
    }
    case 'CLEAR_TRAVEL_INTENT':
      return createEmptyTravelQueryState()
  }
}

export function reduceTravelQueryOperations(
  state: TravelQueryState,
  operations: readonly TravelQueryOperation[],
): TravelQueryState {
  return operations.reduce(reduceTravelQuery, state)
}

const QUERY_LABELS: Record<TravelState, string> = {
  HUNGRY: '想吃饭',
  WANT_WALK: '想散步',
  TIRED: '有点累',
  QUIET: '想安静',
  PHOTO: '想拍照',
  CULTURE: '想看人文',
  NATURE: '想亲近自然',
}

export interface TravelQueryChip {
  id: string
  label: string
  removeOperation: TravelQueryOperation
}

/** 所有真正参与推荐的 Session Intent，都必须投影成可见、可删除的 Chip。 */
export function createTravelQueryChips(state: TravelQueryState): TravelQueryChip[] {
  const chips: TravelQueryChip[] = []
  for (const intent of state.intents) {
    chips.push({
      id: `intent-${intent}`,
      label: QUERY_LABELS[intent],
      removeOperation: { op: 'REMOVE_INTENT', value: intent },
    })
  }
  for (const preference of state.preferences) {
    chips.push({
      id: `preference-${preference}`,
      label: QUERY_LABELS[preference],
      removeOperation: { op: 'REMOVE_PREFERENCE', value: preference },
    })
  }
  if (state.constraints.durationMinutes !== null) {
    chips.push({
      id: 'minutes',
      label: `剩 ${state.constraints.durationMinutes} 分钟`,
      removeOperation: { op: 'SET_CONSTRAINT', key: 'durationMinutes', value: null },
    })
  }
  if (state.constraints.maxDistanceMeters !== null) {
    chips.push({
      id: 'distance',
      label: `${formatDistance(state.constraints.maxDistanceMeters)}内`,
      removeOperation: { op: 'SET_CONSTRAINT', key: 'maxDistanceMeters', value: null },
    })
  }
  if (state.constraints.budgetMax !== null) {
    chips.push({
      id: 'budget',
      label: `预算 ≤ ${state.constraints.budgetMax} 元`,
      removeOperation: { op: 'SET_CONSTRAINT', key: 'budgetMax', value: null },
    })
  }
  for (const [key, value] of Object.entries(state.biases)) {
    chips.push({
      id: `bias-${key}`,
      label: `${value < 0 ? '避开' : '偏爱'}${biasLabel(key)}`,
      removeOperation: { op: 'REMOVE_BIAS', key },
    })
  }
  return chips
}

/** 把 reducer 即将执行的操作复述成人话，让状态变化对用户可见。 */
export function describeTravelQueryOperations(
  operations: readonly TravelQueryOperation[],
  unrecognized: readonly string[] = [],
): string {
  const changes = operations.map((operation) => {
    switch (operation.op) {
      case 'ADD_INTENT':
      case 'ADD_PREFERENCE':
        return `已加上「${QUERY_LABELS[operation.value]}」`
      case 'REMOVE_INTENT':
      case 'REMOVE_PREFERENCE':
        return `已取消「${QUERY_LABELS[operation.value]}」`
      case 'REPLACE_INTENTS':
        return operation.values.length === 0
          ? '已清空主要活动'
          : `已把主要活动改为${operation.values.map((value) => `「${QUERY_LABELS[value]}」`).join('、')}`
      case 'SET_CONSTRAINT':
        if (operation.value === null) {
          return `已取消${constraintLabel(operation.key)}限制`
        }
        if (operation.key === 'durationMinutes') {
          const duration = operation.value % 60 === 0
            ? `${operation.value / 60} 小时`
            : `${operation.value} 分钟`
          return `已把时间改为 ${duration}`
        }
        if (operation.key === 'maxDistanceMeters') {
          return `已把距离改为 ${formatDistance(operation.value)}以内`
        }
        return `已把预算改为 ${operation.value} 元以内`
      case 'MERGE_BIASES':
        return '已更新地点偏好'
      case 'REMOVE_BIAS':
        return `已取消「${biasLabel(operation.key)}」地点偏好`
      case 'CLEAR_TRAVEL_INTENT':
        return '已清空这次的旅行想法'
    }
  })
  changes.push(...unrecognized.map((item) => `“${item}”暂时没用上`))
  return changes.join('；')
}

function constraintLabel(key: keyof TravelConstraints): string {
  return ({
    durationMinutes: '时间',
    maxDistanceMeters: '距离',
    budgetMax: '预算',
  } satisfies Record<keyof TravelConstraints, string>)[key]
}

function formatDistance(meters: number): string {
  return meters < 1000 ? `${meters} 米` : `${Number((meters / 1000).toFixed(1))} 公里`
}

export function biasLabel(key: string): string {
  return ({
    NATURE: '自然风光',
    CULTURE: '人文历史',
    FOOD: '美食探索',
    PHOTOGRAPHY: '摄影出片',
    HIDDEN_GEMS: '小众独特',
    CROWD_TOLERANCE: '人群氛围',
    WALKING: '步行强度',
    PLANNING: '安排方式',
  } satisfies Record<string, string>)[key] ?? key
}

export interface RecommendationFilters {
  states: TravelState[]
  remainingMinutes: number
  maxDistanceKm: number | null
  maxTicketPrice: number | null
  biases: Record<string, number>
}

export function toRecommendationFilters(state: TravelQueryState): RecommendationFilters {
  return {
    states: [...state.intents, ...state.preferences],
    remainingMinutes: state.constraints.durationMinutes ?? 240,
    maxDistanceKm: state.constraints.maxDistanceMeters === null
      ? null
      : state.constraints.maxDistanceMeters / 1000,
    maxTicketPrice: state.constraints.budgetMax,
    biases: state.biases,
  }
}
