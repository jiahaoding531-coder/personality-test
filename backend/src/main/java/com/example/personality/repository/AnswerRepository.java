package com.example.personality.repository;

import com.example.personality.entity.Answer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 作答记录的数据访问接口。
 */
public interface AnswerRepository extends JpaRepository<Answer, Long> {

    /**
     * 取出某次会话的全部作答。
     *
     * <p>方法名解析规则：{@code findBy} + {@code SessionId}（实体字段名 sessionId）
     * → {@code WHERE session_id = ?}。注意这里写的是<b>Java 字段名</b>
     * （驼峰 sessionId），不是数据库列名（下划线 session_id），
     * Spring Data 会自动做驼峰转下划线。
     */
    List<Answer> findBySessionId(Long sessionId);

    /** 某次会话已作答的题目数量。用于提交前校验是否答完。 */
    long countBySessionId(Long sessionId);
}
