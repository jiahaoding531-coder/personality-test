package com.example.personality.repository;

import com.example.personality.entity.TravelProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TravelProfileRepository extends JpaRepository<TravelProfile, Long> {

    Optional<TravelProfile> findBySessionId(Long sessionId);

    boolean existsBySessionId(Long sessionId);

    /**
     * 多个会话的旅行画像。
     *
     * <p>用来做「画像跨会话复用」：用户这次没测，就从他<b>历史上</b>的会话里
     * 找一份最近的画像顶上（见 {@code TravelProfileService.loadProfile}）。
     *
     * <p>一次查完，而不是逐个会话去查——那会变成 N+1。
     */
    List<TravelProfile> findBySessionIdIn(Collection<Long> sessionIds);
}
