package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;

import java.util.List;
import java.util.Map;

/**
 * 自然语言只能产生“操作”，不能直接覆盖会话状态。
 * 前端会把这些操作交给纯 reducer 执行。
 */
public record TravelIntentOperation(
        Type op,
        TravelState state,
        List<TravelState> states,
        ConstraintKey constraintKey,
        Double numberValue,
        Map<TravelDimension, Double> biases
) {
    public enum Type {
        ADD_INTENT,
        REMOVE_INTENT,
        REPLACE_INTENTS,
        ADD_PREFERENCE,
        REMOVE_PREFERENCE,
        SET_CONSTRAINT,
        MERGE_BIASES,
        CLEAR_TRAVEL_INTENT
    }

    public enum ConstraintKey {
        DURATION_MINUTES,
        MAX_DISTANCE_METERS,
        BUDGET_MAX
    }
}
