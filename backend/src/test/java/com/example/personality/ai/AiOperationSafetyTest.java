package com.example.personality.ai;

import com.example.personality.exception.AiServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 模型是不可信输入；非法操作绝不能变成有效的状态修改。 */
class AiOperationSafetyTest {
    @Test void 非法字段不能夹带在清空操作中() {
        var result = DeepSeekTextIntentGenerator.parse("""
            {"operations":[{"op":"CLEAR_TRAVEL_INTENT","userId":99},
            {"op":"ADD_PREFERENCE","value":"QUIET"}]}
            """);
        assertEquals(1, result.operations().size());
        assertEquals(TravelIntentOperation.Type.ADD_PREFERENCE, result.operations().get(0).op());
    }
    @Test void 越界限制不能被静默夹取扩大或改写() {
        var result = DeepSeekTextIntentGenerator.parse("""
            {"operations":[{"op":"SET_CONSTRAINT","key":"maxDistanceMeters","value":1},
            {"op":"SET_CONSTRAINT","key":"budgetMax","value":5000}]}
            """);
        assertTrue(result.operations().isEmpty());
    }
    @Test void 非有限数值和小数分钟必须拒绝() {
        var result = DeepSeekTextIntentGenerator.parse("""
            {"operations":[{"op":"SET_CONSTRAINT","key":"maxDistanceMeters","value":"NaN"},
            {"op":"SET_CONSTRAINT","key":"durationMinutes","value":60.5},
            {"op":"MERGE_BIASES","values":{"PHOTOGRAPHY":"Infinity"}}]}
            """);
        assertTrue(result.operations().isEmpty());
    }
    @Test void 根节点和尾随内容不是合法操作协议() {
        for (String invalid : new String[]{"null", "[]", "{} {}"}) {
            assertThrows(AiServiceException.class, () -> DeepSeekTextIntentGenerator.parse(invalid));
        }
    }
}
