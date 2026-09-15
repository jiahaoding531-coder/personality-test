package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 反馈链路的端到端测试：👍/👎 → 画像被修正 → 下次推荐真的变了。
 *
 * <p>这一组测试守的是这个功能<b>最核心的承诺</b>：
 * 用户点了 👎 之后，下一次推荐不能还是原来那几个地方。
 * 如果只是把反馈存进库、推荐却纹丝不动，那这个按钮就是假的。
 */
class TravelFeedbackIntegrationTest extends IntegrationTestBase {

    private static final double WEST_LAKE_LAT = 30.2420;
    private static final double WEST_LAKE_LNG = 120.1400;

    // ==========================================================
    // 反馈本身
    // ==========================================================

    @Test
    @DisplayName("👎 一条推荐 → 200，并返回画像被调整的结果")
    void dislikeReturnsAdjustment() throws Exception {
        Session s = preparedSession();

        mockMvc.perform(withToken(post(
                        "/api/travel/sessions/{id}/recommendations/{rid}/feedback",
                        s.sessionId(), s.firstRecommendationId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reaction", "DISLIKE"))), s.ref()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(s.firstRecommendationId()))
                .andExpect(jsonPath("$.reaction").value("DISLIKE"))
                // ⚠️ 关键：必须至少有一个维度被调整，否则前端没法给用户任何反馈
                .andExpect(jsonPath("$.adjustments.length()").value(1))
                .andExpect(jsonPath("$.adjustments[0].questionnaireScore").isNumber())
                .andExpect(jsonPath("$.adjustments[0].effectiveScore").isNumber());
    }

    @Test
    @DisplayName("👎 会让该维度下调 10 分")
    void dislikeLowersTheAttributedDimension() throws Exception {
        Session s = preparedSession();

        JsonNode body = feedback(s, s.firstRecommendationId(), "DISLIKE");

        JsonNode adjustment = body.get("adjustments").get(0);
        int before = adjustment.get("questionnaireScore").asInt();
        int after = adjustment.get("effectiveScore").asInt();
        assertEquals(before - 10, after,
                "一次 👎 应该下调 10 分（问卷画像本身不变，变的是推荐时用的有效画像）");
    }

    @Test
    @DisplayName("同一维度连点两次 → 累计下调 20（'用得越多越准'的机制）")
    void repeatedDislikeAccumulates() throws Exception {
        Session s = preparedSession();

        feedback(s, s.firstRecommendationId(), "DISLIKE");
        JsonNode body = feedback(s, s.secondRecommendationId(), "DISLIKE");

        // 两条推荐的归因维度不一定相同，所以这里断言的是"总调整量"而不是某个维度
        int totalShift = 0;
        for (JsonNode adjustment : body.get("adjustments")) {
            totalShift += adjustment.get("questionnaireScore").asInt()
                    - adjustment.get("effectiveScore").asInt();
        }
        // 至少有一条被下调了，且不超过两条各 10 分
        assertTrue(totalShift >= 10 && totalShift <= 20,
                "两条 👎 累计下调应该在 10~20 之间，实际 " + totalShift);
    }

    @Test
    @DisplayName("改主意：👎 之后再点 👍 → 是覆盖而不是新增一条记录")
    void changingReactionOverwrites() throws Exception {
        Session s = preparedSession();

        JsonNode disliked = feedback(s, s.firstRecommendationId(), "DISLIKE");
        int dislikedAfter = disliked.get("adjustments").get(0).get("effectiveScore").asInt();

        JsonNode liked = feedback(s, s.firstRecommendationId(), "LIKE");
        int likedAfter = liked.get("adjustments").get(0).get("effectiveScore").asInt();

        assertEquals(disliked.get("adjustments").get(0).get("questionnaireScore").asInt() + 10,
                likedAfter, "改主意后应该变成上调 10，而不是两次叠加");
        assertNotEquals(dislikedAfter, likedAfter);
    }

    // ==========================================================
    // 反馈真的影响了下一次推荐
    // ==========================================================

    /**
     * <b>这条是整个功能的核心承诺。</b>
     *
     * <p>用户把一批全点了 👎，然后点"换一批"——那三个地方不能又原样回来。
     * 如果只是把反馈存进库、推荐纹丝不动，这个按钮就是假的。
     */
    @Test
    @DisplayName("【核心】把一批全否掉后换一批 → 三个都不再出现")
    void dislikedPlacesDoNotComeBack() throws Exception {
        Session s = preparedSession();

        JsonNode first = recommend(s.ref(), false);
        Set<String> disliked = new HashSet<>();
        for (JsonNode place : first.get("places")) {
            disliked.add(place.get("name").asString());
            feedback(s, place.get("recommendationId").asLong(), "DISLIKE");
        }
        assertEquals(3, disliked.size());

        JsonNode next = recommend(s.ref(), true);

        assertTrue(next.get("places").size() > 0, "杭州还有别的候选，不该是空的");
        for (JsonNode place : next.get("places")) {
            assertFalse(disliked.contains(place.get("name").asString()),
                    "被否掉的地方不该再出现：" + place.get("name").asString());
        }
    }

    @Test
    @DisplayName("excludeSeen=true（'换一批'）会排除看过的地方，不传则可能重复")
    void excludeSeenSkipsAlreadySeenPlaces() throws Exception {
        Session s = preparedSession();

        JsonNode first = recommend(s.ref(), false);
        String firstName = first.get("places").get(0).get("name").asString();

        JsonNode next = recommend(s.ref(), true);

        for (JsonNode place : next.get("places")) {
            assertNotEquals(firstName, place.get("name").asString(),
                    "换一批不该再给出刚看过的那个地方");
        }
        // 新的一批，批次号要递增
        assertEquals(first.get("batchNo").asInt() + 1, next.get("batchNo").asInt());
    }

    @Test
    @DisplayName("点过 👍 的地方不会被'换一批'排除——用户喜欢它，应该还能被推荐到")
    void likedPlacesAreNotExcluded() throws Exception {
        Session s = preparedSession();

        JsonNode first = recommend(s.ref(), false);
        JsonNode favourite = first.get("places").get(0);
        String favouriteName = favourite.get("name").asString();
        feedback(s, favourite.get("recommendationId").asLong(), "LIKE");

        // 连换几批，把其余候选都排掉
        for (int i = 0; i < 3; i++) {
            recommend(s.ref(), true);
        }

        JsonNode next = recommend(s.ref(), true);
        boolean favouriteStillThere = false;
        for (JsonNode place : next.get("places")) {
            if (favouriteName.equals(place.get("name").asString())) {
                favouriteStillThere = true;
            }
        }
        assertTrue(favouriteStillThere,
                "点过 👍 的地点不该被排除——" + favouriteName + " 应该还能出现");
    }

    // ==========================================================
    // 越权
    // ==========================================================

    @Test
    @DisplayName("拿自己的会话去反馈别人的推荐 → 404（不是 403）")
    void cannotFeedbackSomeoneElsesRecommendation() throws Exception {
        Session mine = preparedSession();
        Session theirs = preparedSession();

        mockMvc.perform(withToken(post(
                        "/api/travel/sessions/{id}/recommendations/{rid}/feedback",
                        mine.sessionId(), theirs.firstRecommendationId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reaction", "DISLIKE"))), mine.ref()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("不带令牌去反馈 → 404")
    void feedbackRequiresTheSessionToken() throws Exception {
        Session s = preparedSession();

        mockMvc.perform(post("/api/travel/sessions/{id}/recommendations/{rid}/feedback",
                        s.sessionId(), s.firstRecommendationId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reaction", "DISLIKE"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("reaction 取值非法 → 400")
    void invalidReactionIsRejected() throws Exception {
        Session s = preparedSession();

        mockMvc.perform(withToken(post(
                        "/api/travel/sessions/{id}/recommendations/{rid}/feedback",
                        s.sessionId(), s.firstRecommendationId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reaction", "MAYBE"))), s.ref()))
                .andExpect(status().isBadRequest());
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================

    /** 一次"已经答完题、提交过、并且拿到过一批推荐"的会话。 */
    private record Session(TestSessionRef ref, long sessionId, long firstRecommendationId,
                           long secondRecommendationId) {
    }

    private Session preparedSession() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        // 全都答"说不好"（3 分）→ 8 个维度都是 50。
        // ⚠️ 刻意不用 5 分：那样所有维度都是 100，👍 一上调就撞到上限被夹住，
        // 反而测不出"上调 10 分"这件事。选中间值让两个方向都有余量。
        answerAllTravelQuestions(ref, 3, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        JsonNode first = recommend(ref, false);
        long firstId = first.get("places").get(0).get("recommendationId").asLong();
        long secondId = first.get("places").get(1).get("recommendationId").asLong();
        return new Session(ref, ref.id(), firstId, secondId);
    }

    private JsonNode recommend(TestSessionRef ref, boolean excludeSeen) throws Exception {
        Map<String, Object> body = excludeSeen
                ? Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG, "excludeSeen", true)
                : Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG);

        MvcResult result = mockMvc.perform(withToken(post(
                        "/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)), ref))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private JsonNode feedback(Session s, long recommendationId, String reaction) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post(
                        "/api/travel/sessions/{id}/recommendations/{rid}/feedback",
                        s.sessionId(), recommendationId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reaction", reaction))), s.ref()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
