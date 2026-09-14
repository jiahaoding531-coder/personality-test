package com.example.personality.service;

import com.example.personality.ai.AiReportGenerator;
import com.example.personality.dto.AiReportResponse;
import com.example.personality.dto.SessionResultResponse;
import com.example.personality.entity.AiReport;
import com.example.personality.entity.PersonalityProfile;
import com.example.personality.repository.AiReportRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * AI 个性化反馈的编排。
 *
 * <h2>为什么这个类没有 @Transactional（这是刻意的）</h2>
 *
 * <p>整个流程分三段，<b>只有第 ① ③ 段在事务里</b>：
 * <pre>
 *   ① 读画像（短事务）  →  ② 调大模型（长耗时，事务外）  →  ③ 存报告（短事务）
 * </pre>
 *
 * <p><b>为什么不能把三段包在一个事务里：</b>事务没提交时，数据库连接一直被这个
 * 请求占着。而调用大模型 API 通常要 3~30 秒。默认连接池大小是 10，只要 10 个用户
 * 同时点"生成 AI 分析"，连接池就空了，之后所有请求——包括根本不需要 AI 的取题目、
 * 查结果——全部排队等待。
 *
 * <p>这是新手最容易忽略的性能问题：代码逻辑完全正确，压测一上来就雪崩。
 * 记住一条经验法则：<b>事务里绝不调用外部 HTTP 接口。</b>
 *
 * <h2>那第 ③ 段的事务从哪来</h2>
 *
 * <p>{@code aiReportRepository.save()} 自带事务——Spring Data JPA 的
 * {@code SimpleJpaRepository} 在类上标了 {@code @Transactional(readOnly = true)}，
 * 在 {@code save/delete} 等写方法上标了 {@code @Transactional}。
 * 从非事务方法调用它，Spring 会自动为这一次 save 开一个新事务，做完就提交。
 *
 * <p>这同时规避了"自调用陷阱"：如果把存库逻辑写成本类的一个
 * {@code @Transactional} 私有方法再用 {@code this.xxx()} 调用，
 * 事务注解<b>不会生效</b>（自调用绕过了代理），而且不报任何错。
 * 交给 Repository 自带的语义最省心。
 */
@Service
public class AiReportService {

    private final AiReportGenerator aiReportGenerator;
    private final ProfileQueryService profileQueryService;
    private final AiReportRepository aiReportRepository;

    public AiReportService(AiReportGenerator aiReportGenerator,
                           ProfileQueryService profileQueryService,
                           AiReportRepository aiReportRepository) {
        this.aiReportGenerator = aiReportGenerator;
        this.profileQueryService = profileQueryService;
        this.aiReportRepository = aiReportRepository;
    }

    /**
     * 为某次会话生成 AI 反馈。
     *
     * @param regenerate 为 false 时，如果已经有报告就直接返回旧的。
     *                   这个默认行为很重要——用户误点两次不该白白消耗两次 token，
     *                   而且两次生成的文本不一样反而让人困惑。
     * @throws com.example.personality.exception.NotImplementedException
     *         当 {@code app.ai.enabled=false} 时（当前装配的是桩实现）
     * @throws com.example.personality.exception.AiServiceException
     *         调用上游失败时
     */
    public AiReportResponse generate(Long sessionId, boolean regenerate) {

        // ---------- ① 短事务：取画像（只为了拿主键 id）----------
        PersonalityProfile profile = profileQueryService.loadProfile(sessionId);

        // 已有报告且用户没要求重新生成 → 直接返回缓存，不调大模型
        if (!regenerate) {
            Optional<AiReport> existing =
                    aiReportRepository.findFirstByProfileIdOrderByCreatedAtDesc(profile.getId());
            if (existing.isPresent()) {
                return toResponse(sessionId, existing.get(), true);
            }
        }

        // 组织提示词所需的数据（又一段只读短事务）
        SessionResultResponse result = profileQueryService.buildResult(sessionId);

        // ---------- ② 事务外：调用大模型（耗时 3~30 秒）----------
        String content = aiReportGenerator.generateReport(result);

        // ---------- ③ 短事务：写库（由 Repository 自带的事务覆盖）----------
        AiReport saved = aiReportRepository.save(
                AiReport.of(profile.getId(), content, aiReportGenerator.providerName()));

        return toResponse(sessionId, saved, false);
    }

    private AiReportResponse toResponse(Long sessionId, AiReport report, boolean cached) {
        return new AiReportResponse(
                sessionId,
                report.getContent(),
                report.getProvider(),
                report.getCreatedAt(),
                cached
        );
    }
}
