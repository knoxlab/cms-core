package com.powercms.cms.stats.repo;

import com.powercms.cms.stats.entity.MemberProgressEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberProgressRepository extends JpaRepository<MemberProgressEntity, UUID> {

    Optional<MemberProgressEntity> findBySiteIdAndMemberIdAndContentId(UUID siteId, UUID memberId, UUID contentId);

    List<MemberProgressEntity> findBySiteIdAndMemberIdOrderByLastWatchedAtDesc(UUID siteId, UUID memberId, Pageable pageable);

    List<MemberProgressEntity> findBySiteIdAndContentIdOrderByFarthestMsDesc(UUID siteId, UUID contentId, Pageable pageable);

    /**
     * 完播判定（FR-STAT-037）：最远位置达到时长 95% 时标记。
     * completed 以 boolean 参数绑定而非 {@code = true} 字面量：达梦 bit 列
     * 不接受 boolean 字面量比较/赋值（DmSqlProbe3-P2 实测 Syntax error、
     * P1 setBoolean 绑定通过），绑定方式在 H2/PG/MySQL/金仓同样成立。
     */
    @Modifying
    @Query(value = """
        update member_progress set completed = :done, updated_at = :now
        where site_id = :siteId and member_id = :memberId and content_id = :contentId
          and farthest_ms >= :thresholdMs
        """, nativeQuery = true)
    int markCompletedIfReached(@Param("siteId") UUID siteId,
                               @Param("memberId") UUID memberId,
                               @Param("contentId") UUID contentId,
                               @Param("thresholdMs") long thresholdMs,
                               @Param("now") Instant now,
                               @Param("done") boolean done);

    /** 按视频的会员进度分布（FR-STAT-039）。 */
    @Query("""
        select p.contentId, count(p), sum(case when p.completed then 1 else 0 end), avg(p.farthestMs)
        from MemberProgressEntity p
        where p.siteId = :siteId
          and (:contentTypeId is null or p.contentTypeId = :contentTypeId)
          and (:folderId is null or p.folderId = :folderId)
        group by p.contentId
        order by count(p) desc
        """)
    List<Object[]> progressByContent(@Param("siteId") UUID siteId,
                                     @Param("contentTypeId") UUID contentTypeId,
                                     @Param("folderId") UUID folderId,
                                     Pageable pageable);
}
