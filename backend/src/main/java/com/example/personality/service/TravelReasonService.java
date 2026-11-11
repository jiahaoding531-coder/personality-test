package com.example.personality.service;

import com.example.personality.ai.AiCredentials;
import com.example.personality.ai.TravelReasonGenerator;
import com.example.personality.ai.TravelReasonEvidence;
import com.example.personality.ai.DeepSeekTravelReasonGenerator;
import com.example.personality.exception.AiServiceException;
import com.example.personality.exception.AiRequestRejectedException;
import com.example.personality.exception.InvalidAiCredentialsException;
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
     * <p>AI关闭/缺少凭据由控制器保持既有501契约；已进入解释流程后的模型错误、
     * 格式错误和保护拒绝使用本批次确定性解释，不自动重试。
     */
    public TravelReasonResponse generate(Long sessionId, boolean regenerate,
                                         AiCredentials credentials) {
        return generate(sessionId,regenerate,credentials,null);
    }

    public TravelReasonResponse generate(Long sessionId, boolean regenerate,
                                         AiCredentials credentials,Integer batchNo) {

        // ---------- ① 短事务：重建"这批是怎么算的" ----------
        RecommendationService.ReasonContext context =
                batchNo == null ? recommendationService.buildReasonContext(sessionId)
                        : recommendationService.buildReasonContext(sessionId,batchNo);
        var catalog=TravelReasonEvidence.from(context.input());
        Map<Integer,TravelReasonEvidence.CacheSelection> selected=new LinkedHashMap<>();
        boolean untrustedCache=false;
        for(int rank:catalog.ranks()) {
            String raw=context.existingReasons().get(rank);
            if(raw==null) continue;
            var cached=catalog.readCache(rank,raw);
            if(cached.isPresent()) selected.put(rank,cached.get()); else untrustedCache=true;
        }
        boolean cached=!regenerate && !untrustedCache && selected.size()==catalog.ranks().size();
        if(!cached) {
            // 发现旧/跨批次/损坏缓存时直接本地修复，不为替换不可信缓存自动付费。
            List<TravelReasonGenerator.RankedReason> generated;
            if(untrustedCache) generated=DeepSeekTravelReasonGenerator.fallback(catalog);
            else {
                try {generated=reasonGenerator.generateReasons(credentials,context.input());}
                catch(AiServiceException | AiRequestRejectedException | InvalidAiCredentialsException unavailable) {
                    generated=DeepSeekTravelReasonGenerator.fallback(catalog);
                }
            }
            selected.clear();
            // 即使未来更换生成器，也只接收可验证ID，绝不信任生成器携带的文本。
            for(var ranked:generated) {
                if(selected.containsKey(ranked.rank())) continue;
                try {
                    catalog.render(ranked.rank(),ranked.evidenceIds());
                    selected.put(ranked.rank(),new TravelReasonEvidence.CacheSelection(ranked.evidenceIds(),ranked.modelSelected()));
                } catch(IllegalArgumentException invalid) { /* 下方统一补本地解释 */ }
            }
            for(var fallback:DeepSeekTravelReasonGenerator.fallback(catalog)) {
                selected.putIfAbsent(fallback.rank(),new TravelReasonEvidence.CacheSelection(fallback.evidenceIds(),false));
            }
            Map<Integer,String> envelopes=new LinkedHashMap<>();
            for(var entry:selected.entrySet()) envelopes.put(entry.getKey(),catalog.encodeCache(entry.getKey(),
                    entry.getValue().evidenceIds(),entry.getValue().modelSelected()));
            // 保存的是带版本/批次指纹的ID，而不是模型自由文本。仍按最初批次回填。
            recommendationService.attachReasons(sessionId,context.batchNo(),envelopes);
        }
        Map<Integer,String> rendered=new LinkedHashMap<>();
        boolean modelSelected=false;
        for(var entry:selected.entrySet()) {
            rendered.put(entry.getKey(),catalog.render(entry.getKey(),entry.getValue().evidenceIds()));
            modelSelected |= entry.getValue().modelSelected();
        }
        return toResponse(sessionId,context,rendered,cached,modelSelected?credentials.providerName():"local:evidence-v1");
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
                                            boolean cached,
                                            String providerName) {
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
                providerName,
                cached,
                reasons);
    }
}
