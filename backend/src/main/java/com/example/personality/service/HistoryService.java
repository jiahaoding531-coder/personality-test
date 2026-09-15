package com.example.personality.service;

import com.example.personality.domain.Dimension;
import com.example.personality.domain.Level;
import com.example.personality.dto.SessionSummaryResponse;
import com.example.personality.dto.SessionSummaryResponse.DimensionBrief;
import com.example.personality.entity.PersonalityProfile;
import com.example.personality.entity.QuestionScale;
import com.example.personality.entity.TestSession;
import com.example.personality.repository.PersonalityProfileRepository;
import com.example.personality.repository.TestSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试历史的查询。
 *
 * <p>单独成类而不是塞进 {@code TestSessionService}，是因为职责不同：
 * 后者管的是"一次测试的生命周期"（创建 → 答题 → 提交），
 * 这里管的是"一个用户的多次测试"（列表、对比）。
 * 混在一起会让那个类同时承担两个方向的变更原因。
 */
@Service
public class HistoryService {

    /** 单次最多返回多少条历史。防止某个用户测试了几千次时把响应撑爆。 */
    private static final int MAX_ITEMS = 100;

    private final TestSessionRepository sessionRepository;
    private final PersonalityProfileRepository profileRepository;

    public HistoryService(TestSessionRepository sessionRepository,
                          PersonalityProfileRepository profileRepository) {
        this.sessionRepository = sessionRepository;
        this.profileRepository = profileRepository;
    }

    /**
     * 列出某个用户的<b>人格测试</b>历史，最近的在前。
     *
     * <p><b>查询次数是固定的 2 次，与历史条数无关</b>——这是刻意设计的。
     * 如果写成"遍历会话、逐个查画像"，20 条历史就是 21 次数据库往返，
     * 这就是常说的 N+1 查询问题。这里先一次性取出全部画像放进 Map，
     * 再在内存里配对。
     *
     * <p><b>⚠️ 只返回人格会话，不带旅行会话。</b>
     *
     * <p>这个接口返回的是 {@code DimensionBrief}——5 个人格维度的简要分数，
     * 它对应的数据源是 {@code personality_profiles}。而旅行会话的画像存在
     * {@code travel_profiles} 里，在这里根本查不到，结果是：一个已经提交过的
     * 旅行测试会被当成"没有画像"→ 前端显示成「未完成」→ 点进去 404。
     *
     * <p>所以过滤收在 Repository 的查询方法里（{@code findByUserIdAndScale...}），
     * 而不是在内存里 {@code if} 一下——顺带也少了把旅行会话查出来又丢掉的开销。
     *
     * <p>旅行记录什么时候进历史？等反馈功能做完以后——那时候一条旅行记录
     * 才有"当时推了什么、用户点了什么"可看，否则点进去只能看到一个画像，
     * 和历史里点人格记录看到完整结果是不对等的。
     *
     * @param userId 当前登录用户的 id
     */
    @Transactional(readOnly = true)
    public List<SessionSummaryResponse> listForUser(Long userId) {
        List<TestSession> sessions = sessionRepository.findByUserIdAndScaleOrderByCreatedAtDesc(
                userId, QuestionScale.PERSONALITY);
        if (sessions.isEmpty()) {
            return List.of();
        }

        // 只取前 MAX_ITEMS 条去查画像，避免为根本不会返回的记录白查一遍
        List<TestSession> visible = sessions.size() > MAX_ITEMS
                ? sessions.subList(0, MAX_ITEMS)
                : sessions;

        List<Long> sessionIds = new ArrayList<>(visible.size());
        for (TestSession session : visible) {
            sessionIds.add(session.getId());
        }

        // 一次查询取出全部画像，做成 Map<sessionId, profile>
        Map<Long, PersonalityProfile> profileBySessionId = new HashMap<>();
        for (PersonalityProfile profile : profileRepository.findBySessionIdIn(sessionIds)) {
            profileBySessionId.put(profile.getSessionId(), profile);
        }

        List<SessionSummaryResponse> result = new ArrayList<>(visible.size());
        for (TestSession session : visible) {
            PersonalityProfile profile = profileBySessionId.get(session.getId());
            result.add(new SessionSummaryResponse(
                    session.getId(),
                    session.getCreatedAt(),
                    session.getSubmittedAt(),
                    session.getStatus().name(),
                    // 还没提交的会话没有画像——这是正常情况（用户答到一半就关了页面），
                    // 返回空列表而不是报错。前端会显示成"未完成"。
                    profile == null ? List.of() : briefsOf(profile)
            ));
        }
        return result;
    }

    private static List<DimensionBrief> briefsOf(PersonalityProfile profile) {
        List<DimensionBrief> briefs = new ArrayList<>(Dimension.values().length);
        for (Dimension dimension : Dimension.values()) {
            BigDecimal score = profile.scoreOf(dimension);
            briefs.add(new DimensionBrief(
                    dimension.name(),
                    dimension.label(),
                    score,
                    Level.fromScore(score).label()
            ));
        }
        return briefs;
    }
}
