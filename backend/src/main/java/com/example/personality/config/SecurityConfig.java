package com.example.personality.config;

import com.example.personality.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
// ⚠️ Spring Boot 4 用的是 **Jackson 3**，databind 的包名从
//    com.fasterxml.jackson.databind 改成了 tools.jackson.databind。
//    （注解包仍是 com.fasterxml.jackson.annotation，保留 2.x 做兼容——
//      所以在 DeepSeekAiReportGenerator 里写 @JsonIgnoreProperties 不受影响。）
//    从 Spring Boot 3 升级过来时，这是最容易踩的编译错误之一。
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.io.IOException;

/**
 * Spring Security 配置。
 *
 * <h2>引入 Spring Security 后最重要的一件事</h2>
 *
 * <p>它的默认行为是<b>所有请求都需要认证</b>。不写这个配置类的话，
 * 连 {@code GET /api/health} 都会返回 401。所以下面的
 * {@code authorizeHttpRequests} 不是"加强安全"，而是"把该放开的放开"——
 * 每一条 {@code permitAll()} 都是一个需要你确认过的决定。
 *
 * <h2>为什么用 Session Cookie 而不是 JWT</h2>
 *
 * <p>会话状态存在服务端（HttpSession），浏览器只拿一个不可读的会话 ID。
 * 相比 JWT 方案的优势：
 * <ul>
 *   <li><b>注销是真的注销</b>——服务端删掉会话即失效。
 *       JWT 签发后无法撤回，要"强制下线"得额外维护黑名单，那又变成有状态了</li>
 *   <li><b>令牌偷不走</b>——会话 Cookie 可以设成 HttpOnly，
 *       JavaScript 读不到，XSS 也偷不走。JWT 若存 localStorage 则恰恰相反</li>
 *   <li><b>没有过期刷新的复杂度</b>——会话可以滑动续期，不需要 refresh token</li>
 * </ul>
 *
 * <p>代价是不好水平扩展（多台服务器要共享会话存储）。但本项目
 * 单体部署，这个代价为零；真要扩展时，把会话挪到 Redis 即可，
 * 业务代码一行不用改。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 密码编码器。
     *
     * <p><b>BCrypt 而不是 MD5/SHA256。</b>后者是通用哈希函数，设计目标是"快"，
     * 一张现代显卡每秒能算几十亿次——用户密码空间小，暴力破解很快就能撞出来。
     *
     * <p>BCrypt 是专门为密码设计的，有两个关键特性：
     * <ul>
     *   <li><b>故意慢</b>：内置可调的工作因子（这里是默认的 10 次迭代），
     *       让每次校验耗时约 100ms。正常登录无感，但暴力破解的成本提高了几百万倍</li>
     *   <li><b>自带随机盐</b>：同一个密码每次哈希结果都不同，
     *       所以"彩虹表"和"两个用户密码相同就说明他们用了同样的密码"都失效了</li>
     * </ul>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证管理器——登录时用它做"校验用户名密码"这件事。
     *
     * <p>它的实现由 Spring Boot 自动装配：检测到容器里有
     * {@code AppUserDetailsService}（用来取用户）和 {@code PasswordEncoder}
     * （用来比对密码），就自动组装出一个 {@code DaoAuthenticationProvider}
     * 并注册进来。所以这里只需要把它"取出来"暴露成 Bean，不用手工拼装。
     *
     * <p><b>为什么不自己调 {@code passwordEncoder.matches()} 就完事？</b>
     * 因为框架在这一步里顺带做了三件容易被忽略的事：
     * <ol>
     *   <li>用户不存在时执行一次假比对，抹平时序差异（防用户名枚举）</li>
     *   <li>密码为空/被锁定/已过期等情况统一抛出规范的异常类型</li>
     *   <li>认证成功后封装出带权限的 {@code Authentication} 对象</li>
     * </ol>
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * 会话上下文的存储位置：HttpSession。
     *
     * <p>登录成功后要把"谁登录了"这个信息存起来，这个接口负责存取。
     * 换成 JWT 方案时，这里会换成从请求头解析令牌的实现——
     * 业务代码不用改，因为大家用的都是 {@code SecurityContextRepository}
     * 这个抽象。
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 会话固定攻击防护。
     *
     * <p><b>攻击场景</b>：攻击者先访问网站拿到一个会话 ID（比如通过
     * 诱导你点击一个带 {@code ?JSESSIONID=xxx} 的链接），然后骗你用这个
     * 会话去登录。登录成功后，服务端把这个会话标记为"已认证"——
     * 而攻击者早就知道这个会话 ID，于是他直接就能以你的身份操作。
     *
     * <p><b>防护手段</b>：登录成功时换一个新的会话 ID
     * （{@code ChangeSessionIdAuthenticationStrategy}），
     * 攻击者手里那个旧 ID 就作废了。
     *
     * <p>Spring Security 的登录**过滤器**会自动做这件事，但我们没走
     * {@code formLogin}，而是在 Controller 里手动认证，所以要显式调用它。
     * 这也是"手动实现登录"最容易漏掉的一步。
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // ---------------------------------------------------------
                // CSRF 防护
                // ---------------------------------------------------------
                // 用 Cookie 存令牌（withHttpOnlyFalse 让 JS 能读出来放到请求头里）。
                //
                // 为什么 Cookie 认证必须开 CSRF 防护：
                // 浏览器会自动给同域请求带上 Cookie。如果不开防护，
                // 用户登录后访问一个恶意网站，那个网站上的表单可以
                // 悄悄向你的接口发 POST，浏览器会自动附上会话 Cookie，
                // 服务端会以为这是用户本人的操作。
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))

                // 复用 CorsConfig 里的跨域设置
                .cors(Customizer.withDefaults())

                // ---------------------------------------------------------
                // 授权规则
                // ---------------------------------------------------------
                .authorizeHttpRequests(auth -> auth
                        // 认证相关端点必须匿名可访问，否则没人能登录进来
                        .requestMatchers("/api/auth/register", "/api/auth/login",
                                "/api/auth/logout", "/api/auth/me").permitAll()

                        // 公开的只读/匿名可用端点：
                        // 不登录也能做测试、看结果、生成 AI 解读。
                        // 只有"历史记录"需要登录——那本质上是「我的」数据。
                        .requestMatchers("/api/health", "/api/questions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/test-sessions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/test-sessions/*/answers").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/test-sessions/*/submit").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/test-sessions/*/result").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/test-sessions/*/ai-report").permitAll()

                        // 旅行偏好测试。规则和人格测试完全对称——同样是匿名可做，
                        // 靠会话访问令牌（X-Session-Token）而不是登录身份来保护数据。
                        // ⚠️ 新增端点时这里必须逐条补：漏写的后果很隐蔽——
                        // 请求会掉进最后那句 anyRequest().permitAll()，功能"看起来能用"，
                        // 但这条路径没有经过显式声明的规则，将来收紧权限时会被漏掉。
                        .requestMatchers(HttpMethod.POST, "/api/travel/sessions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/travel/sessions/*/answers").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/travel/sessions/*/submit").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/travel/sessions/*/profile").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/travel/sessions/*/recommendations").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/travel/sessions/*/recommendations/*/feedback").permitAll()

                        // 「我的」数据必须登录
                        .requestMatchers("/api/me/**").authenticated()

                        // 其余（静态页面、favicon 等）放行。
                        // 放在最后是因为前面的规则优先匹配。
                        .anyRequest().permitAll())

                // ---------------------------------------------------------
                // 关掉用不上的默认功能
                // ---------------------------------------------------------
                // 我们自己写了 POST /api/auth/logout，不需要 Spring 的注销过滤器
                .logout(AbstractHttpConfigurer::disable)
                // 这是纯 API，不需要 HTTP Basic 弹窗和表单登录页
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)

                // ---------------------------------------------------------
                // 未认证 / 无权限时返回 JSON 而不是重定向
                // ---------------------------------------------------------
                // 默认行为是 302 重定向到 /login 页面——对浏览器页面合理，
                // 对 fetch 调用的 API 就是灾难：前端拿到一个 200 的 HTML 登录页，
                // 完全不知道发生了什么。
                //
                // ⚠️ 注意这些异常发生在**过滤器链**里，比 Controller 更早，
                // 所以 GlobalExceptionHandler 的 @ExceptionHandler 接不到它们，
                // 必须在这里单独处理。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Unauthorized", "请先登录", req.getRequestURI()))
                        .accessDeniedHandler((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_FORBIDDEN,
                                        "Forbidden", "没有权限执行此操作", req.getRequestURI())));

        return http.build();
    }

    /** 把错误按项目统一的 JSON 结构写回响应体。 */
    private void writeJson(HttpServletResponse response, int status, String error,
                           String message, String path) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getWriter(),
                ApiErrorResponse.of(status, error, message, path));
    }
}
