package com.example.personality.controller;

import com.example.personality.ai.AiCredentials;
import com.example.personality.ai.AiCredentialsResolver;
import com.example.personality.domain.AmbientContext;
import com.example.personality.dto.AnswersSavedResponse;
import com.example.personality.dto.FeedbackRequest;
import com.example.personality.dto.FeedbackResponse;
import com.example.personality.dto.RecommendationRequest;
import com.example.personality.dto.RecommendationResponse;
import com.example.personality.dto.SessionResponse;
import com.example.personality.dto.SubmitAnswersRequest;
import com.example.personality.dto.TravelProfileResponse;
import com.example.personality.dto.TravelReasonResponse;
import com.example.personality.entity.TestSession;
import com.example.personality.security.AppUserPrincipal;
import com.example.personality.security.SessionAccessGuard;
import com.example.personality.service.AmbientService;
import com.example.personality.service.RecommendationService;
import com.example.personality.service.TestSessionService;
import com.example.personality.service.TravelProfileService;
import com.example.personality.service.TravelReasonService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AmbientService ambientService;
    private final TravelReasonService travelReasonService;
    private final AiCredentialsResolver aiCredentialsResolver;
    private final SessionAccessGuard accessGuard;

    public TravelController(TestSessionService testSessionService,
                            TravelProfileService travelProfileService,
                            RecommendationService recommendationService,
                            AmbientService ambientService,
                            TravelReasonService travelReasonService,
                            AiCredentialsResolver aiCredentialsResolver,
                            SessionAccessGuard accessGuard) {
        this.testSessionService = testSessionService;
        this.travelProfileService = travelProfileService;
        this.recommendationService = recommendationService;
        this.ambientService = ambientService;
        this.travelReasonService = travelReasonService;
        this.aiCredentialsResolver = aiCredentialsResolver;
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
     *
     * <h2>⚠️ 先取环境，再进事务</h2>
     *
     * <p>两行的顺序不能反，也不能合并进 service。{@code recommend} 上有
     * {@code @Transactional}，整个方法期间占着数据库连接；而逆地理编码是
     * 一次网络往返。把网络等待放进事务，十来个并发就能把连接池占满，
     * 让所有接口一起排队超时。
     *
     * <p>所以在这里（事务外、控制器层）先把地名取好，再把结果当参数传进去。
     * 完整理由见 {@link AmbientService} 的类注释。
     *
     * <p>取不到地名时 {@code ambientService} 返回空，这里是正常的 null，
     * 不构成错误——{@code locationLabel} 就是 null，其余照常。
     */
    @PostMapping("/sessions/{sessionId}/recommendations")
    public RecommendationResponse recommend(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody RecommendationRequest request) {

        checkAccess(sessionId, sessionToken, principal);

        // 事务外：两次网络往返（逆地理编码 → 天气）
        AmbientContext ambient = ambientService.resolve(request.latitude(), request.longitude());

        // 事务内：只碰本地数据库
        return recommendationService.recommend(sessionId, request, ambient);
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

    /**
     * {@code POST /api/travel/sessions/{id}/recommendations/reasons} —— 生成 AI 推荐理由。
     *
     * <p>作用于该会话<b>最新的一批</b>推荐，为其中每个地点写一句话。
     *
     * <h2>为什么是独立接口，不塞进推荐请求里</h2>
     *
     * <p>推荐是本地算法算的（毫秒级），理由要调大模型（几秒）。绑在一起的话，
     * "拿推荐"这个产品最核心的交互就得等 AI，而且 AI 一慢或一挂，
     * 推荐本身也跟着不可用。分开之后，前端可以先把列表秒出来，理由到了再填进去。
     *
     * <h2>⚠️ 这个端点的访问校验尤其不能省</h2>
     *
     * <p><b>AI 调用是花钱的。</b>少了 {@code checkAccess}，任何人都能遍历
     * sessionId 把别人的理由跑一遍，账单算在会话主人头上——
     * 人格侧 {@code AiReportController} 的注释专门写了这一条，是同一个道理。
     *
     * @param regenerate 为 false 时，这批已经都有理由了就直接返回缓存，
     *                   <b>不调用大模型</b>。默认关掉是刻意的：用户误点两次
     *                   不该白花两次 token，而且两次文本不一样反而让人困惑。
     *                   <p>前端「重新生成」按钮带上 {@code ?regenerate=true} 即可。
     */
    @PostMapping("/sessions/{sessionId}/recommendations/reasons")
    public TravelReasonResponse generateReasons(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(defaultValue = "false") boolean regenerate,
            // 访客自带的大模型凭据。两个都不传就是走服务端配置的那把。
            @RequestHeader(value = AiCredentialsResolver.PROVIDER_HEADER, required = false)
            String aiProvider,
            @RequestHeader(value = AiCredentialsResolver.KEY_HEADER, required = false)
            String aiKey) {

        checkAccess(sessionId, sessionToken, principal);

        // ⚠️ 解析放在**进服务层之前**，有两个原因：
        //   ① 都没有凭据时在这里就抛 501，后面一行都不会执行——不会白读一遍库
        //   ② 厂商名认不出来是 400，属于"请求本身有问题"，也该在碰业务逻辑之前就挡掉
        AiCredentials credentials = aiCredentialsResolver.require(aiProvider, aiKey);

        // 服务层拿到的是**已经解析好的凭据**，不用关心它从哪来，
        // 也因此不会把 HTTP 请求头这个概念漏进业务逻辑里。
        return travelReasonService.generate(sessionId, regenerate, credentials);
    }
}
