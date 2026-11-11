import type { FeedbackEventResponse, RecommendationReplyTarget } from '../types'
import {
  describeTravelQueryOperations,
  interpretFeedbackOperations,
  reduceTravelQueryOperations,
  type TravelQueryState,
} from './travelQueryState'

/**
 * 把反馈响应交给和普通 interpret 相同的 reducer。
 *
 * 反馈响应不能直接替换 query：用户可能只说了“太远”，原有的活动、偏好和
 * 其它限制都必须保留；真正改变哪些字段由受控 operations 决定。
 */
export function applyFeedbackOperations(
  query: TravelQueryState,
  response: FeedbackEventResponse,
): { query: TravelQueryState; message: string } {
  const operations = interpretFeedbackOperations(response.operations)
  const nextQuery = reduceTravelQueryOperations(query, operations)
  const message = describeTravelQueryOperations(operations, response.unrecognized)
    || response.summary
    || '已记录你的反馈'
  return { query: nextQuery, message }
}

/** 选择卡片后始终以最后一次选择为准，避免回复目标残留在上一张卡片。 */
export function selectReplyTarget(
  _current: RecommendationReplyTarget | null,
  next: RecommendationReplyTarget,
): RecommendationReplyTarget {
  return next
}

/** 取消卡片回复，恢复普通的旅行条件输入。 */
export function clearReplyTarget(_current: RecommendationReplyTarget | null): null {
  return null
}

export { interpretFeedbackOperations }
