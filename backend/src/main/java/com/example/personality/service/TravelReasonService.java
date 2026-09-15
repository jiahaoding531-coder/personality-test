package com.example.personality.service;

import com.example.personality.ai.TravelReasonGenerator;
import com.example.personality.dto.TravelReasonResponse;
import com.example.personality.entity.Recommendation;
import com.example.personality.repository.RecommendationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 推荐理由的编排。
 *
 * <h2>⚠️ 这个类没有 {@code @Transactional}，而且是故意的</h2>
 *
 * <p>流程分三段，<b>只有 ① ③ 在事务里</b>：
 * <pre>
 *   ① 读依据（短事务）  →  ② 调大模型（几秒，事务外）  →  ③ 写理由（短事务）
 * </pre>
 *
 * <p>把三段包成一个事务的话，整个大模型调用期间数据库连接一直被占着。
 * 连接池上限是 10，只要十来个人同时点，池子就空了，之后所有请求——
 * 包括根本不需要 AI 的取题目、看历史——全部排队等待。
 *
 * <p>这是新手最容易忽略的一类问题：<b>代码逻辑完全正确，压测一上来就雪崩。</b>
 * 经验法则是"事务里绝不调用外部 HTTP 接口"，这个项目已经吃过两次亏
 * （见 {@code AiReportService} 和 {@link AmbientService} 的类注释）。
 *
 * <p>①③ 的事务由 {@code RecommendationService} 上那两个字面
 * {@code @Transactional} 提供——它们是<b>另一个 Bean 的方法</b>，
 * 所以 Spring 的代理正常生效。这也是为什么不把读写写成本类的私有方法
 * 再用 {@code this.xxx()} 调：自调用会绕过代理，事务注解<b>静默失效</b>。
 */
@Service
public class TravelReasonService {

    private final RecommendationService recommendationService;
    private final RecommendationRepository recommendationRepository;
    private final TravelReasonGenerator reasonGenerator;

    public TravelReasonService(RecommendationService recommendationService,
                               RecommendationRepository recommendationRepository,
                               TravelReasonGenerator reasonGenerator) {
        this.recommendationService = recommendationService;
        this.recommendationRepository = recommendationRepository;
        this.reasonGenerator = reasonGenerator;
    }

    /**
     * 为这个会话最新一批推荐生成理由。
     *
     * @param regenerate false 时，如果这一批已经都有理由了就直接返回，
     *                   <b>不调用大模型</b>。这个默认行为很重要：用户误点两次
     *                   不该白白消耗两次 token，而且两次生成的文本不一样
     *                   反而让人困惑（和人格报告那边是同一个取舍）。
     * @throws com.example.personality.exception.NotImplementedException
     *         未配置 AI 时（装配的是桩实现）→ HTTP 501
     * @throws com.example.personality.exception.AiServiceException
     *         调用上游失败、或返回的内容解析不出来 → HTTP 502
     */
    public TravelReasonResponse generate(Long sessionId, boolean regenerate) {

        // ---------- ① 短事务：重建"这批是怎么算的" ----------
        RecommendationService.ReasonContext context =
                recommendationService.buildReasonContext(sessionId);

        int placeCount = context.input().places().size();

        // 已经都有理由了，而且用户没要求重新生成 → 直接吃缓存
        if (!regenerate && context.existingReasons().size() >= placeCount) {
            return toResponse(sessionId, context, context.existingReasons(), true);
        }

        // ---------- ② 事务外：调用大模型（几秒） ----------
        List<TravelReasonGenerator.RankedReason> generated =
                reasonGenerator.generateReasons(context.input());

        // 名次 → 理由。模型偶尔会同一个名次给两条，保留先出现的那个。
        Map<Integer, String> byRank = new LinkedHashMap<>();
        for (TravelReasonGenerator.RankedReason ranked : generated) {
            byRank.putIfAbsent(ranked.rank(), ranked.reason());
        }

        // ---------- ③ 短事务：回填到推荐记录 ----------
        recommendationService.attachReasons(sessionId, context.batchNo(), byRank);

        return toResponse(sessionId, context, byRank, false);
    }

    /**
     * 组装响应。
     *
     * <p>要把理由从"第几名"翻译成"哪条推荐记录"：模型只知道
     * "第 1 名该说什么"，而前端要知道"墙上这张卡片该显示什么"——
     * 后者只有 {@code recommendationId} 能唯一表达（名次只在一批之内有意义）。
     *
     * <p>⚠️ 名次和 id 的对应关系<b>查一次库、建一张表</b>就够，
     * 不要在每个名次上单独查一次——那是 N+1，三个地点就是三次往返。
     */
    private TravelReasonResponse toResponse(Long sessionId,
                                            RecommendationService.ReasonContext context,
                                            Map<Integer, String> reasonByRank,
                                            boolean cached) {
        Map<Integer, Long> idByRank = new LinkedHashMap<>();
        for (Recommendation row : recommendationRepository
                .findBySessionIdAndBatchNoOrderByRankNoAsc(sessionId, context.batchNo())) {
            idByRank.put(row.getRankNo(), row.getId());
        }

        // TreeMap 只为让输出按名次升序——响应顺序稳定，前端和测试都不必自己排
        List<TravelReasonResponse.PlaceReason> reasons = new ArrayList<>(reasonByRank.size());
        for (Map.Entry<Integer, String> entry : new TreeMap<>(reasonByRank).entrySet()) {
            Long recommendationId = idByRank.get(entry.getKey());
            if (recommendationId != null) {
                reasons.add(new TravelReasonResponse.PlaceReason(
                        recommendationId, entry.getKey(), entry.getValue()));
            }
        }

        return new TravelReasonResponse(
                sessionId,
                context.batchNo(),
                reasonGenerator.providerName(),
                cached,
                reasons);
    }
}
