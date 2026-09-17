export type NaturalLanguageStateKind =
  | 'idle'
  | 'loading'
  | 'nothing'
  | 'noServerAi'
  | 'keyRejected'
  | 'error'

export type AiRetryTarget = 'naturalLanguage' | 'reasons' | null

/** 保存 Key 后只重试刚才真正失败的那条链路。 */
export function chooseAiRetryTarget(
  naturalLanguageState: NaturalLanguageStateKind,
  hasRecommendations: boolean,
): AiRetryTarget {
  if (naturalLanguageState === 'noServerAi' || naturalLanguageState === 'keyRejected') {
    return 'naturalLanguage'
  }
  return hasRecommendations ? 'reasons' : null
}

export interface LatestRequestGate {
  next: () => number
  isCurrent: (sequence: number) => boolean
}

/**
 * 为同一类请求发递增序号。旧请求即使更晚返回，也不能覆盖最后一次操作。
 */
export function createLatestRequestGate(): LatestRequestGate {
  let current = 0
  return {
    next: () => ++current,
    isCurrent: (sequence) => sequence === current,
  }
}

export interface SingleFlightGate<T> {
  tryStart: (task: T) => boolean
  takeLatest: () => T | null
  finish: () => void
}

/** 同一时刻只执行一个任务；忙碌期间反复提交时，只留下最后一次。 */
export function createSingleFlightGate<T>(): SingleFlightGate<T> {
  let running = false
  let latest: T | null = null
  return {
    tryStart: (task) => {
      if (running) {
        latest = task
        return false
      }
      running = true
      return true
    },
    takeLatest: () => {
      const task = latest
      latest = null
      return task
    },
    finish: () => {
      running = false
      latest = null
    },
  }
}
