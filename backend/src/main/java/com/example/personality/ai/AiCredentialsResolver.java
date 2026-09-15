package com.example.personality.ai;

import com.example.personality.config.AiProperties;
import com.example.personality.exception.InvalidAiCredentialsException;
import com.example.personality.exception.NotImplementedException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 决定这次请求该用谁的 key。
 *
 * <h2>生效顺序</h2>
 * <pre>
 *   ① 请求头里访客自带的 key   → 用它
 *   ② 服务端配置的 key         → 用它
 *   ③ 都没有                   → 空（由调用方转成 501，前端会引导访客去填 key）
 * </pre>
 *
 * <p><b>访客的优先。</b>他既然特意填了自己的，就不该被"服务端恰好也配了"盖掉——
 * 那样他会以为自己填的没生效（而且确实没生效）。
 *
 * <h2>⚠️ 这个类是纯逻辑的，不碰网络也不碰数据库</h2>
 *
 * <p>这不是巧合，是刻意的：**这次改动里最该被测试盯住的安全逻辑全在这里**
 * （厂商白名单、URL 从哪来、优先级），而纯逻辑类可以毫秒级单测，
 * 不需要数据库、不需要联网、不花钱。
 *
 * <p>相比之下"带着这个凭据去调模型"那部分必须联网，测起来又慢又贵——
 * 把能测的部分挤到这一侧，是这个类单独存在的第二个理由。
 */
@Component
public class AiCredentialsResolver {

    /**
     * 访客自带 key 用的两个请求头。
     *
     * <p>抽成常量而不是在两个控制器里各写一遍字符串：写岔了不会报错，
     * 只会表现成"填了 key 却没用上"，而且很难查。
     */
    public static final String PROVIDER_HEADER = "X-AI-Provider";

    public static final String KEY_HEADER = "X-AI-Key";

    private final AiProperties properties;

    public AiCredentialsResolver(AiProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析这次请求该用的凭据。
     *
     * @param providerHeader {@code X-AI-Provider}，厂商标识。可以为 null/空
     * @param userKeyHeader  {@code X-AI-Key}，访客自己的 key。可以为 null/空
     * @return 凭据；没有可用的返回空，调用方据此返回 501
     * @throws InvalidAiCredentialsException 带了 key 却给了个认不出的厂商名（400）
     */
    public Optional<AiCredentials> resolve(String providerHeader, String userKeyHeader) {
        if (properties.isAllowUserKeys() && hasText(userKeyHeader)) {
            return Optional.of(AiCredentials.forUser(resolveProvider(providerHeader),
                    userKeyHeader.trim()));
        }

        // 访客没带 key（或这个部署关掉了自带 key），退回服务端配置的那把。
        // ⚠️ 这里的 enabled 语义是「允不允许用服务端配的 key」，不是「有没有 AI」——
        //    访客自带 key 时完全不看它。见 AiProperties#isEnabled 的注释。
        if (properties.isEnabled() && hasText(properties.getApiKey())) {
            return Optional.of(AiCredentials.forServer(
                    properties.getBaseUrl(), properties.getModel(), properties.getApiKey()));
        }

        return Optional.empty();
    }

    /**
     * 和 {@link #resolve} 一样，但没有可用凭据时直接抛 501。
     *
     * <p>把这段文案收在这里，而不是让每个调用点各写一遍——
     * <b>因为它要被真实的用户看到</b>，而这段话是它有生以来最重要的一次出场：
     *
     * <blockquote>以前 501 的意思是"这站没有 AI"，前端该把入口藏起来。
     * 现在的意思是"<b>这站没替你配 AI，但你可以填自己的</b>"，
     * 前端该引导用户去填 key。</blockquote>
     *
     * <p>对方是第一次打开你站点的人。他看到"AI 不可用"就走了，
     * 看到"填个 key 就能用"才会留下来——这两句话的差别就是这个功能的意义。
     */
    public AiCredentials require(String providerHeader, String userKeyHeader) {
        return resolve(providerHeader, userKeyHeader)
                .orElseThrow(() -> new NotImplementedException(
                        "这台服务器没有配置 AI。"
                                + "你可以在「AI 设置」里填上自己的 API Key（只存在你的浏览器里，"
                                + "不会上传保存），填好之后就能用了。"));
    }

    /**
     * 把厂商标识转成白名单里的枚举。
     *
     * <p>没带厂商标识时退回 DeepSeek——这是"少填一项也能用"的便利，
     * 不是安全让步：**默认值同样来自白名单，不是请求里的字符串**。
     *
     * <p>带了却认不出来则直接拒绝。不静默退回默认值，是因为那样用户
     * 填错了却看不出——他以为在用通义，实际调的是 DeepSeek，
     * 输出的风格和账单来源都对不上，而且没有任何提示。
     */
    private AiProvider resolveProvider(String providerHeader) {
        if (!hasText(providerHeader)) {
            return AiProvider.DEEPSEEK;
        }
        return AiProvider.fromName(providerHeader)
                .orElseThrow(() -> new InvalidAiCredentialsException(
                        "不认识的 AI 厂商「" + providerHeader.trim() + "」。"
                                + "可选：" + AiProvider.supportedNames()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
