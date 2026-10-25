package com.powercms.cms.stats.repo;

import com.powercms.cms.stats.entity.AnalyticsPlaySessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AnalyticsPlaySessionRepository extends JpaRepository<AnalyticsPlaySessionEntity, String> {

    /** 心跳原子累加：watched/traffic 累加、最远位置取 max、断点覆盖（FR-STAT-102，禁读-改-写）。
     *  同时刷新 last_heartbeat_at——超时收口只认"多久没心跳"，不认"起播多久"（见 closeTimedOut）。 */
    @Modifying
    @Query("""
        update AnalyticsPlaySessionEntity s
        set s.watchedMs = s.watchedMs + :delta,
            s.trafficBytes = s.trafficBytes + :traffic,
            s.farthestMs = case when :position > s.farthestMs then :position else s.farthestMs end,
            s.resumeMs = :position,
            s.lastHeartbeatAt = :now
        where s.id = :id
        """)
    int applyHeartbeat(@Param("id") String id,
                       @Param("delta") long delta,
                       @Param("position") long position,
                       @Param("traffic") long traffic,
                       @Param("now") Instant now);

    /** 心跳超时收口（NFR-STAT-006）：未结束、且**最后一次心跳**早于阈值的会话标记 TIMEOUT。
     *  只按 started_at 一刀切会把长视频里仍在正常心跳的会话提前收口：ended_at 被占用后
     *  真实结束原因写不进去（完播率失真）。last_heartbeat_at 为空的存量行走 started_at。 */
    @Modifying
    @Query("""
        update AnalyticsPlaySessionEntity s
        set s.endReason = :reason, s.endedAt = :endedAt
        where s.endedAt is null and s.startedAt < :threshold
          and (s.lastHeartbeatAt is null or s.lastHeartbeatAt < :threshold)
        """)
    int closeTimedOut(@Param("threshold") Instant threshold,
                      @Param("endedAt") Instant endedAt,
                      @Param("reason") String reason);

    List<AnalyticsPlaySessionEntity> findBySiteIdAndContentIdAndStartedAtBetweenOrderByStartedAtDesc(
        UUID siteId, UUID contentId, Instant from, Instant to, Pageable pageable);

    long countBySiteIdAndContentIdAndStartedAtBetween(UUID siteId, UUID contentId, Instant from, Instant to);

    /**
     * 播放行为统计（FR-STAT-035）：会话数/完播数/净观看/流量/平均完成度。
     * <p>{@code cast(x as double)} 为 HQL 类型名（生成 SQL 时由方言输出 double precision，
     * H2/PostgreSQL 均可执行）；此查询是 JPQL 而非 native SQL——写 {@code double precision}
     * 或在查询串内加 {@code --} 注释都会导致 HQL 解析失败、Bean 初始化中断。</p>
     */
    @Query("""
        select count(s),
               sum(case when s.endReason = 'COMPLETED' then 1 else 0 end),
               coalesce(sum(s.watchedMs), 0),
               coalesce(sum(s.trafficBytes), 0),
               coalesce(avg(case when s.durationMs is null or s.durationMs = 0 then null
                                else cast(s.watchedMs as double) / s.durationMs end), 0)
        from AnalyticsPlaySessionEntity s
        where s.siteId = :siteId and s.contentId = :contentId and s.startedAt between :from and :to
        """)
    List<Object[]> aggregateByContent(@Param("siteId") UUID siteId,
                                      @Param("contentId") UUID contentId,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to);

    /** 退出位置分布分桶数据源（FR-STAT-035）：各会话最远位置。 */
    @Query("""
        select s.farthestMs from AnalyticsPlaySessionEntity s
        where s.siteId = :siteId and s.contentId = :contentId and s.startedAt between :from and :to
        """)
    List<Long> farthestPositions(@Param("siteId") UUID siteId,
                                 @Param("contentId") UUID contentId,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);
}
