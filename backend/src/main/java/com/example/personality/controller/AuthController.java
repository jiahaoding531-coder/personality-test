package com.example.personality.controller;

import com.example.personality.dto.LoginRequest;
import com.example.personality.dto.RegisterRequest;
import com.example.personality.dto.UserResponse;
import com.example.personality.entity.User;
import com.example.personality.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录 / 注销 / 查询当前用户。
 *
 * <p>这四个端点在 {@code SecurityConfig} 里被设为 {@code permitAll}——
 * 必须如此，否则没人能登录进来（想登录就得先登录，死循环）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public AuthController(UserService userService,
                          AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    /**
     * {@code POST /api/auth/register} —— 注册。
     *
     * <p><b>注册后不会自动登录。</b>用户需要再调一次 login。
     * 这样前端流程更清晰（注册页 → 登录页），
     * 也避免了"注册接口既要建账号又要建会话"的职责混杂。
     *
     * <p>用户名重复 → 409；格式不合法 → 400（由 DTO 上的注解自动校验）。
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request.username(), request.password());
        return UserResponse.from(user);
    }

    /**
     * {@code POST /api/auth/login} —— 登录，成功后建立会话。
     *
     * <p>这个方法体看着有几步，但每一步都不能省。它等价于
     * {@code formLogin} 过滤器内部做的事情——我们只是把手动登录
     * 需要的步骤显式写了出来：
     *
     * <ol>
     *   <li>{@code authenticationManager.authenticate(...)} —— 校验用户名密码。
     *       失败会抛 {@code BadCredentialsException}，由 GlobalExceptionHandler
     *       转成 401</li>
     *   <li>{@code sessionAuthenticationStrategy.onAuthentication(...)} ——
     *       <b>换一个新的会话 ID</b>，防会话固定攻击。
     *       这一步最容易被漏掉，因为漏了之后功能完全正常，只是不安全</li>
     *   <li>把认证结果放进 {@code SecurityContext}</li>
     *   <li>{@code securityContextRepository.saveContext(...)} ——
     *       把上下文写进 HttpSession，否则下一个请求就"忘了"你登录过</li>
     * </ol>
     *
     * <p>注意第 2 步和第 4 步的顺序：必须先换 ID 再存上下文。
     * 反过来的话，上下文会存进那个即将被丢弃的旧会话里。
     */
    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpRequest,
                              HttpServletResponse httpResponse) {

        // 1. 校验凭证。用 unauthenticated(...) 明确表示"这是一个尚未认证的令牌"，
        //    比直接 new 更清楚地表达意图。
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.username(), request.password()));

        // 2. 会话固定攻击防护：换新的会话 ID
        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        // 3. 建立安全上下文
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        // 4. 持久化到会话，让后续请求能识别身份
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return UserResponse.from(userService.requireByUsername(authentication.getName()));
    }

    /**
     * {@code POST /api/auth/logout} —— 注销。
     *
     * <p>服务端销毁会话，返回 204 No Content。
     *
     * <p>这里体现了 Session 方案相比 JWT 的一个实在优势：
     * <b>注销是真的注销</b>。会话没了就是没了，不存在"令牌还没过期"的窗口期。
     * JWT 方案要实现同样的效果，得额外维护一个已注销令牌的黑名单——
     * 那又变成有状态了，等于绕了一圈回到起点。
     *
     * <p>{@code getSession(false)} 的 false 表示"没有会话就返回 null，
     * 不要新建一个"。不加这个参数的话，注销请求反而会创建一个新会话，
     * 然后在下一行把它销毁——既浪费又容易让人困惑。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code GET /api/auth/me} —— 查询当前登录用户。
     *
     * <p>前端启动时会调它来判断"我登录了没"。
     *
     * <p><b>未登录时返回 200 + 空响应体，而不是 401。</b>
     * 这是一个刻意的取舍：
     * <ul>
     *   <li>401 语义上更"正确"，但前端每次启动都要为一个完全正常的情况
     *       （没登录）写 try/catch，代码反而更乱</li>
     *   <li>返回 200 + null，前端一句 {@code if (user)} 就处理完了</li>
     * </ul>
     * 真正需要保护的是 {@code /api/me/**} 那些端点——那里未登录会返回 401，
     * 因为那才是"你没权限访问"的错误场景。
     *
     * <p>{@code @AuthenticationPrincipal} 会从安全上下文里取出当前身份。
     * 匿名访问时 Spring 放的是 {@code AnonymousAuthenticationToken}，
     * 它的 principal 是个字符串而不是 {@code UserDetails}，
     * 所以这里的参数会是 {@code null}——正好用来判断"没登录"。
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok(
                UserResponse.from(userService.requireByUsername(principal.getUsername())));
    }
}
