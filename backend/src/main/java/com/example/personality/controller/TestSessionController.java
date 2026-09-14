package com.example.personality.controller;

import com.example.personality.dto.AnswersSavedResponse;
import com.example.personality.dto.SessionResponse;
import com.example.personality.dto.SessionResultResponse;
import com.example.personality.dto.SubmitAnswersRequest;
import com.example.personality.entity.TestSession;
import com.example.personality.security.AppUserPrincipal;
import com.example.personality.service.ProfileQueryService;
import com.example.personality.service.TestSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试会话接口——整个 MVP 的主干链路。
 *
 * <p>对应计划书第七节的 4 个端点（外加 result 的查询）。
 *
 * <h2>Controller 该有多厚？</h2>
 * 看下面的方法：每个都只有一两行，做三件事——接参数、调 Service、返回结果。
 * <b>业务逻辑一行都不该出现在 Controller 里。</b>
 *
 * <p>原因是 Controller 是最难测试的一层（要起 Web 环境、造 HTTP 请求），
 * 逻辑放在这里就意味着它很难被单元测试覆盖。而 Service 是普通 Java 对象，
 * 可以直接 new 出来测。把逻辑往 Service 推，测试成本会低一个数量级。
 */
@RestController
@RequestMapping("/api/test-sessions")
public class TestSessionController {

    private final TestSessionService testSessionService;
    private final ProfileQueryService profileQueryService;

    public TestSessionController(TestSessionService testSessionService,
                                 ProfileQueryService profileQueryService) {
        this.testSessionService = testSessionService;
        this.profileQueryService = profileQueryService;
    }

    /**
     * {@code POST /api/test-sessions} —— 开始一次新测试。
     *
     * <p><b>登录和未登录都能用。</b>登录了就自动把这次测试关联到当前用户
     * （之后能在历史记录里看到）；没登录就是匿名测试，{@code user_id} 留空，
     * 功能完全一样，只是不留痕。不强迫用户为了做个测试先注册。
     *
     * <p><b>⚠️ 注意这里不再从请求体读 userId。</b>
     * 之前 {@code CreateSessionRequest} 里有个 {@code userId} 字段，
     * 那是个安全隐患：客户端传什么就信什么，任何人只要把 id 改一改，
     * 就能以别人的名义创建测试记录。用户身份必须从**服务端的会话**里取，
     * 绝不能来自请求参数。
     *
     * <p>{@code @AuthenticationPrincipal} 会从安全上下文取出当前身份；
     * 匿名访问时是 null。参数类型是我们自己的 {@code AppUserPrincipal}，
     * 所以能直接拿到业务主键 id，不需要额外查库。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse createSession(@AuthenticationPrincipal AppUserPrincipal principal) {
        Long userId = (principal == null) ? null : principal.getId();
        TestSession session = testSessionService.createSession(userId);
        return SessionResponse.from(session);
    }

    /**
     * {@code POST /api/test-sessions/{id}/answers} —— 保存作答。
     *
     * <p>{@code @Valid} 是触发前面 DTO 上那些校验注解的开关。
     * 少了它，{@code @Min/@Max/@NotNull} 全都不会生效——这是一个非常常见的遗漏，
     * 而且不报错，只是校验静默地不工作了。
     *
     * <p>{@code @PathVariable Long sessionId} 把 URL 里的 {@code {sessionId}}
     * 绑定到方法参数，并自动把字符串 "123" 转成 Long 123。
     * 如果传的不是数字，Spring 会抛 {@code MethodArgumentTypeMismatchException}，
     * 返回 400，不需要你写转换代码。
     */
    @PostMapping("/{sessionId}/answers")
    public AnswersSavedResponse saveAnswers(@PathVariable Long sessionId,
                                            @Valid @RequestBody SubmitAnswersRequest request) {
        int savedCount = testSessionService.saveAnswers(sessionId, request.answers());
        return new AnswersSavedResponse(sessionId, savedCount);
    }

    /**
     * {@code POST /api/test-sessions/{id}/submit} —— 提交并计分。
     *
     * <p>这是闭环收口点。先触发计分（Service 层的事务），
     * 再把算好的结果读出来返回，这样前端一个请求就能拿到分数，
     * 少一次往返。
     *
     * <p>这里两步都在事务外（每个 Service 方法自己管事务），
     * 所以"计分"和"读取"之间理论上可能有别的请求插进来。
     * 对 V0.1 无所谓——画像一旦生成就不会变。如果将来画像会更新，
     * 就得把这两步合并进同一个事务。
     */
    @PostMapping("/{sessionId}/submit")
    public SessionResultResponse submit(@PathVariable Long sessionId) {
        testSessionService.submit(sessionId);
        return profileQueryService.buildResult(sessionId);
    }

    /**
     * {@code GET /api/test-sessions/{id}/result} —— 查询结果。
     *
     * <p>GET 请求必须是<b>幂等且无副作用</b>的：调 100 次和调 1 次结果一样，
     * 且不修改任何数据。所以这里只读，不触发计分。
     * 如果会话还没提交过，会返回 404（由 ProfileQueryService 抛出）。
     */
    @GetMapping("/{sessionId}/result")
    public SessionResultResponse getResult(@PathVariable Long sessionId) {
        return profileQueryService.buildResult(sessionId);
    }
}
