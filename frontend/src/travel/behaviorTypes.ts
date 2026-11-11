export interface BehaviorScope { userId: number | null; sessionId: number; sessionToken?: string; generation: number }
export interface BehaviorSource { recommendationId?: number; searchSessionId?: string; resultSetId?: string; targetId: string }
export interface BehaviorDraft {
  targetType: 'PLACE' | 'RESTAURANT'; provider: 'LOCAL' | 'AMAP'; targetId: string | null
  action: 'NAVIGATION_REQUESTED' | 'EXCLUDE' | 'NEXT_BATCH_REQUESTED'
  recommendationId?: number; recommendationBatchNo?: number
  searchSessionId?: string; resultSetId?: string; visibleSources?: BehaviorSource[]
}
export interface BehaviorMetadata { eventId: string; occurredAt: string; expectedFeedbackVersion?: number }
export interface BehaviorEnvelope extends BehaviorDraft, BehaviorMetadata { schemaVersion: 1 }
export interface BehaviorReceipt { eventId: string; duplicate: boolean }
