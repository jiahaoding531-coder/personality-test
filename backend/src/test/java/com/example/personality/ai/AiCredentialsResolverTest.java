package com.example.personality.ai;

import com.example.personality.config.AiProperties;
import com.example.personality.exception.InvalidAiCredentialsException;
import com.example.personality.exception.NotImplementedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据解析的单元测试。
 *
 * <p><b>不需要数据库、不联网、不花钱。</b>
 *
 * <p>这个类覆盖的是这次改动里**最该被盯住的部分**——厂商白名单、
 * 地址从哪来、优先级顺序。它们全是纯逻辑，所以能毫秒级跑完；
 * 而"带着凭据去调模型"那部分必须联网，又慢又贵，把能测的挤到这一侧
 * 是 {@link AiCredentialsResolver} 单独存在的第二个理由。
 */
class AiCredentialsResolverTest {

    private static final String SERVER_KEY = "sk-server-side-key";
    private static final String USER_KEY = "sk-visitor-own-key";

    // ==========================================================
    // 优先级：访客的 key 优先
    // ==========================================================

    @Test
    @DisplayName("【核心】访客带了 key → 用他的，服务端那把靠边站")
    void userKeyWinsOverServerKey() {
        AiCredentials credentials = resolver(true, SERVER_KEY)
                .resolve("DEEPSEEK", USER_KEY).orElseThrow();

        assertEquals(USER_KEY, credentials.apiKey());
        assertTrue(credentials.userSupplied());
    }

    @Test
    @DisplayName("访客没带 → 用服务端配置的那把")
    void fallsBackToServerKey() {
        AiCredentials credentials = resolver(true, SERVER_KEY)
                .resolve(null, null).orElseThrow();

        assertEquals(SERVER_KEY, credentials.apiKey());
        assertFalse(credentials.userSupplied());
    }

    @Test
    @DisplayName("两边都没有 → 空（调用方据此返回 501）")
    void noCredentialsYieldsEmpty() {
        assertTrue(resolver(false, "").resolve(null, null).isEmpty());
    }

    @Test
    @DisplayName("服务端 enabled=true 但没配 key，仍然算没有")
    void enabledWithoutKeyIsStillNothing() {
        // ⚠️ 这个组合很容易漏：开关打开了、key 是空的。
        //    不判 key 是否为空的话，就会拿着一个空 key 去调上游，
        //    然后拿到一个语焉不详的 401——而真正的问题是配置漏了一项。
        assertTrue(resolver(true, "   ").resolve(null, null).isEmpty());
    }

    // ==========================================================
    // ⚠️ SSRF：地址只能来自白名单
    // ==========================================================

    @Test
    @DisplayName("【安全】地址只来自白名单，请求里的任何字符串都变不成地址")
    void baseUrlAlwaysComesFromTheWhitelist() {
        for (AiProvider provider : AiProvider.values()) {
            AiCredentials credentials = resolver(true, SERVER_KEY)
                    .resolve(provider.name(), USER_KEY).orElseThrow();

            assertEquals(provider.baseUrl(), credentials.baseUrl(),
                    provider + " 的地址必须来自白名单常量");
        }
    }

    @Test
    @DisplayName("【安全】把内网地址、云元数据端点塞进厂商头 → 400 拒绝")
    void ssrfAttemptsAreRejected() {
        AiCredentialsResolver resolver = resolver(true, SERVER_KEY);

        // 169.254.169.254 是云厂商的元数据端点：能读到实例的临时凭证，
        // 是云上最经典的失陷路径。这里它只是一个"不认识的厂商名"，
        // 因为解析器只做"查表命中"，**没有"拿它拼 URL"这种可能**。
        String[] attempts = {
                "http://169.254.169.254/latest/meta-data",
                "http://192.168.1.1/admin",
                "http://localhost:8080/api/me",
                "file:///etc/passwd",
                "https://evil.example.com/v1",
        };

        for (String attempt : attempts) {
            assertThrows(InvalidAiCredentialsException.class,
                    () -> resolver.resolve(attempt, USER_KEY),
                    "「" + attempt + "」必须被拒绝，绝不能被当成地址");
        }
    }

    @Test
    @DisplayName("认不出的厂商拒绝时要列出合法取值，用户不用去翻文档")
    void rejectionMessageListsSupportedProviders() {
        InvalidAiCredentialsException e = assertThrows(InvalidAiCredentialsException.class,
                () -> resolver(true, SERVER_KEY).resolve("OPENAI", USER_KEY));

        for (AiProvider provider : AiProvider.values()) {
            assertTrue(e.getMessage().contains(provider.name()),
                    "报错信息应该列出 " + provider.name());
        }
    }

    // ==========================================================
    // 边界
    // ==========================================================

    @Test
    @DisplayName("key 是空白串等于没带")
    void blankKeyCountsAsAbsent() {
        AiCredentialsResolver resolver = resolver(true, SERVER_KEY);

        assertEquals(SERVER_KEY, resolver.resolve(null, "").orElseThrow().apiKey());
        assertEquals(SERVER_KEY, resolver.resolve(null, "   ").orElseThrow().apiKey());
    }

    @Test
    @DisplayName("key 前后的空白会被去掉")
    void keyIsTrimmed() {
        // 从网页上复制粘贴 key 时经常带上首尾空格或换行，
        // 带着它们去调上游会得到一个 401，而用户怎么看那个 key 都是对的
        AiCredentials credentials = resolver(true, SERVER_KEY)
                .resolve("DEEPSEEK", "  " + USER_KEY + "\n").orElseThrow();

        assertEquals(USER_KEY, credentials.apiKey());
    }

    @Test
    @DisplayName("不带厂商时退回默认（DeepSeek），但默认值同样来自白名单")
    void missingProviderFallsBackToDefault() {
        AiCredentials credentials = resolver(true, SERVER_KEY)
                .resolve(null, USER_KEY).orElseThrow();

        assertEquals(AiProvider.DEEPSEEK, credentials.provider());
        assertEquals(AiProvider.DEEPSEEK.baseUrl(), credentials.baseUrl());
    }

    @Test
    @DisplayName("厂商名大小写不敏感")
    void providerNameIsCaseInsensitive() {
        assertEquals(AiProvider.MOONSHOT,
                resolver(true, SERVER_KEY).resolve("moonshot", USER_KEY).orElseThrow().provider());
    }

    @Test
    @DisplayName("这个部署关掉 BYOK 时，访客带的 key 会被忽略")
    void userKeysCanBeDisabled() {
        AiCredentialsResolver resolver = new AiCredentialsResolver(
                properties(true, SERVER_KEY, false));

        AiCredentials credentials = resolver.resolve("MOONSHOT", USER_KEY).orElseThrow();

        assertEquals(SERVER_KEY, credentials.apiKey(), "应该忽略访客的 key");
        assertFalse(credentials.userSupplied());
    }

    @Test
    @DisplayName("require：没有凭据时抛 501，且提示用户去填自己的 key")
    void requireThrowsNotImplementedWithHelpfulMessage() {
        NotImplementedException e = assertThrows(NotImplementedException.class,
                () -> resolver(false, "").require(null, null));

        // ⚠️ 这段话是真实用户会看到的。以前 501 的意思是"这站没有 AI"，
        //    用户看到就走了；现在的意思是"你可以填自己的"，
        //    这两句话的差别就是这个功能的全部意义。
        assertTrue(e.getMessage().contains("自己的"), "要告诉用户可以填自己的 key，实际：" + e.getMessage());
    }

    // ==========================================================
    // ⚠️ key 绝不能出现在任何会被打出来的字符串里
    // ==========================================================

    @Test
    @DisplayName("【安全】providerName() 里不含 key")
    void providerNameNeverContainsTheKey() {
        // providerName 会随接口响应返回、也会进日志。
        // 哪天有人为了"方便排查"把它改成带上 key，这条会立刻红。
        for (AiProvider provider : AiProvider.values()) {
            String name = AiCredentials.forUser(provider, USER_KEY).providerName();
            assertFalse(name.contains(USER_KEY), "providerName 里出现了 key：" + name);
        }

        String server = AiCredentials.forServer("https://api.deepseek.com/v1",
                "deepseek-chat", SERVER_KEY).providerName();
        assertFalse(server.contains(SERVER_KEY));
    }

    @Test
    @DisplayName("providerName 能区分是哪个厂商、哪个模型")
    void providerNameIdentifiesProviderAndModel() {
        assertEquals("moonshot:moonshot-v1-8k",
                AiCredentials.forUser(AiProvider.MOONSHOT, USER_KEY).providerName());
        assertEquals("volcengine:doubao-seed-2-0-lite-260215",
                AiCredentials.forUser(AiProvider.VOLCENGINE, USER_KEY).providerName());
        assertEquals("custom:my-model",
                AiCredentials.forServer("http://localhost:11434/v1", "my-model", "x").providerName());
    }

    @Test
    @DisplayName("火山方舟使用官方 OpenAI 兼容地址和豆包默认模型")
    void volcengineUsesArkEndpointAndDoubaoModel() {
        AiCredentials credentials = resolver(true, SERVER_KEY)
                .resolve("VOLCENGINE", USER_KEY).orElseThrow();

        assertEquals(AiProvider.VOLCENGINE, credentials.provider());
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", credentials.baseUrl());
        assertEquals("doubao-seed-2-0-lite-260215", credentials.model());
    }

    // ==========================================================
    // 夹具
    // ==========================================================

    private static AiCredentialsResolver resolver(boolean serverEnabled, String serverKey) {
        return new AiCredentialsResolver(properties(serverEnabled, serverKey, true));
    }

    private static AiProperties properties(boolean enabled, String key, boolean allowUserKeys) {
        AiProperties properties = new AiProperties();
        properties.setEnabled(enabled);
        properties.setApiKey(key);
        properties.setAllowUserKeys(allowUserKeys);
        properties.setBaseUrl("https://api.deepseek.com/v1");
        properties.setModel("deepseek-chat");
        return properties;
    }
}
