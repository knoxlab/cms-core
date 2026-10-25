package com.powercms.cms.systemlog.repo;

import com.powercms.cms.systemlog.entity.AccessLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccessLogRepository extends JpaRepository<AccessLogEntity, UUID> {

    /**
     * 稳定游标检索（FR-LOG-QR-003）：时间倒序 + id 兜底；可见域与 RuntimeLogRepository 同规则。
     * <p>cursorTime 的 null-check 用 {@code cast(:cursorTime as timestamp) is null} 锚定类型，
     * 原因同 {@link RuntimeLogRepository#search}（PG 无法为 unknown 类型参数的无上下文
     * {@code $n is null} 推断数据类型）。说明不能写进 @Query 内（HQL 不支持 SQL 注释）。</p>
     */
    @Query("""
        select a from AccessLogEntity a
        where (:scopeSiteKey is null or a.siteKey = :scopeSiteKey)
          and a.occurredAt >= :from and a.occurredAt < :to
          and (:outcomes is null or a.outcome in :outcomes)
          and (:keywordLike is null or lower(a.path) like :keywordLike)
          and (:traceId is null or a.traceId = :traceId)
          and (cast(:cursorTime as timestamp) is null or a.occurredAt < :cursorTime
               or (a.occurredAt = :cursorTime and a.id < :cursorId))
        """)
    List<AccessLogEntity> search(@Param("scopeSiteKey") String scopeSiteKey,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to,
                                 @Param("outcomes") List<String> outcomes,
                                 @Param("keywordLike") String keywordLike,
                                 @Param("traceId") String traceId,
                                 @Param("cursorTime") Instant cursorTime,
                                 @Param("cursorId") UUID cursorId,
                                 Pageable pageable);

    /** 详情读取（含 SITE 域可见性判定，AC-LOG-014）。 */
    @Query("select a from AccessLogEntity a where a.id = :id and (:scopeSiteKey is null or a.siteKey = :scopeSiteKey)")
    AccessLogEntity findVisible(@Param("id") UUID id, @Param("scopeSiteKey") String scopeSiteKey);

    /** 结果分类计数（FR-LOG-QR-005）。 */
    @Query("""
        select a.outcome as outcome, count(a) as total from AccessLogEntity a
        where (:scopeSiteKey is null or a.siteKey = :scopeSiteKey)
          and a.occurredAt >= :from and a.occurredAt < :to
          and (:outcomes is null or a.outcome in :outcomes)
          and (:keywordLike is null or lower(a.path) like :keywordLike)
          and (:traceId is null or a.traceId = :traceId)
        group by a.outcome
    """)
    List<OutcomeCount> countByOutcome(@Param("scopeSiteKey") String scopeSiteKey,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      @Param("outcomes") List<String> outcomes,
                                      @Param("keywordLike") String keywordLike,
                                      @Param("traceId") String traceId);

    /** 按小时分布（FR-LOG-QR-005）。GROUP BY / ORDER BY 与 SELECT 表达式逐字一致（H2 严格校验）。 */
    @Query("""
        select cast(date_trunc('hour', a.occurredAt) as timestamp) as hour, count(a) as total
        from AccessLogEntity a
        where (:scopeSiteKey is null or a.siteKey = :scopeSiteKey)
          and a.occurredAt >= :from and a.occurredAt < :to
          and (:outcomes is null or a.outcome in :outcomes)
          and (:keywordLike is null or lower(a.path) like :keywordLike)
          and (:traceId is null or a.traceId = :traceId)
        group by cast(date_trunc('hour', a.occurredAt) as timestamp)
        order by cast(date_trunc('hour', a.occurredAt) as timestamp)
        """)
    List<HourCount> countByHour(@Param("scopeSiteKey") String scopeSiteKey,
                                @Param("from") Instant from,
                                @Param("to") Instant to,
                                @Param("outcomes") List<String> outcomes,
                                @Param("keywordLike") String keywordLike,
                                @Param("traceId") String traceId);

    /** 同 traceId 关联条目（FR-LOG-QR-007）。 */
    List<AccessLogEntity> findByTraceIdOrderByOccurredAtDesc(String traceId, Pageable pageable);

    /**
     * 常规访问日志清理取号（FR-LOG-ST-003 默认 90 天 / FR-LOG-ST-006 分批）：早于 cutoff 的最旧一批主键。
     *
     * <p><b>2026-10-19 验收修复：</b>原 {@code deleteNormalBefore}/{@code deleteSecurityOutcomeBefore}
     * 声明了 {@code @Param("batchSize")} 却从未在 JPQL 中引用（分批形同虚设，单条语句删光全表），
     * 且调用方 {@code SystemLogRetentionJob.purgeExpired} 无事务上下文 → {@code @Modifying}
     * 抛 TransactionRequiredException 被任务 catch 吞掉——该清理自上线起从未真正执行。
     * 现改为「JPQL 取号（Pageable 分页，各库方言安全）+ 按主键分批删除（删除方法自带事务）」。</p>
     */
    @Query("""
        select a.id from AccessLogEntity a
        where a.occurredAt < :cutoff
          and a.outcome not in ('AUTH_FAILURE', 'RATE_LIMITED')
        order by a.occurredAt
        """)
    List<UUID> findNormalExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** 认证失败/限流访问日志取号（FR-LOG-ST-003 默认 180 天），同上；分区口径与查询侧一致。 */
    @Query("""
        select a.id from AccessLogEntity a
        where a.occurredAt < :cutoff
          and a.outcome in ('AUTH_FAILURE', 'RATE_LIMITED')
        order by a.occurredAt
        """)
    List<UUID> findSecurityExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** 按主键批量删除（FR-LOG-ST-006）：每批一个独立事务，避免长事务与大表锁。 */
    @Modifying
    @Transactional
    @Query("delete from AccessLogEntity a where a.id in :ids")
    int deleteByIdInBatch(@Param("ids") List<UUID> ids);

    interface OutcomeCount {
        String getOutcome();
        long getTotal();
    }

    interface HourCount {
        java.sql.Timestamp getHour();
        long getTotal();
    }
}
