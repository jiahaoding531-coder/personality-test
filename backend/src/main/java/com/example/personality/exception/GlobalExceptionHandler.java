package com.example.personality.exception;

import com.example.personality.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局异常处理——把抛出的异常翻译成统一的 JSON 错误响应。
 *
 * <p>{@code @RestControllerAdvice} 的作用是"给所有 Controller 加一层环绕"。
 * 任何 Controller 抛出的异常都会先经过这里，你有机会把它转成合适的响应。
 *
 * <p>没有它的话，Spring 默认的报错长这样：
 * <pre>
 *   {
 *     "timestamp": "...",
 *     "status": 500,
 *     "error": "Internal Server Error",
 *     "path": "/api/test-sessions/999/submit"
 *   }
 * </pre>
 * 用户看不出来到底哪儿错了，而且未捕获异常还会把整段 Java 堆栈打到日志里
 * （堆栈里可能包含类名、SQL 片段，属于信息泄露）。
 *
 * <p><b>处理器的匹配规则是"最具体的优先"。</b>
 * 业务异常有专门的处理器，参数校验异常有专门的处理器，
 * 剩下的漏网之鱼才落到 {@code Exception.class} 那个兜底的处理器。
 * 所以顺序不重要，Spring 会自己挑最匹配的。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：404 / 409 / 400 / 501 都走这里。
     *
     * <p>因为 {@link BusinessException} 自己带着 HttpStatus，
     * 这个处理器不需要写任何 if-else 去区分——这正是把状态码放进异常类的好处。
     *
     * <p>用 {@code log.warn} 而不是 {@code log.error}：业务异常是"预期内的分支"
     * （用户传了不存在的 ID、重复提交），不是系统故障。
     * 打成 ERROR 会污染告警系统，让真正的故障被淹没。
     * <b>日志级别是有语义的，不要一律用 error。</b>
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(
            BusinessException ex, HttpServletRequest request) {

        HttpStatus status = ex.status();
        log.warn("业务异常 status={} path={} message={}",
                status.value(), request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(status).body(ApiErrorResponse.of(
                status.value(),
                status.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        ));
    }

    /**
     * 参数校验失败：DTO 上的 {@code @Min/@Max/@NotNull} 没通过。
     *
     * <p>这类异常的特别之处是它可能同时有<b>多个</b>字段不合法，
     * 所以响应里用 {@code fieldErrors} 数组逐个列出，
     * 前端可以精确地把错误提示显示在对应的输入框旁边。
     *
     * <p>{@code MethodArgumentNotValidException} 只会由
     * {@code @RequestBody} + {@code @Valid} 的组合触发。
     * 如果是 {@code @RequestParam} 校验失败，抛的是
     * {@code ConstraintViolationException}，需要另写一个处理器——
     * V0.1 没有这种参数，所以先不写。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ApiErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        // getBindingResult() 里装着所有校验失败的明细
        for (org.springframework.validation.FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage()));
        }

        log.warn("参数校验失败 path={} 错误数={}", request.getRequestURI(), fieldErrors.size());

        return ResponseEntity.badRequest().body(ApiErrorResponse.withFieldErrors(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "请求参数不合法",
                request.getRequestURI(),
                fieldErrors
        ));
    }

    /**
     * 请求体不是合法 JSON，或字段类型对不上。
     *
     * <p>典型场景：body 里多写了一个逗号、少了一个引号、
     * 或者把 {@code "score": "abc"} 这种字符串传给了 int 字段。
     * Jackson 解析失败时抛出本异常。
     *
     * <p><b>必须单独处理，否则会掉进下面的兜底分支返回 500。</b>
     * 但那是个误导——请求体格式错误是<b>客户端</b>的问题（4xx），
     * 服务端本身完全正常。返回 500 会让前端以为后端挂了，
     * 排查方向整个跑偏。
     *
     * <p>这个分支很容易被漏掉，因为它需要"发一个坏请求"才会暴露。
     * 正常功能的测试永远发现不了它。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        log.warn("请求体无法解析 path={} message={}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "请求体不是合法的 JSON，或字段类型不匹配",
                request.getRequestURI()
        ));
    }

    /**
     * 路径变量或查询参数类型转换失败。
     *
     * <p>场景：{@code GET /api/test-sessions/abc/result} —— 路径里写的是 "abc"，
     * 但 Controller 声明的是 {@code Long sessionId}，字符串转 Long 失败。
     *
     * <p>同样必须单独处理，否则会变成 500。这是"资源标识符格式不对"，
     * 属于客户端错误，返回 400 并告诉调用方期望什么格式。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String required = (ex.getRequiredType() == null) ? "正确的类型" : ex.getRequiredType().getSimpleName();
        String message = "参数「" + ex.getName() + "」格式不正确，期望 " + required + "，实际是：" + ex.getValue();

        log.warn("参数类型不匹配 path={} {}", request.getRequestURI(), message);

        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                message,
                request.getRequestURI()
        ));
    }

    /**
     * 触发限流，映射为 429。
     *
     * <p>比 {@link BusinessException} 的通用处理器更具体，所以会被优先选中。
     *
     * <p>它比通用处理器多做一件事：<b>往响应里塞一个 {@code Retry-After} 头</b>。
     * 这是 HTTP 规范为 429 定义的标准头，客户端可以据此显示
     * 「请 X 秒后重试」的倒计时，而不是干等或者盲目重试。
     * 把"要等多久"放在标准头里，也方便反向代理、CDN 这些中间层识别和处理。
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException ex, HttpServletRequest request) {

        log.warn("触发限流 path={} message={}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(ex.status())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiErrorResponse.of(
                        ex.status().value(),
                        ex.status().getReasonPhrase(),
                        ex.getMessage(),
                        request.getRequestURI()));
    }

    /**
     * 登录失败：用户名不存在，或密码错误。
     *
     * <p><b>⚠️ 这里最关键的一点是：两种情况返回一模一样的消息。</b>
     *
     * <p>如果对"用户不存在"返回「该用户不存在」，对"密码错误"返回
     * 「密码错误」，攻击者就能拿一个字典逐个试，<b>快速地枚举出系统里
     * 有哪些用户名</b>——这叫用户名枚举漏洞。有了用户名，后面的
     * 密码爆破就有了明确目标，难度大幅降低。
     *
     * <p>所以统一返回「用户名或密码错误」。用户会稍微多花一点时间
     * 排除问题，但攻击者拿不到任何有用信息。
     *
     * <p>同样的道理，{@code AppUserDetailsService} 在用户不存在时会抛
     * {@code UsernameNotFoundException}，而 Spring Security 会对它
     * 执行一次"假密码比对"，让两条路径的**耗时**也基本一致——
     * 否则光靠响应时间就能把用户名试出来（时序攻击）。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {

        // 日志里记录真实原因（方便自己排查），但响应体里只说"用户名或密码错误"。
        // 日志是给自己看的，响应是给攻击者看的，两者要区别对待。
        log.warn("登录失败 path={} 原因={}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "用户名或密码错误",
                request.getRequestURI()
        ));
    }

    /**
     * 请求了一个不存在的路径或静态资源。
     *
     * <p>比如访问 {@code /api/nonexistent}，或者静态目录里没有的 {@code /foo.html}。
     *
     * <p><b>同样必须单独处理。</b>Spring 6.1 起对静态资源找不到的情况抛的是
     * {@code NoResourceFoundException}，如果不接住，它会掉进下面的兜底分支返回 500——
     * 但"你要的东西不存在"显然是 404。这个分支特别隐蔽，因为它只有在
     * 请求路径写错时才会暴露，正常功能测试永远碰不到。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest request) {

        log.warn("资源不存在 path={}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                "资源不存在：" + request.getRequestURI(),
                request.getRequestURI()
        ));
    }

    /**
     * 兜底：所有没被上面处理器接住的异常。
     *
     * <p><b>两个关键点：</b>
     * <ol>
     *   <li>返回给用户的 message 是<b>写死的通用文案</b>，绝不能是
     *       {@code ex.getMessage()}。因为未预期异常的 message 里可能包含
     *       SQL 语句、文件路径、内网主机名——这些都是攻击者想要的信息。
     *       详细信息应该只进服务端日志。</li>
     *   <li>用 {@code log.error(msg, ex)} 把完整堆栈记下来。
     *       如果这里不记，异常就被"吃掉"了，线上出问题时你什么都查不到。</li>
     * </ol>
     *
     * <p>同时这也保证了：即使代码里有没考虑到的 bug，接口也永远是
     * 统一的 JSON 格式，不会给前端返回一整页 HTML 错误页。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception ex, HttpServletRequest request) {

        log.error("未预期的异常 path={}", request.getRequestURI(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "服务器内部错误，请稍后重试",
                request.getRequestURI()
        ));
    }
}
