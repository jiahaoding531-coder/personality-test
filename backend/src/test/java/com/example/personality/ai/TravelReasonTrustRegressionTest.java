package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.exception.AiServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 外部模型只用替身；断言的是实际生成器的输出，绝不调用付费接口。 */
class TravelReasonTrustRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"人群没那么挤", "此刻游人稀疏", "现场几乎看不到游客", "无需等候即可入内",
            "等候队伍比另一处短", "不用排队", "现在正在营业", "此时可以直接进园",
            "今天一路畅通无阻", "眼下天气舒适", "这里现在格外清静"})
    void 自由文本无论如何措辞都只能确定性降级(String unsupported) {
        var client = mock(DeepSeekChatClient.class);
        when(client.complete(any(), anyString(), anyString(), eq(true)))
                .thenReturn("{\"reasons\":[{\"rank\":1,\"reason\":\"" + unsupported + "\"}]}");
        var generator = new DeepSeekTravelReasonGenerator(client, new TravelReasonPromptBuilder());
        var result = assertDoesNotThrow(() -> generator.generateReasons(credentials(), input()));
        assertEquals(1, result.size());
        assertFalse(result.get(0).reason().contains(unsupported), "模型原文不得进入展示或缓存");
        assertTrue(result.get(0).reason().contains("本批次"), "必须仍提供有依据的降级解释");
    }

    @Test void 非法依据ID不能导致空白解释或透传自由文本() {
        var client = mock(DeepSeekChatClient.class);
        when(client.complete(any(), anyString(), anyString(), eq(true)))
                .thenReturn("{\"selections\":[{\"rank\":1,\"evidenceIds\":[\"LIVE_CROWD\"]}]}");
        var result = assertDoesNotThrow(() -> new DeepSeekTravelReasonGenerator(client,
                new TravelReasonPromptBuilder()).generateReasons(credentials(), input()));
        assertEquals(1, result.size());
        assertFalse(result.get(0).reason().contains("LIVE_CROWD"));
    }

    @Test void 超时后提供确定性理由且不自动重试() {
        var client = mock(DeepSeekChatClient.class);
        when(client.complete(any(), anyString(), anyString(), eq(true)))
                .thenThrow(new AiServiceException("模拟模型超时"));
        var generator = new DeepSeekTravelReasonGenerator(client, new TravelReasonPromptBuilder());
        var first = assertDoesNotThrow(() -> generator.generateReasons(credentials(), input()));
        assertFalse(first.isEmpty());
        verify(client, times(1)).complete(any(), anyString(), anyString(), eq(true));
    }

    static AiCredentials credentials() {
        return AiCredentials.forServer("https://example.invalid", "test-model", "fake-test-key");
    }
    static TravelReasonInput input() {
        return new TravelReasonInput(null, LocalTime.NOON, 240, 3, null,
                Set.of(TravelState.TIRED), Set.of(), Map.of(TravelDimension.NATURE, .5), null,
                List.of(new TravelReasonInput.ExplainedPlace(1, "公开测试公园", "NATURE", "静态地点资料",
                        70, new TravelReasonInput.Factors(.8, .7, .9, .8, 1),
                        0, 60, null, null, List.of())));
    }
}
