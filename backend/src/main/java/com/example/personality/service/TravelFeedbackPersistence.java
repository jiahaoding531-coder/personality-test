package com.example.personality.service;

import com.example.personality.ai.TextFeedbackResult;
import com.example.personality.ai.TravelFeedbackSignal;
import com.example.personality.ai.TravelIntentOperation;
import com.example.personality.dto.TravelQuerySnapshot;
import com.example.personality.dto.FeedbackState;
import com.example.personality.exception.BehaviorRequestException;
import com.example.personality.entity.Place;
import com.example.personality.entity.Recommendation;
import com.example.personality.entity.RecommendationFeedback;
import com.example.personality.entity.RecommendationFeedbackEvent;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.repository.PlaceRepository;
import com.example.personality.repository.RecommendationFeedbackEventRepository;
import com.example.personality.repository.RecommendationFeedbackRepository;
import com.example.personality.repository.RecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 反馈流程的数据库边界：每个公开方法都是一个短事务，不包含网络调用。
 */
@Service
public class TravelFeedbackPersistence {

    private final RecommendationRepository recommendationRepository;
    private final PlaceRepository placeRepository;
    private final RecommendationFeedbackEventRepository eventRepository;
    private final RecommendationFeedbackRepository feedbackRepository;
    private final JdbcTemplate jdbc;

    public TravelFeedbackPersistence(RecommendationRepository recommendationRepository,
                                     PlaceRepository placeRepository,
                                     RecommendationFeedbackEventRepository eventRepository,
                                     RecommendationFeedbackRepository feedbackRepository, JdbcTemplate jdbc) {
        this.recommendationRepository = recommendationRepository;
        this.placeRepository = placeRepository;
        this.eventRepository = eventRepository;
        this.feedbackRepository = feedbackRepository;
        this.jdbc = jdbc;
    }

    /** 读取推荐目标及其事实；查询本身带 sessionId，防止 IDOR。 */
    @Transactional(readOnly = true)
    public FeedbackTargetContext loadTarget(Long sessionId,
                                            Long recommendationId,
                                            double latitude,
                                            double longitude,
                                            TravelQuerySnapshot query) {
        if (recommendationId == null) {
            return new FeedbackTargetContext(null, null, null, null, null, null);
        }

        Recommendation recommendation = recommendationRepository
                .findByIdAndSessionId(recommendationId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("推荐记录不存在：id="
                        + recommendationId));
        Place place = placeRepository.findById(recommendation.getPlaceId())
                .orElseThrow(() -> new ResourceNotFoundException("推荐地点不存在：id="
                        + recommendation.getPlaceId()));

        double distance = FeedbackConstraintPolicy.haversineMeters(
                latitude, longitude, place.getLatitude(), place.getLongitude());
        Integer currentDistance = query == null ? null : query.maxDistanceMeters();
        Integer currentBudget = query == null ? null : query.budgetMax();
        return new FeedbackTargetContext(
                recommendationId,
                place.getName(),
                distance,
                FeedbackConstraintPolicy.suggestCloserLimit(distance, currentDistance)
                        .orElse(null),
                place.getTicketPrice(),
                FeedbackConstraintPolicy.suggestBudgetLimit(place.getTicketPrice(), currentBudget)
                        .orElse(null));
    }

    /** 在一个短写事务内保存事件，并在有明确 reaction 时同步旧反馈表。 */
    @Transactional
    public Long saveEventAndOptionalReaction(SaveFeedbackEventCommand command) {
        TextFeedbackResult result = command.result();
        // INSERT 的外键会先取得推荐行共享锁。必须在插入前锁父行，避免两个事务
        // 各持共享锁再升级排他锁而死锁；事件和反馈仍像 JDBC commit 一样一起提交。
        if (result.reaction() != null && command.recommendationId() != null) {
            requireFeedbackVersion(command.recommendationId(),command.expectedFeedbackVersion());
        }
        RecommendationFeedbackEvent event = RecommendationFeedbackEvent.create(
                command.sessionId(),
                command.recommendationId(),
                command.rawText(),
                result.kind(),
                result.reaction(),
                signalsJson(result),
                operationsJson(result));
        RecommendationFeedbackEvent saved = eventRepository.save(event);

        if (result.reaction() != null && command.recommendationId() != null) {
            doUpsertReaction(command.recommendationId(), result.reaction());
        }
        return saved.getId();
    }

    /** 在同一个事务内取得提交版本，防止事件完成后再读取到别人的版本。 */
    @Transactional
    public SavedFeedbackEvent saveEventWithState(SaveFeedbackEventCommand command) {
        Long id=saveEventAndOptionalReaction(command);
        return new SavedFeedbackEvent(id,command.result(),command.recommendationId()==null?null:reactionState(command.recommendationId()));
    }

    @Transactional(readOnly=true)
    public FeedbackState reactionState(Long id) {
        var rows=jdbc.query("""
                SELECT f.reaction,r.feedback_revision FROM recommendations r
                LEFT JOIN recommendation_feedback f ON f.recommendation_id=r.id WHERE r.id=?
                """,(rs,n)->new FeedbackState(rs.getString(1),rs.getLong(2)),id);
        if(rows.isEmpty()) throw new ResourceNotFoundException("推荐记录不存在");
        return rows.get(0);
    }

    /** 对比和写入都在同一推荐行锁内，不信任客户端时间戳。 */
    public void requireFeedbackVersion(Long id,Long expected) {
        lockRecommendation(id);
        if(expected!=null && reactionState(id).version()!=expected) throw BehaviorRequestException.staleFeedback();
    }

    /** 旧的 👍/👎 接口与自然语言反馈共用同一份 upsert 规则。 */
    @Transactional
    public void upsertReaction(Long recommendationId,
                               RecommendationFeedback.Reaction reaction) {
        doUpsertReaction(recommendationId, reaction);
    }

    /** 撤销按钮反馈；记录不存在时保持幂等，不把重复请求变成错误。 */
    @Transactional
    public void deleteReaction(Long recommendationId) {
        lockRecommendation(recommendationId);
        feedbackRepository.findByRecommendationId(recommendationId)
                .ifPresent(feedbackRepository::delete);
        feedbackRepository.flush();
        advanceVersion(recommendationId);
    }

    private void doUpsertReaction(Long recommendationId,
                                  RecommendationFeedback.Reaction reaction) {
        lockRecommendation(recommendationId);
        RecommendationFeedback feedback = feedbackRepository
                .findByRecommendationId(recommendationId)
                .orElseGet(() -> RecommendationFeedback.of(recommendationId, reaction));
        feedback.changeReaction(reaction);
        feedbackRepository.saveAndFlush(feedback);
        advanceVersion(recommendationId);
    }

    private void advanceVersion(Long id) {
        jdbc.update("UPDATE recommendations SET feedback_revision=feedback_revision+1 WHERE id=?",id);
    }

    /** 锁父推荐行；即使最初没有反馈行，也能串行化首次写入。自然语言路径共用此锁。 */
    private void lockRecommendation(Long id) {
        if(jdbc.query("SELECT id FROM recommendations WHERE id=? FOR UPDATE",(rs,n)->rs.getLong(1),id).isEmpty())
            throw new ResourceNotFoundException("推荐记录不存在");
    }

    private static JsonNode signalsJson(TextFeedbackResult result) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (TravelFeedbackSignal signal : result.signals()) {
            array.add(signal.name());
        }
        return array;
    }

    private static JsonNode operationsJson(TextFeedbackResult result) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (TravelIntentOperation operation : result.operations()) {
            ObjectNode item = JsonNodeFactory.instance.objectNode();
            item.put("op", operation.op().name());
            switch (operation.op()) {
                case ADD_INTENT, REMOVE_INTENT, ADD_PREFERENCE, REMOVE_PREFERENCE ->
                        item.put("value", operation.state().name());
                case REPLACE_INTENTS -> {
                    ArrayNode values = item.putArray("values");
                    operation.states().forEach(state -> values.add(state.name()));
                }
                case SET_CONSTRAINT -> {
                    item.put("key", constraintName(operation.constraintKey()));
                    if (operation.numberValue() == null) {
                        item.putNull("value");
                    } else {
                        item.put("value", operation.numberValue());
                    }
                }
                case MERGE_BIASES -> {
                    ObjectNode values = item.putObject("values");
                    for (Map.Entry<com.example.personality.domain.TravelDimension, Double> entry
                            : operation.biases().entrySet()) {
                        values.put(entry.getKey().name(), entry.getValue());
                    }
                }
                case REMOVE_BIAS -> {
                    if (!operation.biases().isEmpty()) {
                        item.put("key", operation.biases().keySet().iterator().next().name());
                    }
                }
                case CLEAR_TRAVEL_INTENT -> {
                    // 仅保存 op 名称。
                }
            }
            array.add(item);
        }
        return array;
    }

    private static String constraintName(TravelIntentOperation.ConstraintKey key) {
        return switch (key) {
            case DURATION_MINUTES -> "durationMinutes";
            case MAX_DISTANCE_METERS -> "maxDistanceMeters";
            case BUDGET_MAX -> "budgetMax";
        };
    }
}
