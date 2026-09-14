package com.example.personality.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * 前后端分离场景下的 CSRF 令牌处理。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>Spring Security 6 默认用 {@link XorCsrfTokenRequestAttributeHandler}，
 * 它会把令牌做一次 XOR 掩码（这是为了防 BREACH 攻击——一种通过 HTTP 压缩
 * 响应体积变化来逐字符推断出密钥的攻击）。
 *
 * <p>问题在于：<b>掩码后的值和写进 Cookie 的原始值对不上</b>。
 * 浏览器把 Cookie 里的值放到请求头里发回来时，服务端一比对就失败，
 * 于是所有 POST 请求都收到 403。这是 Spring Security 6 + SPA 最常见的坑，
 * 搜索结果里一大堆"升级到 6.x 后 CSRF 一直 403"都是这个原因。
 *
 * <h2>这个类的做法</h2>
 *
 * <p>按请求来源选择处理方式：
 * <ul>
 *   <li><b>请求头里带了令牌</b>（来自 JS，比如 SPA 用 fetch 发的请求）
 *       → 用 {@code plain} 处理，直接明文比对</li>
 *   <li><b>没有请求头</b>（传统表单提交）→ 用 {@code xor} 处理，保留 BREACH 防护</li>
 * </ul>
 *
 * <p>这样 SPA 能正常工作，同时表单场景的安全性没有被削弱。
 * 这是 Spring Security 官方文档给出的标准做法。
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);

        // 强制解析一次令牌。
        // 少了这一行，在"只读取令牌但不渲染表单"的请求里（比如 GET /api/auth/me），
        // Cookie 不会被写入浏览器，前端随后就找不到令牌可发，POST 必然 403。
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor)
                .resolveCsrfTokenValue(request, csrfToken);
    }
}
