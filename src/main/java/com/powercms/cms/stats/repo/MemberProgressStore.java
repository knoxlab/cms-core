package com.powercms.cms.stats.repo;

import com.powercms.cms.stats.AnalyticsDialect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 学习进度 upsert 的方言分支实现（FR-STAT-037/102）。
 * PG 系（PostgreSQL/金仓）：INSERT ... ON CONFLICT 原子 upsert（多节点并发安全）；
 * H2 2.x / MySQL / 达梦不支持该子句，走 update-then-insert + 冲突重试（单写者场景，
 * 统计聚合任务由 AnalyticsJobLockService 保证串行）。
 */
@Service
public class MemberProgressStore {

    private final AnalyticsDialect dialect;

    public MemberProgressStore(AnalyticsDialect dialect) {
        this.dialect = dialect;
    }

    @PersistenceContext
    private EntityManager em;

    /**
     * 幂等 upsert + 原子累加：最远位置取 max、断点覆盖、净观看时长累加。
     *
     * @return 1 = 新插入行，2 = 更新已有行（与仓库原语义一致）
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int upsert(UUID id, UUID siteId, UUID memberId, UUID contentId,
                      long delta, long farthest, long resume, Instant now) {
        if (dialect.supportsOnConflict()) {
            return em.createNativeQuery("""
                    insert into member_progress (id, site_id, member_id, content_id, farthest_ms, resume_ms,
                                                 watched_ms_total, completed, last_watched_at, updated_at)
                    values (:id, :siteId, :memberId, :contentId, :farthest, :resume, :delta, false, :now, :now)
                    on conflict (site_id, member_id, content_id) do update set
                        farthest_ms = greatest(member_progress.farthest_ms, excluded.farthest_ms),
                        resume_ms = excluded.resume_ms,
                        watched_ms_total = member_progress.watched_ms_total + excluded.watched_ms_total,
                        last_watched_at = excluded.last_watched_at,
                        updated_at = excluded.updated_at
                    """)
                .setParameter("id", id)
                .setParameter("siteId", siteId)
                .setParameter("memberId", memberId)
                .setParameter("contentId", contentId)
                .setParameter("delta", delta)
                .setParameter("farthest", farthest)
                .setParameter("resume", resume)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
        }
        // 可移植：先更新，无行则插入；并发撞唯一键时回退为更新
        int updated = em.createNativeQuery("""
                update member_progress set
                    farthest_ms = greatest(farthest_ms, :farthest),
                    resume_ms = :resume,
                    watched_ms_total = watched_ms_total + :delta,
                    last_watched_at = :now,
                    updated_at = :now
                where site_id = :siteId and member_id = :memberId and content_id = :contentId
                """)
            .setParameter("siteId", siteId)
            .setParameter("memberId", memberId)
            .setParameter("contentId", contentId)
            .setParameter("delta", delta)
            .setParameter("farthest", farthest)
            .setParameter("resume", resume)
            .setParameter("now", Timestamp.from(now))
            .executeUpdate();
        if (updated > 0) return 2;
        try {
            em.createNativeQuery("""
                    insert into member_progress (id, site_id, member_id, content_id, farthest_ms, resume_ms,
                                                 watched_ms_total, completed, last_watched_at, updated_at)
                    values (:id, :siteId, :memberId, :contentId, :farthest, :resume, :delta, false, :now, :now)
                    """)
                .setParameter("id", id)
                .setParameter("siteId", siteId)
                .setParameter("memberId", memberId)
                .setParameter("contentId", contentId)
                .setParameter("delta", delta)
                .setParameter("farthest", farthest)
                .setParameter("resume", resume)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
            return 1;
        } catch (jakarta.persistence.PersistenceException e) {
            // 并发插入撞 uk_member_progress：转为更新路径
            em.createNativeQuery("""
                    update member_progress set
                        farthest_ms = greatest(farthest_ms, :farthest),
                        resume_ms = :resume,
                        watched_ms_total = watched_ms_total + :delta,
                        last_watched_at = :now,
                        updated_at = :now
                    where site_id = :siteId and member_id = :memberId and content_id = :contentId
                    """)
                .setParameter("siteId", siteId)
                .setParameter("memberId", memberId)
                .setParameter("contentId", contentId)
                .setParameter("delta", delta)
                .setParameter("farthest", farthest)
                .setParameter("resume", resume)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
            return 2;
        }
    }
}
