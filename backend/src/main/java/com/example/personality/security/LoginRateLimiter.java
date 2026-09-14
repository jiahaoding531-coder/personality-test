package com.example.personality.security;

import com.example.personality.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录/注册的速率限制，用来挡住暴力破解。
 *
 * <h2>没有它会发生什么</h2>
 *
 * <p>登录接口是攻击者唯一能"无限次尝试"的入口。没有限制的话，
 * 对方可以拿一个密码字典对着某个账号刷几百万次——
 * BCrypt 让每次校验要 100ms，听起来很慢，但攻击者可以并发几百个请求，
 * 而且他自己在本机算哈希更快。**限流是这条防线上最后也最有效的一环。**
 *
 * <h2>为什么按 IP 和用户名各限一套</h2>
 *
 * <p>只用一种都有明显漏洞：
 * <ul>
 *   <li><b>只按 IP</b>：攻击者换个代理 IP 就能继续打同一个账号。
 *       而且大量正常用户可能共用同一个出口 IP（公司、学校、运营商 NAT），
 *       按 IP 限死会误伤。</li>
 *   <li><b>只按用户名</b>：一个 IP 可以拿同一批密码去扫成千上万个账号
 *       （叫「撞库」，因为很多人多个网站用同一个密码）。</li>
 * </ul>
 *
 * <p>两套同时生效，任一套超限就拦。两套的阈值也不同：
 * 用户名那套更严（保护具体账号），IP 那套更松（因为会误伤共用出口的人）。
 *
 * <h2>算法：滑动窗口</h2>
 *
 * <p>给每个 key 维护一个「失败时间戳」队列，只统计窗口内的。
 *
 * <p>为什么不用更简单的「固定窗口」（比如"每 15 分钟最多 5 次"，
 * 到点清零）？因为它在窗口边界会放过最多两倍的流量：
 * 攻击者可以在 14:59 打 5 次、15:01 再打 5 次，8 分钟内实际打了 10 次。
 * 滑动窗口没有这个缝。
 *
 * <h2>⚠️ 单实例限制</h2>
 *
 * <p>计数器存在本机内存里。如果将来部署多个实例，每个实例各算各的，
 * 实际允许的尝试次数会翻倍。到那时把存储换成 Redis 即可
 * （用 {@code ZADD}/{@code ZREMRANGEBYSCORE} 实现滑动窗口），
 * 这个类的接口不用改。
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /**
     * 同一个账号在窗口内允许的失败次数。
     *
     * <p>取 5 是个折中：正常人打错两三次密码很常见，5 次够用；
     * 而 5 次/15 分钟 = 每天约 480 次，对 8 位以上的密码空间来说
     * 暴力破解完全不可行。
     */
    public static final int MAX_FAILURES_PER_USERNAME = 5;

    public static final Duration USERNAME_WINDOW = Duration.ofMinutes(15);

    /**
     * 同一个 IP 在窗口内允许的失败次数。
     *
     * <p>比用户名那套宽松得多，因为一个出口 IP 背后可能是整栋办公楼。
     * 它的作用是拦住「一个 IP 扫大量账号」的撞库行为，不是拦住单账号破解。
     */
    public static final int MAX_FAILURES_PER_IP = 20;

    public static final Duration IP_WINDOW = Duration.ofMinutes(15);

    /**
     * 同一个 IP 在窗口内允许的注册次数。
     *
     * <p>注册不像登录那样有"密码空间"要保护，它防的是**批量刷账号**：
     * 灌垃圾数据、占用户名、或者把这里当成免费的哈希计算服务
     * （每次注册要跑一次 BCrypt）。所以窗口长、阈值小。
     */
    public static final int MAX_REGISTRATIONS_PER_IP = 10;

    public static final Duration REGISTRATION_WINDOW = Duration.ofHours(1);

    /** 所有窗口里最长的那个，清理过期条目时用它做判断标准。 */
    private static final Duration LONGEST_WINDOW =
            REGISTRATION_WINDOW.toMinutes() > IP_WINDOW.toMinutes()
                    ? REGISTRATION_WINDOW
                    : IP_WINDOW;

    /** 跟踪的 key 数量上限。超过就触发一次清理，防止内存无限增长。 */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, ArrayDeque<Long>> failureWindows = new ConcurrentHashMap<>();

    /**
     * 检查是否允许这次尝试。超限就抛异常。
     *
     * <p><b>注意应该在校验密码之前调用</b>——否则攻击者即使被拦，
     * 也已经消耗掉了服务端的 BCrypt 计算量（每次 100ms），
     * 等于给了对方一个低成本的资源耗尽攻击面。
     */
    public void checkAllowed(String clientIp, String username) {
        long now = System.currentTimeMillis();
        checkOne(ipKey(clientIp), MAX_FAILURES_PER_IP, IP_WINDOW, now,
                "来自该网络的登录尝试过于频繁");
        checkOne(userKey(username), MAX_FAILURES_PER_USERNAME, USERNAME_WINDOW, now,
                "该账号的登录尝试过于频繁");
    }

    /** 记录一次失败的尝试（密码错误、或用户不存在）。 */
    public void recordFailure(String clientIp, String username) {
        long now = System.currentTimeMillis();
        addAttempt(ipKey(clientIp), now);
        addAttempt(userKey(username), now);
    }

    /**
     * 登录成功后清除该账号的失败计数。
     *
     * <p><b>只清用户名那套，不清 IP 那套。</b>为什么？如果成功一次就把 IP
     * 计数清零，攻击者只要手里有一个自己注册的账号，就能在扫别人密码的过程中
     * 反复登录自己账号来重置 IP 计数——限流形同虚设。
     *
     * <p>清用户名计数则是必要的：用户打错几次密码后终于想起来正确的了，
     * 不该继续背着之前那几次失败。
     */
    public void recordSuccess(String username) {
        failureWindows.remove(userKey(username));
    }

    // ==========================================================
    // 注册限流
    // ==========================================================

    /**
     * 检查是否允许这次注册。
     *
     * <p>注册和登录的语义不同：登录记的是**失败**次数（成功要清零），
     * 注册记的是**全部尝试**次数（不管成功失败都算）——
     * 因为批量注册成功本身就是要防的行为。
     */
    public void checkRegistrationAllowed(String clientIp) {
        checkOne(registrationKey(clientIp), MAX_REGISTRATIONS_PER_IP,
                REGISTRATION_WINDOW, System.currentTimeMillis(),
                "注册过于频繁");
    }

    /** 记录一次注册尝试（成功或失败都算）。 */
    public void recordRegistration(String clientIp) {
        addAttempt(registrationKey(clientIp), System.currentTimeMillis());
    }

    /** 清空全部计数。仅供测试使用。 */
    public void clearAll() {
        failureWindows.clear();
    }

    // ==========================================================
    // 内部实现
    // ==========================================================

    private void checkOne(String key, int max, Duration window, long now, String messagePrefix) {
        ArrayDeque<Long> window_ = failureWindows.get(key);
        if (window_ == null) {
            return;
        }
        synchronized (window_) {
            prune(window_, now - window.toMillis());
            if (window_.size() >= max) {
                Long oldest = window_.peekFirst();
                if (oldest == null) {
                    return;
                }
                long retryAfterMs = oldest + window.toMillis() - now;
                long retryAfterSec = Math.max(1, (retryAfterMs + 999) / 1000);
                log.warn("触发限流 key={} 窗口内失败 {} 次", key, window_.size());
                throw new RateLimitExceededException(
                        messagePrefix + "，请 " + retryAfterSec + " 秒后再试", retryAfterSec);
            }
        }
    }

    private void addAttempt(String key, long now) {
        if (failureWindows.size() > MAX_TRACKED_KEYS) {
            sweepStaleEntries(now);
        }
        ArrayDeque<Long> window = failureWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(now);
        }
    }

    /** 丢掉窗口外的旧记录（滑动窗口的核心）。 */
    private static void prune(ArrayDeque<Long> window, long cutoff) {
        while (!window.isEmpty()) {
            Long head = window.peekFirst();
            if (head == null || head >= cutoff) {
                break;
            }
            window.pollFirst();
        }
    }

    /**
     * 清理长期没有活动的 key。
     *
     * <p>用**最长的窗口**做判断标准是安全的：某个 key 最后一条记录
     * 比最长窗口还旧，那它在任何一套规则下都不可能再触发限流了。
     *
     * <p>如果不做这个清理，攻击者可以用海量随机用户名把内存撑爆——
     * 每次请求都造一个新 key，一天就能塞进几百万条。
     */
    private void sweepStaleEntries(long now) {
        long cutoff = now - LONGEST_WINDOW.toMillis();
        int removed = 0;
        Iterator<Map.Entry<String, ArrayDeque<Long>>> it = failureWindows.entrySet().iterator();
        while (it.hasNext()) {
            ArrayDeque<Long> window = it.next().getValue();
            synchronized (window) {
                prune(window, cutoff);
                if (window.isEmpty()) {
                    it.remove();
                    removed++;
                }
            }
        }
        log.debug("限流器清理过期条目 {} 个，剩余 {}", removed, failureWindows.size());
    }

    /**
     * 用户名统一转小写。
     *
     * <p>否则 {@code Alice} 和 {@code alice} 会被当成两个 key，
     * 攻击者只要换大小写就能绕过针对某个账号的限流。
     *
     * <p>（真正的用户名校验在注册时就限制了字符集，
     * 大小写敏感性的最终裁决在数据库的唯一约束那边。
     * 这里统一小写是为了让限流更严格，宁严勿松。）
     */
    private static String userKey(String username) {
        return "user:" + (username == null ? "" : username.toLowerCase());
    }

    /**
     * IP 作为 key。
     *
     * <p><b>⚠️ 用的是 {@code request.getRemoteAddr()}，不读 X-Forwarded-For。</b>
     * 因为那个头是客户端可以随便伪造的——一旦信任它，攻击者只要每次请求
     * 换一个假 IP，限流就完全失效了。
     *
     * <p>如果将来部署在 Nginx / 负载均衡后面，所有请求的 remoteAddr 都会变成
     * 代理的地址，这时**不能**手写代码去读 X-Forwarded-For，
     * 而应该在配置里开启 {@code server.forward-headers-strategy=native}，
     * 让 Spring Boot 只在信任的代理链路上解析这个头。
     */
    private static String ipKey(String clientIp) {
        return "ip:" + (clientIp == null ? "unknown" : clientIp);
    }

    /**
     * 注册计数用的 key。
     *
     * <p>刻意和登录的 IP key（{@code ip:}）分开——两者语义不同
     * （一个记失败、一个记全部尝试），窗口长度也不同，混在一起会互相干扰。
     */
    private static String registrationKey(String clientIp) {
        return "reg:" + (clientIp == null ? "unknown" : clientIp);
    }
}
