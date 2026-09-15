package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.exception.AiServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本解析的容错测试。
 *
 * <p><b>不联网、不需要 Key、不花钱。</b>
 *
 * <p>这个类测的全是"模型没按约定返回"的情况——而这类事一定会发生，
 * 因为模型是不受控的。原则是<b>坏的那条丢掉，好的留下</b>：
 * 用户说了五件事，能听懂三件就先给三件，而不是整批作废。
 */
class DeepSeekTextIntentGeneratorTest {

    @Test
    @DisplayName("正常返回：状态、倾向、数字、复述都解析出来")
    void parsesOrdinaryResponse() {
        String json = """
                {
                  "states": ["TIRED", "QUIET"],
                  "biases": {"HIDDEN_GEMS": 0.6},
                  "remainingMinutes": 60,
                  "maxDistanceKm": 2,
                  "maxTicketPrice": 50,
                  "unrecognized": ["想找个能带狗的地方"],
                  "summary": "你有点累了，想找个安静的地方待一会儿"
                }
                """;

        TextIntentResult result = DeepSeekTextIntentGenerator.parse(json);

        assertEquals(2, result.states().size());
        assertTrue(result.states().contains(TravelState.TIRED));
        assertTrue(result.states().contains(TravelState.QUIET));
        assertEquals(0.6, result.biases().get(TravelDimension.HIDDEN_GEMS), 1e-9);
        assertEquals(60, result.remainingMinutes());
        assertEquals(2.0, result.maxDistanceKm(), 1e-9);
        assertEquals(50, result.maxTicketPrice());
        assertEquals(1, result.unrecognized().size());
        assertEquals("你有点累了，想找个安静的地方待一会儿", result.summary());
        assertTrue(result.hasAnythingUsable());
    }

    @Test
    @DisplayName("用户没提的槽位保持 null——不编造")
    void absentSlotsStayNull() {
        String json = """
                {"states": ["HUNGRY"], "biases": {}, "remainingMinutes": null,
                 "maxDistanceKm": null, "maxTicketPrice": null,
                 "unrecognized": [], "summary": "你饿了"}
                """;

        TextIntentResult result = DeepSeekTextIntentGenerator.parse(json);

        // ⚠️ 这一条比看起来重要：编一个"预算 50 元"出来，
        //    用户会看到一个自己从没要求过的条件被悄悄施加了
        assertNull(result.remainingMinutes());
        assertNull(result.maxDistanceKm());
        assertNull(result.maxTicketPrice());
    }

    // ==========================================================
    // 容错：坏的那条丢掉，好的留下
    // ==========================================================

    @Test
    @DisplayName("【核心】编出来的状态名被丢掉，不是'退回默认状态'")
    void unknownStatesAreDroppedNotDefaulted() {
        String json = """
                {"states": ["TIRED", "SLEEPY", "BORED"]}
                """;

        TextIntentResult result = DeepSeekTextIntentGenerator.parse(json);

        // 把 SLEEPY 当成 TIRED 处理的话，用户看到的是"系统理解成你累了"——
        // 而他从没说过累。丢掉的代价只是少一个条件，
        // 编一个的代价是**改变了他没要求的事**。
        assertEquals(1, result.states().size());
        assertEquals(TravelState.TIRED, result.states().iterator().next());
    }

    @Test
    @DisplayName("编出来的维度名被丢掉")
    void unknownDimensionsAreDropped() {
        String json = """
                {"biases": {"CROWD_TOLERANCE": -0.5, "FOODIE": 0.8, "VIBES": 0.3}}
                """;

        TextIntentResult result = DeepSeekTextIntentGenerator.parse(json);

        assertEquals(1, result.biases().size());
        assertEquals(-0.5, result.biases().get(TravelDimension.CROWD_TOLERANCE), 1e-9);
    }

    @Test
    @DisplayName("越界的数值被夹进合法区间，而不是原样返回")
    void outOfRangeNumbersAreClamped() {
        String json = """
                {"remainingMinutes": 99999, "maxDistanceKm": 5000, "maxTicketPrice": -20}
                """;

        TextIntentResult result = DeepSeekTextIntentGenerator.parse(json);

        // 不夹的话，前端原样发出去会被推荐接口的 @Min/@Max 挡下变成 400，
        // 而用户看到的只是"推荐失败了"，完全想不到是 AI 多说了半句
        assertEquals(1440, result.remainingMinutes(), "分钟数要夹到上限");
        assertEquals(50.0, result.maxDistanceKm(), 1e-9, "距离要夹到上限");
        assertEquals(0, result.maxTicketPrice(), "负数要夹到下限");
    }

    @Test
    @DisplayName("单条倾向越界时被夹到 [-1, 1]——这是防负分的第一道")
    void outOfRangeBiasesAreClamped() {
        String json = """
                {"biases": {"CROWD_TOLERANCE": -9.5, "PHOTOGRAPHY": 3.0}}
                """;

        TextIntentResult result = DeepSeekTextIntentGenerator.parse(json);

        assertEquals(-1.0, result.biases().get(TravelDimension.CROWD_TOLERANCE), 1e-9);
        assertEquals(1.0, result.biases().get(TravelDimension.PHOTOGRAPHY), 1e-9);
    }

    @Test
    @DisplayName("数字被写成字符串也认")
    void acceptsNumbersAsStrings() {
        String json = """
                {"remainingMinutes": "90", "maxDistanceKm": "3.5"}
                """;

        TextIntentResult result = DeepSeekTextIntentGenerator.parse(json);

        assertEquals(90, result.remainingMinutes());
        assertEquals(3.5, result.maxDistanceKm(), 1e-9);
    }

    @Test
    @DisplayName("裹了 ```json 代码块的也能剥开")
    void stripsCodeFence() {
        String json = """
                ```json
                {"states": ["PHOTO"], "summary": "你想拍点照"}
                ```
                """;

        assertEquals(1, DeepSeekTextIntentGenerator.parse(json).states().size());
    }

    @Test
    @DisplayName("完全听不懂时返回空结果，而不是抛异常")
    void gibberishReturnsEmptyNotException() {
        // "这句我听不懂"是一个正常的结果，不是故障。
        // 前端会据此提示用户换个说法——和"上游挂了"该给用户看不同的话。
        TextIntentResult result = DeepSeekTextIntentGenerator.parse(
                "{\"states\": [], \"biases\": {}, \"summary\": \"\"}");

        assertFalse(result.hasAnythingUsable());
    }

    // ==========================================================
    // 失败路径
    // ==========================================================

    @Test
    @DisplayName("整个 JSON 坏掉时才抛 502")
    void invalidJsonThrows() {
        // ⚠️ 这里必须抛，不能返回空结果：空结果会被前端当成"这句我听不懂"，
        //    而实际上是我们没解析出来——这两件事该给用户看不同的话
        assertThrows(AiServiceException.class,
                () -> DeepSeekTextIntentGenerator.parse("用户说他累了。"));
    }

    @Test
    @DisplayName("字段类型不对也不炸，只是那一项读不出来")
    void wrongTypesAreTolerated() {
        String json = """
                {"states": "TIRED", "biases": [1,2], "unrecognized": "带狗",
                 "remainingMinutes": {"a": 1}, "summary": 123}
                """;

        TextIntentResult result = DeepSeekTextIntentGenerator.parse(json);

        assertTrue(result.states().isEmpty(), "states 不是数组就当没有");
        assertTrue(result.biases().isEmpty(), "biases 不是对象就当没有");
        assertTrue(result.unrecognized().isEmpty());
        assertNull(result.remainingMinutes());
    }
}
