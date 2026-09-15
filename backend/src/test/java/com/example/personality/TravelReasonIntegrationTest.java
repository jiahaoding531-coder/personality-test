package com.example.personality;

import com.example.personality.ai.TravelReasonGenerator;
import com.example.personality.ai.TravelReasonInput;
import com.example.personality.entity.Recommendation;
import com.example.personality.repository.RecommendationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 推荐理由的端到端测试。
 *
 * <h2>为什么必须用假的生成器</h2>
 *
 * <p>真调一次大模型要几秒、要花钱，而且<b>输出不可复现</b>——
 * 没法断言"它应该写什么"。这个测试要验证的是<b>编排</b>：
 * 该调的时候调、该缓存的时候不调、该拦住的时候拦住。
 * 那正是用假实现能精确断言的部分（比如"上游被调了几次"，
 * 这在真实环境里根本看不见）。
 *
 * <p>提示词和解析逻辑由另外两个纯逻辑测试覆盖
 * （{@code TravelReasonPromptBuilderTest}、{@code DeepSeekTravelReasonGeneratorTest}），
 * 两边合起来，从"喂什么给模型"到"结果怎么落库"整条链路都有测试。
 *
 * <h2>⚠️ 这个测试测不出事务边界</h2>
 *
 * <p>基类上有 {@code @Transactional}，整个测试方法跑在一个事务里。
 * 所以"AI 调用是不是真的在事务外"这件事，<b>这里验证不了</b>——
 * 在一个大事务里，里面套不套小事务看不出区别。
 * 那是 {@code TravelReasonService} 类注释和代码审查负责的事。
 */
class TravelReasonIntegrationTest extends IntegrationTestBase {

    private static final double WEST_LAKE_LAT = 30.2420;
    private static final double WEST_LAKE_LNG = 120.1400;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @BeforeEach
    void resetFake() {
        FakeReasonGenerator.calls.set(0);
        FakeReasonGenerator.countToReturn = 3;
    }

    // ==========================================================
    // 核心路径
    // ==========================================================

    @Test
    @DisplayName("【核心】生成理由 → 返回、并且真的落库了")
    void generatesAndPersistsReasons() throws Exception {
        TestSessionRef ref = prepareSession();

        MvcResult result = mockMvc.perform(reasonRequest(ref, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(ref.id()))
                .andExpect(jsonPath("$.batchNo").value(1))
                .andExpect(jsonPath("$.provider").value("fake"))
                // 第一次生成，不是缓存
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.reasons.length()").value(3))
                .andExpect(jsonPath("$.reasons[0].rank").value(1))
                // ⚠️ 每条都要带 recommendationId——前端靠它把理由贴到对应的卡片上。
                // 名次只在"一批之内"有意义，跨请求对不上任何东西。
                .andExpect(jsonPath("$.reasons[0].recommendationId").isNumber())
                .andReturn();

        // 落库这一条单独查——接口返回对了但没存下来，是很容易漏的一种 bug
        assertEquals(1, FakeReasonGenerator.calls.get(), "应该调了一次生成器");

        List<Recommendation> saved = recommendationRepository
                .findBySessionIdAndBatchNoOrderByRankNoAsc(ref.id(), 1);
        assertEquals(3, saved.size());
        for (Recommendation row : saved) {
            assertTrue(row.getReason() != null && !row.getReason().isBlank(),
                    "第 " + row.getRankNo() + " 名的理由没有落库");
        }
        assertTrue(saved.get(0).getReason().contains("第1名"), "落库的内容应该是生成器给的那句");
    }

    @Test
    @DisplayName("【核心】第二次请求直接吃缓存，不再调用大模型")
    void secondCallIsServedFromCache() throws Exception {
        TestSessionRef ref = prepareSession();

        mockMvc.perform(reasonRequest(ref, false)).andExpect(status().isOk());
        assertEquals(1, FakeReasonGenerator.calls.get());

        // 用户误点两次不该白花两次 token，也不该看到两段不一样的话
        mockMvc.perform(reasonRequest(ref, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.reasons.length()").value(3));

        assertEquals(1, FakeReasonGenerator.calls.get(), "第二次不该再调大模型");
    }

    @Test
    @DisplayName("regenerate=true 会强制重跑，不吃缓存")
    void regenerateForcesAnotherCall() throws Exception {
        TestSessionRef ref = prepareSession();

        mockMvc.perform(reasonRequest(ref, false)).andExpect(status().isOk());
        mockMvc.perform(reasonRequest(ref, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        assertEquals(2, FakeReasonGenerator.calls.get());
    }

    @Test
    @DisplayName("只生成了一部分时，下次请求会补全（不把半成品当缓存）")
    void partialResultIsNotTreatedAsCache() throws Exception {
        TestSessionRef ref = prepareSession();

        // 模型偶尔会漏掉某个名次
        FakeReasonGenerator.countToReturn = 2;
        mockMvc.perform(reasonRequest(ref, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons.length()").value(2));

        // 还有地点没有理由 → 不算缓存命中，应该再试一次
        FakeReasonGenerator.countToReturn = 3;
        mockMvc.perform(reasonRequest(ref, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons.length()").value(3));

        assertEquals(2, FakeReasonGenerator.calls.get());
    }

    // ==========================================================
    // 访问控制：AI 调用是花钱的，这个尤其不能漏
    // ==========================================================

    @Test
    @DisplayName("【安全】别人的会话拿不到理由")
    void cannotGenerateReasonsForSomeoneElsesSession() throws Exception {
        TestSessionRef victim = prepareSession();
        TestSessionRef attacker = prepareSession();

        // 匿名会话之间靠 accessToken 区分；拿自己的 token 去请求别人的会话
        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/reasons", victim.id())
                        .with(csrf())
                        .header(SESSION_TOKEN_HEADER, attacker.token()))
                // ⚠️ 404 而不是 403：403 会泄露"这个会话存在"
                .andExpect(status().isNotFound());

        assertEquals(0, FakeReasonGenerator.calls.get(),
                "被拦下的请求绝不该走到调用大模型那一步——那一步是要花钱的");
    }

    @Test
    @DisplayName("没有访问令牌的匿名请求也拿不到")
    void anonymousRequestWithoutTokenIsRejected() throws Exception {
        TestSessionRef victim = prepareSession();

        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/reasons", victim.id())
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertEquals(0, FakeReasonGenerator.calls.get());
    }

    @Test
    @DisplayName("还没推荐过就请求理由 → 404，且不调用大模型")
    void reasonsBeforeAnyRecommendationIsNotFound() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 4, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        // 建了会话、也提交了，但从来没请求过推荐
        mockMvc.perform(reasonRequest(ref, false))
                .andExpect(status().isNotFound());

        assertEquals(0, FakeReasonGenerator.calls.get());
    }

    // ==========================================================
    // 辅助
    // ==========================================================

    /** 建会话 → 答题 → 提交 → 拿一次推荐。返回的会话已经有一批推荐了。 */
    private TestSessionRef prepareSession() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 4, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(
                        post("/api/travel/sessions/{id}/recommendations", ref.id())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of(
                                        "latitude", WEST_LAKE_LAT,
                                        "longitude", WEST_LAKE_LNG))),
                        ref))
                .andExpect(status().isOk());

        return ref;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reasonRequest(
            TestSessionRef ref, boolean regenerate) {
        return withToken(
                post("/api/travel/sessions/{id}/recommendations/reasons", ref.id())
                        .with(csrf())
                        .param("regenerate", String.valueOf(regenerate)),
                ref);
    }

    // ==========================================================
    // 测试替身
    // ==========================================================

    /**
     * 用 {@code @Primary} 盖过 {@code StubTravelReasonGenerator}。
     *
     * <p>（Spring 默认禁止 Bean 定义覆盖，所以是加一个优先级更高的候选，
     * 而不是覆盖原定义。{@code TravelWeatherIntegrationTest} 也是这么做的。）
     */
    @TestConfiguration
    static class FakeReasonConfiguration {

        @Bean
        @Primary
        TravelReasonGenerator fakeReasonGenerator() {
            return new FakeReasonGenerator();
        }
    }

    /** 数调用次数、能控制返回几条的假生成器。 */
    static final class FakeReasonGenerator implements TravelReasonGenerator {

        static final AtomicInteger calls = new AtomicInteger();

        /** 返回几条。设成小于地点数可以模拟"模型漏了一个名次"。 */
        static int countToReturn = 3;

        @Override
        public List<RankedReason> generateReasons(TravelReasonInput input) {
            calls.incrementAndGet();
            List<RankedReason> reasons = new ArrayList<>();
            int count = Math.min(countToReturn, input.places().size());
            for (int i = 0; i < count; i++) {
                reasons.add(new RankedReason(i + 1, "第" + (i + 1) + "名的理由"));
            }
            return reasons;
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
