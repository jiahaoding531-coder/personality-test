package com.example.personality.service;

import com.example.personality.domain.TravelDimension;
import com.example.personality.dto.TravelProfileResponse;
import com.example.personality.entity.QuestionScale;
import com.example.personality.entity.TestSession;
import com.example.personality.entity.TravelProfile;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.repository.TestSessionRepository;
import com.example.personality.repository.TravelProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Optional;
import java.util.List;

/**
 * 把数据库里的旅行画像组装成给前端的响应对象。
 *
 * <p>和人格侧的 {@code ProfileQueryService} 是平行的两个类，职责也完全一样：
 * 查实体 → 拼 DTO。分开写而不是合成一个，是因为两者的输出结构不同
 * （旅行画像只有 key/name/score，见 {@link TravelProfileResponse} 的说明），
 * 硬合会在里面塞一堆 {@code if (scale == TRAVEL)} 的分支。
 *
 * <p>这个类是<b>只读</b>的，所有方法都标了 {@code readOnly = true}——
 * Spring 会把底层连接设成只读，Hibernate 也会跳过"脏检查"。
 */
@Service
public class TravelProfileService {

    private final TestSessionRepository sessionRepository;
    private final TravelProfileRepository profileRepository;

    public TravelProfileService(TestSessionRepository sessionRepository,
                                TravelProfileRepository profileRepository) {
        this.sessionRepository = sessionRepository;
        this.profileRepository = profileRepository;
    }

    /**
     * 按会话 ID 取旅行画像实体。<b>本会话没有时，会退回用户最近一次测出来的那份。</b>
     *
     * <h2>为什么要这个 fallback</h2>
     *
     * <p>没有它的话，用户每次打开页面都得重新答 8 道题——哪怕他昨天刚答过、
     * 而且答案一个字都不会变。那是问卷，不是助手。
     *
     * <p>有了它，用户可以<b>直接用上次的画像拿推荐</b>，想更新再重测。
     *
     * <h2>画像本身仍然是「每次测试一份」</h2>
     *
     * <p>{@code travel_profiles} 依旧挂在 {@code session_id} 上、一次测试写一份，
     * 历史全都留着（可追溯）。变的只是<b>读取时</b>多找一步——
     * 而不是把画像改成挂在用户身上。
     *
     * <p>这么做的好处是：既保住了"每次测试的结果都存着"，
     * 又让用户不用重复劳动。两种需求不冲突。
     *
     * <p><b>⚠️ 匿名用户没有 fallback。</b>没有稳定的身份，"上次"就无从谈起——
     * 这和"历史记录只有登录用户才有"是同一条边界。
     *
     * <p>对外暴露实体（而不是 DTO）是刻意的：推荐服务需要
     * {@link TravelProfile#toPreferenceMap()} 拿到的整数权重去喂推荐引擎，
     * 而那是实体的能力，不是 DTO 的。
     */
    @Transactional(readOnly = true)
    public TravelProfile loadProfile(Long sessionId) {
        Optional<TravelProfile> own = profileRepository.findBySessionId(sessionId);
        if (own.isPresent()) {
            return own.get();
        }
        return findLatestProfileOfSessionOwner(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "会话 " + sessionId + " 还没有旅行画像，请先调用 submit 完成计分"));
    }

    /**
     * 找这个会话的主人在别处留下的最近一份画像。匿名会话、或从来没测过时返回空。
     *
     * <p>按会话创建时间倒序找，第一份能查到的就是最近的——
     * 因为 {@code findByUserIdAndScaleOrderByCreatedAtDesc} 已经排好序了。
     */
    private Optional<TravelProfile> findLatestProfileOfSessionOwner(Long sessionId) {
        Long userId = sessionRepository.findById(sessionId)
                .map(TestSession::getUserId)
                .orElse(null);
        if (userId == null) {
            return Optional.empty();
        }

        List<TestSession> pastSessions = sessionRepository
                .findByUserIdAndScaleOrderByCreatedAtDesc(userId, QuestionScale.TRAVEL);

        // 排除当前会话：它已经被查过且没有画像了，再查一次是白费
        List<Long> pastIds = new ArrayList<>(pastSessions.size());
        for (TestSession session : pastSessions) {
            if (!session.getId().equals(sessionId)) {
                pastIds.add(session.getId());
            }
        }
        if (pastIds.isEmpty()) {
            return Optional.empty();
        }

        List<TravelProfile> profiles = profileRepository.findBySessionIdIn(pastIds);
        if (profiles.isEmpty()) {
            return Optional.empty();
        }
        // pastIds 是按时间倒序的，按这个顺序取第一个作为"最近一次"
        for (Long id : pastIds) {
            for (TravelProfile profile : profiles) {
                if (profile.getSessionId().equals(id)) {
                    return Optional.of(profile);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 组装某次旅行会话的画像响应。
     *
     * @throws ResourceNotFoundException 会话不存在，或还没提交（没有画像）
     */
    @Transactional(readOnly = true)
    public TravelProfileResponse buildProfileResponse(Long sessionId) {
        TestSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("测试会话不存在：id=" + sessionId));

        TravelProfile profile = loadProfile(sessionId);

        // 画像是不是从别的会话借来的。前端要据此说清"这不是你刚答的"——
        // 否则用户会以为系统把他没做的测试算完了。
        boolean reused = !sessionId.equals(profile.getSessionId());

        List<TravelProfileResponse.TravelDimensionResult> dimensions =
                new ArrayList<>(TravelDimension.values().length);
        for (TravelDimension dimension : TravelDimension.values()) {
            dimensions.add(new TravelProfileResponse.TravelDimensionResult(
                    dimension.name(),
                    dimension.label(),
                    profile.scoreOf(dimension)
            ));
        }

        return new TravelProfileResponse(
                session.getId(),
                session.getStatus().name(),
                session.getCreatedAt(),
                session.getSubmittedAt(),
                session.getScale().name(),
                reused,
                dimensions
        );
    }
}
