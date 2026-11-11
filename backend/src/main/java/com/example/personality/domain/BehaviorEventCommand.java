package com.example.personality.domain;

import com.example.personality.exception.BehaviorRequestException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** record 类似 Python dataclass：不可变命令，同一动作重试必须保留全部字段。 */
public record BehaviorEventCommand(UUID eventId, Integer schemaVersion, BehaviorTargetType targetType,
        BehaviorProvider provider, String targetId, BehaviorAction action, Instant occurredAt,
        Long recommendationId, Integer recommendationBatchNo, UUID searchSessionId, UUID resultSetId,
        String reaction, List<BehaviorSource> visibleSources, Long expectedFeedbackVersion) {
    public BehaviorEventCommand(UUID eventId,Integer schemaVersion,BehaviorTargetType targetType,
            BehaviorProvider provider,String targetId,BehaviorAction action,Instant occurredAt,
            Long recommendationId,Integer recommendationBatchNo,UUID searchSessionId,UUID resultSetId,
            String reaction,List<BehaviorSource> visibleSources) {
        this(eventId,schemaVersion,targetType,provider,targetId,action,occurredAt,recommendationId,
                recommendationBatchNo,searchSessionId,resultSetId,reaction,visibleSources,null);
    }
    public BehaviorEventCommand { visibleSources = visibleSources == null ? List.of() : List.copyOf(visibleSources); }

    public void validate(boolean allowReaction) {
        if (eventId == null || !Integer.valueOf(1).equals(schemaVersion) || action == null || occurredAt == null
                || targetType == null || provider == null || visibleSources.size() > 3) throw BehaviorRequestException.invalid();
        boolean place = targetType == BehaviorTargetType.PLACE && provider == BehaviorProvider.LOCAL;
        boolean restaurant = targetType == BehaviorTargetType.RESTAURANT && provider == BehaviorProvider.AMAP;
        if (!place && !restaurant) throw BehaviorRequestException.invalid();
        boolean next = action == BehaviorAction.NEXT_BATCH_REQUESTED;
        boolean reacts = action == BehaviorAction.REACTION_SET || action == BehaviorAction.REACTION_CLEAR;
        if(expectedFeedbackVersion!=null && (!reacts || !allowReaction || expectedFeedbackVersion<0))
            throw BehaviorRequestException.invalid();
        if (next ? targetId != null : targetId == null || targetId.isBlank() || targetId.length() > 100)
            throw BehaviorRequestException.invalid();
        if (reacts && (!allowReaction || !place)) throw BehaviorRequestException.invalid();
        if (action == BehaviorAction.REACTION_SET ? !("LIKE".equals(reaction) || "DISLIKE".equals(reaction)) : reaction != null)
            throw BehaviorRequestException.invalid();
        if (!next && !visibleSources.isEmpty()) throw BehaviorRequestException.invalid();
        if (place) {
            if(next && !visibleSources.isEmpty() && recommendationBatchNo==null) throw BehaviorRequestException.invalid();
            if (searchSessionId != null || resultSetId != null || action == BehaviorAction.EXCLUDE
                    || (next ? recommendationId != null : recommendationId == null || recommendationId <= 0)
                    || (recommendationBatchNo != null && recommendationBatchNo <= 0)) throw BehaviorRequestException.invalid();
        } else if (recommendationId != null || recommendationBatchNo != null || searchSessionId == null || resultSetId == null) {
            throw BehaviorRequestException.invalid();
        }
        for (BehaviorSource source : visibleSources) {
            if (source.targetId() == null || source.targetId().isBlank() || source.targetId().length() > 100
                    || (place ? source.recommendationId() == null || source.searchSessionId() != null || source.resultSetId() != null
                    : source.recommendationId() != null || source.searchSessionId() == null || source.resultSetId() == null
                        || !source.searchSessionId().equals(searchSessionId))) throw BehaviorRequestException.invalid();
        }
    }
}
