package com.example.personality.ai;

import com.example.personality.exception.AiServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大模型返回内容的解析测试。
 *
 * <p><b>不联网、不需要 Key、不花钱</b>——{@code parse} 是纯函数。
 *
 * <p>它拦的是哪类问题：<b>模型没按约定格式返回</b>。这类事一定会发生
 * （模型是不受控的），所以解析代码必须对"格式不对"有准备。
 * 而这些分支如果只靠真实调用去撞，一是慢、二是费钱、三是撞不全。
 */
class DeepSeekTravelReasonGeneratorTest {

    @Test
    @DisplayName("正常返回：解析出名次和理由")
    void parsesOrdinaryResponse() {
        String content = """
                {"reasons":[{"rank":1,"reason":"就在你旁边，走两步就到。"},\
                {"rank":2,"reason":"菜是本地口味，离得也不算远。"}]}
                """;

        List<TravelReasonGenerator.RankedReason> reasons =
                DeepSeekTravelReasonGenerator.parse(content, 2);

        assertEquals(2, reasons.size());
        assertEquals(1, reasons.get(0).rank());
        assertEquals("就在你旁边，走两步就到。", reasons.get(0).reason());
        assertEquals(2, reasons.get(1).rank());
    }

    @Test
    @DisplayName("⚠️ 裹了 ```json 代码块的也能剥开")
    void stripsCodeFence() {
        // 开了 response_format=json_object 理论上不该出现，但"理论上"
        // 这三个字在大模型身上一向不太可靠。剥一层壳的成本是几行代码，
        // 收益是这类失败从"整批作废"变成"照常可用"。
        String content = """
                ```json
                {"reasons":[{"rank":1,"reason":"很近。"}]}
                ```
                """;

        List<TravelReasonGenerator.RankedReason> reasons =
                DeepSeekTravelReasonGenerator.parse(content, 1);

        assertEquals(1, reasons.size());
        assertEquals("很近。", reasons.get(0).reason());
    }

    @Test
    @DisplayName("rank 给成字符串 \"1\" 也能认")
    void acceptsRankAsString() {
        String content = """
                {"reasons":[{"rank":"1","reason":"很近。"}]}
                """;

        List<TravelReasonGenerator.RankedReason> reasons =
                DeepSeekTravelReasonGenerator.parse(content, 1);

        assertEquals(1, reasons.get(0).rank());
    }

    @Test
    @DisplayName("名次对不上时只记日志，不丢整批")
    void partialResultIsKept() {
        // 模型偶尔会漏掉某个名次。那种情况下宁可少给一条，
        // 也不该让整批作废——用户至少还能看到另外两条。
        String content = """
                {"reasons":[{"rank":1,"reason":"很近。"}]}
                """;

        List<TravelReasonGenerator.RankedReason> reasons =
                DeepSeekTravelReasonGenerator.parse(content, 3);

        assertEquals(1, reasons.size(), "少给的不该让已给的那条也作废");
    }

    @Test
    @DisplayName("名次非法或理由为空的那一条被丢掉，其余保留")
    void skipsInvalidEntries() {
        String content = """
                {"reasons":[
                  {"rank":1,"reason":"很近。"},
                  {"rank":0,"reason":"名次从 1 开始，0 是错的"},
                  {"rank":2,"reason":"   "},
                  {"rank":3,"reason":"这条是好的。"}
                ]}
                """;

        List<TravelReasonGenerator.RankedReason> reasons =
                DeepSeekTravelReasonGenerator.parse(content, 3);

        assertEquals(2, reasons.size());
        assertEquals(1, reasons.get(0).rank());
        assertEquals(3, reasons.get(1).rank());
    }

    // ==========================================================
    // 失败路径：必须抛，不能静默返回空
    // ==========================================================

    @Test
    @DisplayName("【关键】一条都没解析出来时必须报错，而不是返回空列表")
    void emptyResultThrows() {
        // ⚠️ 这条是刻意的。返回空列表的话，上层会以为"生成成功了，只是没内容"，
        // 于是把空结果当成正常结果**缓存起来**——之后每次都命中这个空缓存，
        // 用户永远看不到理由，而且没有任何地方报错。
        String content = """
                {"reasons":[]}
                """;

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> DeepSeekTravelReasonGenerator.parse(content, 3));
        assertTrue(e.getMessage().contains("没有返回任何可用的推荐理由"),
                "报错信息要说清是什么问题，实际是：" + e.getMessage());
    }

    @Test
    @DisplayName("不是合法 JSON 时报错")
    void invalidJsonThrows() {
        assertThrows(AiServiceException.class,
                () -> DeepSeekTravelReasonGenerator.parse("我觉得吧，西湖挺不错的。", 3));
    }

    @Test
    @DisplayName("JSON 合法但没有 reasons 字段时报错")
    void missingReasonsFieldThrows() {
        // 模型有时候会自作主张换个键名（"推荐理由"、"result"...），
        // 那种情况必须报错，而不是当成"没有理由"
        assertThrows(AiServiceException.class,
                () -> DeepSeekTravelReasonGenerator.parse("{\"data\":[]}", 3));
    }
}
