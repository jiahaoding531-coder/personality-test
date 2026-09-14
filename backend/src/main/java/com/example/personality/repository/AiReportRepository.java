package com.example.personality.repository;

import com.example.personality.entity.AiReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * AI 报告的数据访问接口。
 */
public interface AiReportRepository extends JpaRepository<AiReport, Long> {

    /**
     * 取某个画像<b>最新</b>的一份报告。
     *
     * <p>方法名里的 {@code First} 和 {@code OrderByCreatedAtDesc} 组合起来，
     * Spring Data 生成的 SQL 大致是：
     * <pre>
     *   SELECT * FROM ai_reports
     *   WHERE profile_id = ?
     *   ORDER BY created_at DESC
     *   LIMIT 1
     * </pre>
     *
     * <p>因为一个画像可以有多份报告（用户点"重新生成"就会多一条），
     * 所以必须显式排序取最新——不排序的话数据库返回哪一条是不确定的。
     */
    Optional<AiReport> findFirstByProfileIdOrderByCreatedAtDesc(Long profileId);

    /** 某个画像的全部报告，按时间倒序。为将来做"历史版本对比"预留。 */
    List<AiReport> findByProfileIdOrderByCreatedAtDesc(Long profileId);

    /** 是否已经生成过报告。用于避免重复点击时白白消耗 token。 */
    boolean existsByProfileId(Long profileId);
}
