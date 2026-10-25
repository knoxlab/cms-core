package com.powercms.cms.systemlog.repo;

import com.powercms.cms.systemlog.entity.RuntimeLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RuntimeLogRepository extends JpaRepository<RuntimeLogEntity, UUID> {

    /**
     * 稳定游标检索（FR-LOG-QR-003）：时间倒序 + id 兜底序，无新增数据时翻页不重复、不漏读。
     * 可见域过滤（FR-LOG-PM-001）在服务层拼入 siteKey 条件后走同一方法：
     * scopeSiteKey = null 表示 ORG 域（全量），否则仅 site_key = scopeSiteKey 的条目。
     * <p>cursorTime 的 null-check 必须写成 {@code cast(:cursorTime as timestamp) is null}
     * 而非 {@code :cursorTime is null}：Instant 参数以未指定（unknown）类型绑定，
     * PostgreSQL 在无类型上下文的 {@code $n is null} 处无法推断参数类型，
     * 报「无法确定参数的数据类型」；cast 提供类型锚定，null cast 后仍为 null，H2 行为一致。
     * 注：此说明不能写在 @Query 字符串内部——HQL 解析器不接受 SQL {@code --} 注释。</p>
     */
    @Query("""
        select r from RuntimeLogEntity r
        where (:scopeSiteKey is null or r.siteKey = :scopeSiteKey)
          and r.occurredAt >= :from and r.occurredAt < :to
          and (:levels is null or r.level in :levels)
          and (:modules is null or r.module in :modules)
          and (:minLevelRank is null or r.level not in ('TRACE', 'DEBUG'))
          and (:keywordLike is null or lower(r.message) like :keywordLike
               or lower(coalesce(r.exceptionType, '')) like :keywordLike)
          and (:traceId is null or r.traceId = :traceId)
          and (cast(:cursorTime as timestamp) is null or r.occurredAt < :cursorTime
               or (r.occurredAt = :cursorTime and r.id < :cursorId))
        """)
    List<RuntimeLogEntity> search(@Param("scopeSiteKey") String scopeSiteKey,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  @Param("levels") List<String> levels,
                                  @Param("modules") List<String> modules,
                                  @Param("minLevelRank") Boolean minLevelRank,
                                  @Param("keywordLike") String keywordLike,
                                  @Param("traceId") String traceId,
                                  @Param("cursorTime") Instant cursorTime,
                                  @Param("cursorId") UUID cursorId,
                                  Pageable pageable);

    /** 详情读取（含 SITE 域可见性判定，直接以 siteKey 过滤防构造 ID 越权，AC-LOG-014）。 */
    @Query("select r from RuntimeLogEntity r where r.id = :id and (:scopeSiteKey is null or r.siteKey = :scopeSiteKey)")
    RuntimeLogEntity findVisible(@Param("id") UUID id, @Param("scopeSiteKey") String scopeSiteKey);

    /** 来源模块聚合（FR-LOG-QR-001 条件⑤，后端聚合去重）。 */
    @Query("""
        select distinct r.module from RuntimeLogEntity r
        where (:scopeSiteKey is null or r.siteKey = :scopeSiteKey)
          and r.occurredAt >= :from and r.module is not null
        """)
    List<String> distinctModules(@Param("scopeSiteKey") String scopeSiteKey,
                                 @Param("from") Instant from,
                                 Pageable pageable);

    /** 统计概览（FR-LOG-QR-005）：各级别计数。 */
    @Query("""
        select r.level as level, count(r) as total from RuntimeLogEntity r
        where (:scopeSiteKey is null or r.siteKey = :scopeSiteKey)
          and r.occurredAt >= :from and r.occurredAt < :to
          and (:levels is null or r.level in :levels)
          and (:modules is null or r.module in :modules)
          and (:minLevelRank is null or r.level not in ('TRACE', 'DEBUG'))
          and (:keywordLike is null or lower(r.message) like :keywordLike
               or lower(coalesce(r.exceptionType, '')) like :keywordLike)
          and (:traceId is null or r.traceId = :traceId)
        group by r.level
        """)
    List<LevelCount> countByLevel(@Param("scopeSiteKey") String scopeSiteKey,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  @Param("levels") List<String> levels,
                                  @Param("modules") List<String> modules,
                                  @Param("minLevelRank") Boolean minLevelRank,
                                  @Param("keywordLike") String keywordLike,
                                  @Param("traceId") String traceId);

    /** 按小时分布（FR-LOG-QR-005 迷你柱状图；UTC 小时口径，前端按展示时区重投影）。
     *  注意：GROUP BY / ORDER BY 必须与 SELECT 表达式逐字一致（H2 严格校验，MODE=PostgreSQL 亦然）。 */
    @Query("""
        select cast(date_trunc('hour', r.occurredAt) as timestamp) as hour, count(r) as total
        from RuntimeLogEntity r
        where (:scopeSiteKey is null or r.siteKey = :scopeSiteKey)
          and r.occurredAt >= :from and r.occurredAt < :to
          and (:levels is null or r.level in :levels)
          and (:modules is null or r.module in :modules)
          and (:minLevelRank is null or r.level not in ('TRACE', 'DEBUG'))
          and (:keywordLike is null or lower(r.message) like :keywordLike
               or lower(coalesce(r.exceptionType, '')) like :keywordLike)
          and (:traceId is null or r.traceId = :traceId)
        group by cast(date_trunc('hour', r.occurredAt) as timestamp)
        order by cast(date_trunc('hour', r.occurredAt) as timestamp)
        """)
    List<HourCount> countByHour(@Param("scopeSiteKey") String scopeSiteKey,
                                @Param("from") Instant from,
                                @Param("to") Instant to,
                                @Param("levels") List<String> levels,
                                @Param("modules") List<String> modules,
                                @Param("minLevelRank") Boolean minLevelRank,
                                @Param("keywordLike") String keywordLike,
                                @Param("traceId") String traceId);

    /** 同 traceId 关联条目（FR-LOG-QR-007 / FR-LOG-AC-006，按时间倒序截断）。 */
    List<RuntimeLogEntity> findByTraceIdOrderByOccurredAtDesc(String traceId, Pageable pageable);

    /**
     * 标准级别（INFO 及以上）清理取号（FR-LOG-ST-003 默认 30 天 / FR-LOG-ST-006 分批）：
     * 早于 cutoff 的最旧一批主键。
     *
     * <p><b>2026-10-19 验收修复：</b>原 {@code deleteStandardBefore}/{@code deleteLowLevelBefore}
     * 声明了 {@code @Param("batchSize")} 却从未在 JPQL 中引用（注释所称「前 N 条子查询删除」
     * 实际未生效，单条语句删光全表），且调用方无事务上下文 → {@code @Modifying} 抛
     * TransactionRequiredException 被任务 catch 吞掉——该清理自上线起从未真正执行。
     * 现改为「JPQL 取号（Pageable 分页，各库方言安全）+ 按主键分批删除（删除方法自带事务）」。</p>
     */
    @Query("""
        select r.id from RuntimeLogEntity r
        where r.occurredAt < :cutoff and r.level not in ('TRACE', 'DEBUG')
        order by r.occurredAt
        """)
    List<UUID> findStandardExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** DEBUG/TRACE 短保留取号（FR-LOG-ST-003：默认 7 天），同上。 */
    @Query("""
        select r.id from RuntimeLogEntity r
        where r.occurredAt < :cutoff and r.level in ('TRACE', 'DEBUG')
        order by r.occurredAt
        """)
    List<UUID> findLowLevelExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** 按主键批量删除（FR-LOG-ST-006）：每批一个独立事务，避免长事务与大表锁。 */
    @Modifying
    @Transactional
    @Query("delete from RuntimeLogEntity r where r.id in :ids")
    int deleteByIdInBatch(@Param("ids") List<UUID> ids);

    /** 导出（FR-LOG-QR-008）：与 search 同条件、不分页（服务层限制上限）。 */
    long countByTraceId(String traceId);

    interface LevelCount {
        String getLevel();
        long getTotal();
    }

    interface HourCount {
        java.sql.Timestamp getHour();
        long getTotal();
    }
}
