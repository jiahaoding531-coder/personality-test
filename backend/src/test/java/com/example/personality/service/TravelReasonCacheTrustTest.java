package com.example.personality.service;

import com.example.personality.ai.*;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.entity.Recommendation;
import com.example.personality.repository.RecommendationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelReasonCacheTrustTest {
    // 在本轮实现前，由旧v1生产类生成并冻结；不能在测试中用新目录重新生成“旧缓存”。
    private static final String V1_ADVANTAGE_CACHE="""
            {"version":1,"fingerprint":"8e425f852c35577541b15b586fa2d1c1ceb83e61d10585e3bdc96c248c332a73","rank":1,"evidenceIds":["8e425f85-2c355775-41b15b58-6fa2d1c1-ceb83e61-d10585e3-bdc96c24-8c332a73:1:STATIC_NATURE"],"modelSelected":true}
            """;

    @Test void 可信旧v1缓存命中时补体力提示且不重写或付费() {
        var recommendations=mock(RecommendationService.class);
        var rows=mock(RecommendationRepository.class);
        var generator=mock(TravelReasonGenerator.class);
        when(recommendations.buildReasonContext(7L,4)).thenReturn(new RecommendationService.ReasonContext(
                4,walkingInput(),Map.of(1,V1_ADVANTAGE_CACHE)));
        var row=mock(Recommendation.class);when(row.getRankNo()).thenReturn(1);when(row.getId()).thenReturn(71L);
        when(rows.findBySessionIdAndBatchNoOrderByRankNoAsc(7L,4)).thenReturn(List.of(row));
        var result=new TravelReasonService(recommendations,rows,generator).generate(7L,false,
                AiCredentials.forServer("https://example.invalid","test","fake-test-key"),4);
        assertTrue(result.cached(),"新增提醒不能让已验证的v1缓存失效");
        assertEquals("custom:test",result.provider());
        assertEquals(71L,result.reasons().get(0).recommendationId());
        assertTrue(result.reasons().get(0).reason().contains("步行强度静态标注为88/100"));
        assertTrue(result.reasons().get(0).reason().contains("入选不代表省力路线"));
        verifyNoInteractions(generator);
        verify(recommendations,never()).attachReasons(anyLong(),anyInt(),anyMap());
    }

    @Test void 不可信旧缓存本地修复也必须保留体力取舍() {
        var recommendations=mock(RecommendationService.class);
        var rows=mock(RecommendationRepository.class);
        var generator=mock(TravelReasonGenerator.class);
        when(recommendations.buildReasonContext(7L,4)).thenReturn(new RecommendationService.ReasonContext(
                4,walkingInput(),Map.of(1,"现在没什么人，而且不用排队")));
        var row=mock(Recommendation.class);when(row.getRankNo()).thenReturn(1);when(row.getId()).thenReturn(71L);
        when(rows.findBySessionIdAndBatchNoOrderByRankNoAsc(7L,4)).thenReturn(List.of(row));
        var result=new TravelReasonService(recommendations,rows,generator).generate(7L,false,
                AiCredentials.forServer("https://example.invalid","test","fake-test-key"),4);
        assertFalse(result.cached());assertEquals("local:evidence-v1",result.provider());
        assertFalse(result.reasons().get(0).reason().contains("不用排队"));
        assertTrue(result.reasons().get(0).reason().contains("入选不代表省力路线"));
        verifyNoInteractions(generator);
    }

    private TravelReasonInput walkingInput() {
        return new TravelReasonInput(null,LocalTime.NOON,240,3,null,Set.of(TravelState.TIRED),Set.of(),
                Map.of(TravelDimension.NATURE,.6),null,List.of(new TravelReasonInput.ExplainedPlace(1,"公开测试公园","NATURE","静态资料",70,
                new TravelReasonInput.Factors(.8,.7,.9,.4,1),0,60,null,null,List.of(),
                Map.of(TravelDimension.NATURE,80,TravelDimension.WALKING,88))),"7:4:[71]");
    }

    @Test void 旧自由文本缓存不能绕过验证且不得为替换缓存自动付费() {
        var recommendations = mock(RecommendationService.class);
        var rows = mock(RecommendationRepository.class);
        var generator = mock(TravelReasonGenerator.class);
        var input = new TravelReasonInput(null, LocalTime.NOON, 240, 3, null, Set.of(), Set.of(),
                Map.of(), null, List.of(new TravelReasonInput.ExplainedPlace(1, "公开测试公园", "NATURE",
                "静态资料", 70, new TravelReasonInput.Factors(.8,.7,.9,1,1),0,60,null,null,List.of())));
        when(recommendations.buildReasonContext(7L)).thenReturn(new RecommendationService.ReasonContext(
                4, input, Map.of(1, "此刻游人稀疏，无需等候即可入内")));
        var row = mock(Recommendation.class);
        when(row.getRankNo()).thenReturn(1);
        when(row.getId()).thenReturn(71L);
        when(rows.findBySessionIdAndBatchNoOrderByRankNoAsc(7L,4)).thenReturn(List.of(row));
        var response = new TravelReasonService(recommendations, rows, generator).generate(7L,false,
                AiCredentials.forServer("https://example.invalid","test","fake-test-key"));
        assertEquals(1,response.reasons().size());
        assertFalse(response.reasons().get(0).reason().contains("游人稀疏"));
        verifyNoInteractions(generator);
    }
}
