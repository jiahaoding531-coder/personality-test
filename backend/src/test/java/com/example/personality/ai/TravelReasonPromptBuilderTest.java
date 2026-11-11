package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 提示词只是第一道约束；真正的ID验证由解析与缓存测试负责。 */
class TravelReasonPromptBuilderTest {
    private final TravelReasonPromptBuilder builder=new TravelReasonPromptBuilder();
    @Test void 模型只能选择ID而不是编写用户可见事实() {
        assertTrue(builder.systemPrompt().contains("evidenceIds"));
        assertTrue(builder.systemPrompt().contains("不得返回reason"));
        assertTrue(builder.systemPrompt().toLowerCase().contains("json"));
    }
    @Test void 依据明确区分静态偏好条件和已保存评分() {
        var input=TravelReasonEvidenceTest.input("user1:batch2",Map.of(TravelDimension.NATURE,.6),Set.of(TravelState.TIRED));
        var prompt=builder.userPrompt(input);
        for(var kind:TravelReasonEvidence.Kind.values()) assertTrue(prompt.contains(kind.name()));
    }
    @Test void 只发送白名单依据不发送宣传描述或精确街道() {
        var input=TravelReasonEvidenceTest.input("user1:batch2",Map.of(),Set.of());
        var prompt=builder.userPrompt(input);
        assertFalse(prompt.contains(input.places().get(0).description()));
        assertFalse(prompt.contains("user1"));
        assertFalse(prompt.contains("batch2"));
        var root=JsonMapper.builder().build().readTree(prompt);
        assertEquals(1,root.path("places").size());
        assertEquals("公开测试公园",root.path("places").get(0).path("name").asString());
    }
    @Test void 未知实时信息没有可选择的事实ID() {
        var prompt=builder.userPrompt(TravelReasonTrustRegressionTest.input());
        var root=JsonMapper.builder().build().readTree(prompt);
        assertTrue(root.path("unknownInformation").toString().contains("实时人流"));
        var evidence=root.path("places").get(0).path("evidence").toString();
        assertFalse(evidence.contains("正在营业"));
        assertFalse(evidence.contains("天气舒适"));
    }
}
