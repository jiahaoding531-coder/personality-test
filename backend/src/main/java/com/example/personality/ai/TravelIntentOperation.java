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
        REMOVE_BIAS,
        CLEAR_TRAVEL_INTENT
    }

    public enum ConstraintKey {
        DURATION_MINUTES,
        MAX_DISTANCE_METERS,
        BUDGET_MAX
    }

    public static TravelIntentOperation addIntent(TravelState state) {
        return stateOperation(Type.ADD_INTENT, state);
    }

    public static TravelIntentOperation removeIntent(TravelState state) {
        return stateOperation(Type.REMOVE_INTENT, state);
    }

    public static TravelIntentOperation replaceIntents(List<TravelState> states) {
        return new TravelIntentOperation(Type.REPLACE_INTENTS, null, states, null, null, Map.of());
    }

    public static TravelIntentOperation addPreference(TravelState state) {
        return stateOperation(Type.ADD_PREFERENCE, state);
    }

    public static TravelIntentOperation removePreference(TravelState state) {
        return stateOperation(Type.REMOVE_PREFERENCE, state);
    }

    public static TravelIntentOperation setConstraint(ConstraintKey key, double value) {
        return new TravelIntentOperation(Type.SET_CONSTRAINT, null, List.of(), key, value, Map.of());
    }

    public static TravelIntentOperation clearConstraint(ConstraintKey key) {
        return new TravelIntentOperation(Type.SET_CONSTRAINT, null, List.of(), key, null, Map.of());
    }

    public static TravelIntentOperation mergeBiases(Map<TravelDimension, Double> biases) {
        return new TravelIntentOperation(Type.MERGE_BIASES, null, List.of(), null, null, biases);
    }

    public static TravelIntentOperation removeBias(TravelDimension dimension) {
        return new TravelIntentOperation(Type.REMOVE_BIAS, null, List.of(), null, null,
                Map.of(dimension, 0.0));
    }

    public static TravelIntentOperation clearTravelIntent() {
        return new TravelIntentOperation(Type.CLEAR_TRAVEL_INTENT, null, List.of(), null, null, Map.of());
    }

    private static TravelIntentOperation stateOperation(Type type, TravelState state) {
        return new TravelIntentOperation(type, state, List.of(), null, null, Map.of());
    }
}
