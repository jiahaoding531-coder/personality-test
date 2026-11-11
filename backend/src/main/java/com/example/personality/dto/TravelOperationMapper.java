package com.example.personality.dto;

import com.example.personality.ai.TravelIntentOperation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把 AI 的内部操作协议转换成前端 reducer 使用的响应格式。 */
public final class TravelOperationMapper {

    private TravelOperationMapper() {
    }

    public static List<InterpretResponse.Operation> toResponseOperations(
            List<TravelIntentOperation> operations) {
        List<InterpretResponse.Operation> converted = new ArrayList<>(operations.size());
        for (TravelIntentOperation operation : operations) {
            Object value = operation.state() == null ? operation.numberValue() : operation.state().name();
            Object values = null;
            String key = constraintKey(operation.constraintKey());
            if (!operation.states().isEmpty()) {
                values = operation.states().stream().map(Enum::name).toList();
            } else if (operation.op() == TravelIntentOperation.Type.REMOVE_BIAS) {
                key = operation.biases().keySet().iterator().next().name();
            } else if (!operation.biases().isEmpty()) {
                Map<String, Double> namedBiases = new LinkedHashMap<>();
                operation.biases().forEach((dimension, bias) -> namedBiases.put(dimension.name(), bias));
                values = namedBiases;
            }
            converted.add(new InterpretResponse.Operation(
                    operation.op().name(), value, values, key));
        }
        return converted;
    }

    private static String constraintKey(TravelIntentOperation.ConstraintKey key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case DURATION_MINUTES -> "durationMinutes";
            case MAX_DISTANCE_METERS -> "maxDistanceMeters";
            case BUDGET_MAX -> "budgetMax";
        };
    }
}
