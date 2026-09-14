package com.example.personality;

import com.example.personality.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
// ⚠️ Spring Boot 4 把测试自动配置的包路径改了：
//    Boot 3: org.springframework.boot.test.autoconfigure.web.servlet
//    Boot 4: org.springframework.boot.webmvc.test.autoconfigure
//    从 Boot 3 升级时这是个必踩的编译错误。
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试的基类。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>集成测试要跑真实的 HTTP 语义（Security 过滤器、序列化、校验、异常处理），
 * 还要连真实数据库。每个测试类都重复写一遍这些配置既啰嗦又容易漏，
 * 所以抽到这里，子类只管写断言。
 *
 * <h2>三个关键注解</h2>
 *
 * <ul>
 *   <li>{@code @SpringBootTest} —— 启动**完整的**应用上下文（不是切片）。
 *       这是集成测试和单元测试的分界线：{@code ScoringServiceTest} 直接
 *       {@code new ScoringService()}，而这里走完整的 Bean 装配。</li>
 *
 *   <li>{@code @AutoConfigureMockMvc} —— 提供 {@link MockMvc}，
 *       用真实的过滤器链和 DispatcherServlet 处理请求，但不启动真正的
 *       Web 服务器（不占端口、快很多）。</li>
 *
 *   <li>{@code @Transactional} —— <b>每个测试方法结束后自动回滚</b>。
 *       这让测试之间互相隔离：A 测试注册了 alice，B 测试不会看到它。
 *       省掉了手写清理逻辑，也不会因为执行顺序不同而出现诡异失败。</li>
 * </ul>
 *
 * <p><b>⚠️ @Transactional 的代价</b>：因为整个测试方法都在一个事务里，
 * 你<b>测不出</b>"事务边界本身是否正确"——比如"抛异常时是否回滚"，
 * 在一个大事务里永远看不出区别。要测那个，得用
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} 发真实 HTTP 请求，
 * 或者去掉 {@code @Transactional} 手工清理数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected LoginRateLimiter loginRateLimiter;

    /** 每个测试用的用户名都不同，避免唯一约束冲突（虽然事务会回滚，但保险起见）。 */
    protected static int userCounter = 0;

    /**
     * 每个测试前重置状态。
     *
     * <p><b>⚠️ 限流器的计数必须手动清空——{@code @Transactional} 回滚不了它。</b>
     *
     * <p>事务回滚只对**数据库**生效。限流器的计数器存在内存的
     * {@code ConcurrentHashMap} 里，是进程级的单例状态，
     * 测试之间会互相污染：A 测试故意连错 5 次密码触发限流，
     * B 测试接着登录就会莫名收到 429。
     *
     * <p>这类"测试之间有隐式依赖"的问题最难查，因为单独跑每个测试类都过，
     * 一起跑就随机失败。<b>凡是跨测试共享的可变状态，都要显式重置。</b>
     */
    @BeforeEach
    void resetSharedState() {
        userCounter++;
        loginRateLimiter.clearAll();
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================

    /** 对象 → JSON 字符串。 */
    protected String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /** 生成一个本测试专用的用户名。 */
    protected String uniqueUsername(String prefix) {
        return prefix + "_" + userCounter + "_" + System.nanoTime() % 100000;
    }

    /**
     * 注册一个用户并登录，返回带着会话的客户端上下文。
     *
     * <p>返回的是一个 {@link MockHttpSession}，子类用它继续发请求：
     * <pre>
     *   var session = registerAndLogin("alice", "password123");
     *   mockMvc.perform(get("/api/me/test-sessions").session(session));
     * </pre>
     */
    protected MockHttpSession registerAndLogin(String username, String password) throws Exception {
        // 1. 注册
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, password))))
                .andExpect(status().isCreated());

        // 2. 登录，从响应里取出会话
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, password))))
                .andExpect(status().isOk())
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** 用于构造注册/登录请求体。 */
    protected record Credentials(String username, String password) {
    }

    // ==========================================================
    // 测试会话（id + 访问令牌）
    // ==========================================================

    /**
     * 一个测试会话的引用。
     *
     * <p>带上令牌是必须的：修复 IDOR 之后，匿名访问会话需要凭
     * {@code X-Session-Token} 请求头里的令牌。登录用户访问自己的会话
     * 则不需要（凭身份放行），但测试里统一带着更省心。
     */
    protected record TestSessionRef(long id, String token) {
    }

    protected static final String SESSION_TOKEN_HEADER = "X-Session-Token";

    /** 给请求带上会话令牌。 */
    protected static MockHttpServletRequestBuilder withToken(
            MockHttpServletRequestBuilder builder, TestSessionRef ref) {
        return builder.header(SESSION_TOKEN_HEADER, ref.token());
    }

    /**
     * 建一个测试会话。
     *
     * @param loginSession 传 null 表示匿名创建
     */
    protected TestSessionRef createTestSession(MockHttpSession loginSession) throws Exception {
        var request = post("/api/test-sessions").with(csrf());
        if (loginSession != null) {
            request = request.session(loginSession);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
        return new TestSessionRef(body.get("sessionId").asLong(), body.get("accessToken").asString());
    }

    /** 取题库里的全部题目 ID。 */
    protected List<Long> fetchQuestionIds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/questions"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode questions = objectMapper.readTree(
                        new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .get("questions");
        List<Long> ids = new ArrayList<>();
        questions.forEach(q -> ids.add(q.get("id").asLong()));
        return ids;
    }

    /** 把全部题目都答成同一个分值。 */
    protected void answerAllQuestions(TestSessionRef ref, int score, MockHttpSession loginSession)
            throws Exception {
        List<Map<String, Object>> answers = new ArrayList<>();
        for (Long qid : fetchQuestionIds()) {
            answers.add(Map.of("questionId", qid, "score", score));
        }
        var request = withToken(post("/api/test-sessions/{id}/answers", ref.id()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("answers", answers))), ref);
        if (loginSession != null) {
            request = request.session(loginSession);
        }
        mockMvc.perform(request).andExpect(status().isOk());
    }

    /** 取结果 JSON。 */
    protected JsonNode fetchResultJson(TestSessionRef ref) throws Exception {
        MvcResult result = mockMvc.perform(withToken(get("/api/test-sessions/{id}/result", ref.id()), ref))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
