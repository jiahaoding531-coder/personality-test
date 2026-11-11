package com.example.personality.repository;

import com.example.personality.entity.RecommendationFeedbackEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 自然语言反馈事件的数据访问接口。 */
public interface RecommendationFeedbackEventRepository
        extends JpaRepository<RecommendationFeedbackEvent, Long> {

    /** 按发生顺序读取一个会话的完整反馈事实。 */
    List<RecommendationFeedbackEvent> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
