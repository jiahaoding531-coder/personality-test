package com.example.personality.controller;

import com.example.personality.dto.AnswersSavedResponse;
import com.example.personality.dto.FeedbackRequest;
import com.example.personality.dto.FeedbackResponse;
import com.example.personality.dto.RecommendationRequest;
import com.example.personality.dto.RecommendationResponse;
import com.example.personality.dto.SessionResponse;
import com.example.personality.dto.SubmitAnswersRequest;
import com.example.personality.dto.TravelProfileResponse;
import com.example.personality.entity.TestSession;
import com.example.personality.security.AppUserPrincipal;
import com.example.personality.security.SessionAccessGuard;
import com.example.personality.service.RecommendationService;
import com.example.personality.service.TestSessionService;
import com.example.personality.service.TravelProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅行偏好测试接口 —— TravelMind V0 的主干链路。
 *
 * <p>链路是：建会话 → 答 8 道题 → 提交算画像 → 带上定位拿 Top 3 推荐。
 *
 * <h2>为什么用独立的 /api/travel 前缀，而不是复用 /api/test-sessions</h2>
 *
 * <p>两者底层共用同一张 {@code test_sessions} 表和同一套会话机制，但<b>返回结构不同</b>：
 * 人格侧返回 {@code SessionResultResponse}（5 维 + 档位 + 解读文案），
 * 旅行侧返回 {@code TravelProfileResponse}（8 维的 key/name/score）。
 *
 * <p>硬塞进同一个端点意味着响应里要出现"人格字段和旅行字段并存、各自一半是 null"
 * 的形状，前端每处都要判空，已有的接口契约也会被污染。
 * 分开一个前缀，代价只是多写一个类。
 *
 * <h2>⚠️ 每个会话相关端点的第一行都是 checkAccess</h2>
 *
 * <p>推荐结果和旅行画像都挂在 {@code session_id} 上，而会话 ID 是自增的连续整数。
 * 少了这道校验，任何人遍历 ID 就能读到全站用户的旅行画像和位置——这正是
 * V0.6.1 修过的 IDOR 漏洞。校验统一走 {@link SessionAccessGuard}，
 * 失败返回 <b>404</b> 而不是 403（403 会泄露"这个 ID 存在"）。
 */
@RestController
@RequestMapping("/api/travel")
public class TravelController {

    private final TestSessionService testSessionService;
    private final TravelProfileService travelProfileService;
    private final RecommendationService recommendationService;
    private final SessionAccessGuard accessGuard;

    public TravelController(TestSessionService testSessionService,
                            TravelProfileService travelProfileService,
                            RecommendationService recommendationService,
                            SessionAccessGuard accessGuard) {
        this.testSessionService = testSessionService;
        this.travelProfileService = travelProfileService;
        this.recommendationService = recommendationService;
        this.accessGuard = accessGuard;
    }

    /** 见类注释：会话相关的端点都要先调它。 */
    private void checkAccess(Long sessionId, String rawToken, AppUserPrincipal principal) {
        accessGuard.requireAccess(sessionId, SessionAccessGuard.parseToken(rawToken), principal);
    }

    /**
     * {@code POST /api/travel/sessions} —— 开始一次旅行偏好测试。
     *
     * <p>和人格测试一样：登录和未登录都能用，登录了就关联到当前用户。
     * 响应里的 {@code accessToken} 是匿名用户访问这个会话的唯一凭证，
     * <b>只在这一次返回</b>，不会出现在任何列表接口里。
     */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse createSession(@AuthenticationPrincipal AppUserPrincipal principal) {
        Long userId = (principal == null) ? null : principal.getId();
        TestSession session = testSessionService.createTravelSession(userId);
        return SessionResponse.from(session);
    }

    /**
     * {@code POST /api/travel/sessions/{id}/answers} —— 保存作答。
     *
     * <p>请求体直接复用人格侧的 {@link SubmitAnswersRequest}——
     * 题目只有 id 和 1~5 的分值，两套量表完全一样，没必要各写一份。
     */
    @PostMapping("/sessions/{sessionId}/answers")
    public AnswersSavedResponse saveAnswers(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody SubmitAnswersRequest request) {

        checkAccess(sessionId, sessionToken, principal);
        int savedCount = testSessionService.saveAnswers(sessionId, request.answers());
        return new AnswersSavedResponse(sessionId, savedCount);
    }

    /**
     * {@code POST /api/travel/sessions/{id}/submit} —— 提交并计分，得到 8 维旅行画像。
     *
     * <p>和人格侧的 submit 一样是"计分 + 读取"两步合一，前端一个请求拿到结果。
     */
    @PostMapping("/sessions/{sessionId}/submit")
    public TravelProfileResponse submit(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
            @AuthenticationPrincipal AppUserPrincipal principal) {

        checkAccess(sessionId, sessionToken, principal);
        testSessionService.submitTravel(sessionId);
        return travelProfileService.buildProfileResponse(sessionId);
    }

    /**
     * {@code GET /api/travel/sessions/{id}/profile} —— 查询旅行画像。
     *
     * <p>GET 必须幂等无副作用：只读，不触发计分。没提交过就返回 404。
     */
    @GetMapping("/sessions/{sessionId}/profile")
    public TravelProfileResponse getProfile(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
            @AuthenticationPrincipal AppUserPrincipal principal) {

        checkAccess(sessionId, sessionToken, principal);
        return travelProfileService.buildProfileResponse(sessionId);
    }

    /**
     * {@code POST /api/travel/sessions/{id}/recommendations} —— 拿 Top 3 推荐。
     *
     * <p><b>为什么是 POST 而不是 GET</b>：这个操作有副作用——每次调用都会在
     * {@code recommendations} 表里新写一批记录（用户反馈要挂上去，接受率指标也要历史数据）。
     * GET 按 HTTP 语义必须是幂等、可缓存的，而"每次调用产生一批新数据"显然不满足。
     *
     * <p>请求体里的定位是必填的，理由见 {@link RecommendationRequest}。
     */
    @PostMapping("/sessions/{sessionId}/recommendations")
    public RecommendationResponse recommend(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody RecommendationRequest request) {

        checkAccess(sessionId, sessionToken, principal);
        return recommendationService.recommend(sessionId, request);
    }

    /**
     * {@code POST /api/travel/sessions/{id}/recommendations/{recommendationId}/feedback}
     * —— 对某一条推荐点 👍 / 👎。
     *
     * <p>响应里带回<b>画像被调整的结果</b>（哪个维度从多少变到多少），
     * 让用户当场看得见反馈的影响——否则画像是"下次推荐才用到"的东西，
     * 用户点完没有任何感觉，很容易以为按钮坏了。
     *
     * <p>重复提交同一条是合法的：改主意走 UPDATE（👎 → 👍 不会留下两条记录）。
     *
     * <p><b>为什么 recommendationId 放在路径里而不是请求体里</b>：
     * 它标识的是"哪一个资源"，属于 URL 的职责。而且放在路径里能让
     * "这条推荐属不属于这个会话"的校验显得更自然——Service 会挡住
     * 拿自己的 sessionId 配别人的 recommendationId 这种写法。
     */
    @PostMapping("/sessions/{sessionId}/recommendations/{recommendationId}/feedback")
    public FeedbackResponse giveFeedback(
            @PathVariable Long sessionId,
            @PathVariable Long recommendationId,
            @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody FeedbackRequest request) {

        checkAccess(sessionId, sessionToken, principal);
        return recommendationService.submitFeedback(sessionId, recommendationId, request.reaction());
    }
}
