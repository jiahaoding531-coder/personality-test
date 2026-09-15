package com.example.personality.service;

import com.example.personality.domain.TravelDimension;
import com.example.personality.dto.TravelProfileResponse;
import com.example.personality.entity.TestSession;
import com.example.personality.entity.TravelProfile;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.repository.TestSessionRepository;
import com.example.personality.repository.TravelProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
     * 按会话 ID 取旅行画像实体。
     *
     * <p>对外暴露实体（而不是 DTO）是刻意的：推荐服务需要
     * {@link TravelProfile#toPreferenceMap()} 拿到的整数权重去喂推荐引擎，
     * 而那是实体的能力，不是 DTO 的。
     *
     * <p>把"查不到就抛 404"的逻辑收在这一处，避免调用方各写一遍判空。
     */
    @Transactional(readOnly = true)
    public TravelProfile loadProfile(Long sessionId) {
        return profileRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "会话 " + sessionId + " 还没有旅行画像，请先调用 submit 完成计分"));
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
                dimensions
        );
    }
}
