package com.example.personality.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 *
 * <p><b>为什么需要它：</b>下一轮的 React 前端跑在
 * {@code http://localhost:5173}，后端跑在 {@code http://localhost:8080}。
 * 端口不同 = 不同的"源"（Origin），浏览器会拦截前端发起的请求，
 * 报一个看起来吓人但其实是安全机制正常的错误：
 * <pre>
 *   Access to fetch at 'http://localhost:8080/api/questions'
 *   from origin 'http://localhost:5173' has been blocked by CORS policy
 * </pre>
 *
 * <p>注意是<b>浏览器</b>拦截的，不是服务器拒绝。后端日志里能看到请求已经处理完了，
 * 但响应被浏览器丢掉——所以别去后端日志里找原因，那里什么都没有。
 *
 * <p><b>⚠️ 安全提醒：</b>这里的配置只允许 localhost，是开发环境的正确做法。
 * 上线时必须把 {@code allowedOriginPatterns} 改成真实域名。
 * 如果图省事写成 {@code "*"} 加上 {@code allowCredentials(true)}，
 * 等于允许任意网站拿着用户的 Cookie 调用你的接口——
 * 这是很典型的 CSRF 漏洞来源。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 用 allowedOriginPatterns 而不是 allowedOrigins：
                // 前者支持通配符，且在将来需要携带凭证时不会报错。
                // 这里只放开本机，覆盖 Vite(5173) 和 CRA(3000) 两个常用端口。
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*"
                )
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 允许跨域请求携带 Cookie。
                //
                // ⚠️ 一旦开了这个，浏览器就要求 allowedOrigins **不能**是 "*"，
                // 必须是明确的源——所以上面用的是 allowedOriginPatterns
                // 而不是 allowedOrigins。把 "*" 和 allowCredentials(true)
                // 组合在一起，等于允许任意网站带着用户的 Cookie 调你的接口。
                .allowCredentials(true)
                // 让前端能读到 CSRF 令牌的响应头（跨域部署时需要）
                .exposedHeaders("X-XSRF-TOKEN")
                // 预检请求（OPTIONS）的缓存时间，单位秒。
                // 设了它，浏览器 1 小时内不会对同样的跨域请求反复发预检，
                // 能明显减少请求数。
                .maxAge(3600);
    }
}
