package com.example.personality;

import com.example.personality.ai.TravelReasonGenerator;
import com.example.personality.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.example.personality.ai.TravelReasonInput;
import com.example.personality.ai.TravelReasonEvidence;
import com.example.personality.ai.AiCredentials;
import com.example.personality.service.TravelReasonService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.ai.enabled=true","app.ai.api-key=fake-test-key","app.ai.model=test"})
class TravelReasonBatchTrustIntegrationTest extends IntegrationTestBase {
    @Autowired RecommendationService recommendations;
    @Autowired com.example.personality.repository.RecommendationRepository rows;
    @Autowired TravelReasonService reasonService;
    static final AtomicReference<Consumer<TravelReasonInput>> hook=new AtomicReference<>(input->{});

    @Test void 新反馈不得重建旧批次的解释依据() throws Exception {
        var login=registerAndLogin(uniqueUsername("reason_snapshot"),"password123");
        var session=prepare(login);
        var before=recommendations.buildReasonContext(session.id()).input();
        long recommendationId=rows.findBySessionIdAndBatchNoOrderByRankNoAsc(session.id(),1).get(0).getId();
        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/{rid}/feedback",session.id(),recommendationId)
                .session(login).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"DISLIKE\"}"))
                .andExpect(status().isOk());
        var after=recommendations.buildReasonContext(session.id()).input();
        assertEquals(before.places(),after.places(),"没有历史偏好快照就不能混入新反馈重算旧推荐依据");
    }

    @Test void 解释请求必须绑定用户正在看的批次而不是后端最新批次() throws Exception {
        var login=registerAndLogin(uniqueUsername("reason_batch"),"password123");
        var session=prepare(login);
        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations",session.id()).session(login).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(request(60)))).andExpect(status().isOk());
        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/reasons",session.id())
                .session(login).with(csrf()).param("batchNo","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.batchNo").value(1));
    }

    @Test void 不能借另一个会话的批次号解释本会话不存在的批次() throws Exception {
        var login=registerAndLogin(uniqueUsername("reason_scope"),"password123");
        var session=prepare(login);
        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/reasons",session.id())
                .session(login).with(csrf()).param("batchNo","2"))
                .andExpect(status().isNotFound());
    }

    @Test void 非法批次号拒绝且不触发模型() throws Exception {
        var login=registerAndLogin(uniqueUsername("reason_badbatch"),"password123");
        var session=prepare(login);
        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/reasons",session.id())
                .session(login).with(csrf()).param("batchNo","0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void 模型等待期间换批并反馈旧解释仍只写回原批次() throws Exception {
        // 此测试故意不用基类的大事务，线程之间通过真实PostgreSQL提交观察状态。
        // 仅在独立测试库留下合成账号/会话，不清理任何已有用户数据。
        var login=registerAndLogin(uniqueUsername("reason_race"),"password123");
        var session=prepare(login);
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        hook.set(input->{
            if(input.remainingMinutes()!=240) return;
            started.countDown();
            try {if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("模型替身等待超时");}
            catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}
        });
        try {
            var credentials=AiCredentials.forServer("https://example.invalid","test","fake-test-key");
            var old=executor.submit(()->reasonService.generate(session.id(),false,credentials,1));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            mockMvc.perform(post("/api/travel/sessions/{id}/recommendations",session.id()).session(login).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(json(request(60)))).andExpect(status().isOk());
            long recommendationId=rows.findBySessionIdAndBatchNoOrderByRankNoAsc(session.id(),2).get(0).getId();
            mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/{rid}/feedback",session.id(),recommendationId)
                    .session(login).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"DISLIKE\"}"))
                    .andExpect(status().isOk());
            var current=reasonService.generate(session.id(),false,credentials,2);
            release.countDown();
            var previous=old.get(5,TimeUnit.SECONDS);
            assertEquals(1,previous.batchNo());assertEquals(2,current.batchNo());
            assertTrue(previous.reasons().stream().allMatch(r->r.reason().contains("240分钟")));
            assertTrue(current.reasons().stream().allMatch(r->r.reason().contains("60分钟")));
            for(int batch:List.of(1,2)) {
                var context=recommendations.buildReasonContext(session.id(),batch);
                var catalog=TravelReasonEvidence.from(context.input());
                for(var row:rows.findBySessionIdAndBatchNoOrderByRankNoAsc(session.id(),batch)) {
                    assertTrue(catalog.readCache(row.getRankNo(),row.getReason()).isPresent());
                }
            }
        } finally {release.countDown();hook.set(input->{});executor.shutdownNow();}
    }

    @Test void 旧自由文本缓存真实落库后只做本地修复() throws Exception {
        var login=registerAndLogin(uniqueUsername("reason_oldcache"),"password123");
        var session=prepare(login);
        var row=rows.findBySessionIdAndBatchNoOrderByRankNoAsc(session.id(),1).get(0);
        row.attachReason("现场游人稀疏，直接进园无需等候");rows.saveAndFlush(row);
        hook.set(input->{throw new AssertionError("旧缓存修复不得调用模型");});
        try {
            var response=mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/reasons",session.id())
                    .session(login).with(csrf()).param("batchNo","1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("local:evidence-v1")).andReturn();
            var body=objectMapper.readTree(response.getResponse().getContentAsByteArray());
            assertFalse(body.toString().contains("游人稀疏"));
            var catalog=TravelReasonEvidence.from(recommendations.buildReasonContext(session.id(),1).input());
            assertTrue(catalog.readCache(row.getRankNo(),row.getReason()).isPresent());
        } finally {hook.set(input->{});}
    }

    private TestSessionRef prepare(MockHttpSession login) throws Exception {
        var session=createTravelSession(login);
        answerAllTravelQuestions(session,4,login);
        mockMvc.perform(post("/api/travel/sessions/{id}/submit",session.id()).session(login).with(csrf())).andExpect(status().isOk());
        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations",session.id()).session(login).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(request(240)))).andExpect(status().isOk());
        return session;
    }
    private Map<String,Object> request(int minutes) {
        return Map.of("latitude",30.242,"longitude",120.14,"remainingMinutes",minutes,"autoInfer",false);
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary Clock fixedClock() {return Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"),ZoneOffset.ofHours(8));}
        @Bean @Primary TravelReasonGenerator fixtureGenerator() {
            return (credentials,input)-> {hook.get().accept(input);return List.of(new TravelReasonGenerator.RankedReason(1,"模型任意自由文本不可信"));};
        }
    }
}
