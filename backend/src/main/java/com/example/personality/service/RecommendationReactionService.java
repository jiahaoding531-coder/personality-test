package com.example.personality.service;
import com.example.personality.domain.*;
import com.example.personality.entity.RecommendationFeedback.Reaction;
import com.example.personality.exception.BehaviorRequestException;
import com.example.personality.dto.FeedbackState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** 不计算评分，只保证反应投影与事实同一次 commit/rollback。 */
@Service
public class RecommendationReactionService {
    private final JdbcTemplate jdbc;
    private final BehaviorEventStore store;
    private final BehaviorTargetValidator validator;
    private final BehaviorEventService events;
    private final TravelFeedbackPersistence feedback;
    private final Clock clock;
    public RecommendationReactionService(JdbcTemplate jdbc,BehaviorEventStore store,BehaviorTargetValidator validator,
            BehaviorEventService events,TravelFeedbackPersistence feedback,Clock clock) {
        this.jdbc=jdbc; this.store=store; this.validator=validator; this.events=events; this.feedback=feedback; this.clock=clock;
    }
    @Transactional
    public FeedbackState apply(Long sessionId,Long recommendationId,Reaction reaction,BehaviorMetadata metadata) {
        var owners=jdbc.query("SELECT user_id FROM test_sessions WHERE id=? AND scale='TRAVEL'",
                (rs,n)->(Long)rs.getObject(1),sessionId);
        var targets=jdbc.query("SELECT place_id::text FROM recommendations WHERE id=? AND session_id=?",
                (rs,n)->rs.getString(1),recommendationId,sessionId);
        if(owners.size()!=1 || targets.size()!=1) throw BehaviorRequestException.missing();
        var scope=new AccessScope(sessionId,owners.get(0));
        if(metadata==null) metadata=new BehaviorMetadata(UUID.randomUUID(),clock.instant());
        var command=new BehaviorEventCommand(metadata.eventId(),1,BehaviorTargetType.PLACE,BehaviorProvider.LOCAL,targets.get(0),
                reaction==null?BehaviorAction.REACTION_CLEAR:BehaviorAction.REACTION_SET,metadata.occurredAt(),
                recommendationId,null,null,null,reaction==null?null:reaction.name(),List.of(),metadata.expectedFeedbackVersion());
        command.validate(true); validator.validateScope(scope);
        // 用户→会话→推荐，再竞争事件 ID。所有反应写入共享推荐锁顺序。
        feedback.requireFeedbackVersion(recommendationId,null);
        if(store.existing(scope,command)) return feedback.reactionState(recommendationId);
        feedback.requireFeedbackVersion(recommendationId,metadata.expectedFeedbackVersion());
        events.validateNew(scope,command);
        // 先用唯一约束竞争事件；重复命令不得重做其后的业务写入。
        if(!store.insert(scope,command)) return feedback.reactionState(recommendationId);
        if(reaction==null) feedback.deleteReaction(recommendationId);
        else feedback.upsertReaction(recommendationId,reaction);
        return feedback.reactionState(recommendationId);
    }
}
