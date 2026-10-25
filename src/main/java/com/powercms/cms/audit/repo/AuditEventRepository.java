package com.powercms.cms.audit.repo;

import com.powercms.cms.audit.entity.AuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    Page<AuditEventEntity> findByObjectTypeAndObjectIdOrderByOccurredAtDesc(
        String objectType, String objectId, Pageable pageable);

    Page<AuditEventEntity> findByActorOrderByOccurredAtDesc(String actor, Pageable pageable);

    /** 工作台「Activity」widget：站点级最近管理操作，时间倒序。 */
    Page<AuditEventEntity> findBySiteIdOrderByOccurredAtDesc(UUID siteId, Pageable pageable);

    /**
     * 全局（无站点归属）审计事件，时间倒序：FR-AGT-MCP-005 全局读口取数。
     * 未绑定站点的 API Token 在站点解析前被拒（未知工具 / 分级不足 / 限流）时 {@code site_id} 为 null，
     * 这些越权与探测事件无法经站点维度读口看到，故以本查询补齐。
     */
    Page<AuditEventEntity> findBySiteIdIsNullOrderByOccurredAtDesc(Pageable pageable);

    /** 工作台「My Activity」widget：当前用户在站点内的最近操作。 */
    Page<AuditEventEntity> findBySiteIdAndActorOrderByOccurredAtDesc(UUID siteId, String actor, Pageable pageable);

    /** FR-AI-MCP-006：按 actor+action 重放当日调用计数（MCP 日配额懒惰重建的事实源）。 */
    long countByActorAndActionAndOccurredAtGreaterThanEqual(String actor, String action, java.time.Instant occurredAt);

    /**
     * 矩阵 I1：按保留期限分批删除过期审计事件（AuditRetentionJob 调度执行）。
     *
     * <p><b>2026-10-19 二次修复：</b>本清理原先用 native SQL
     * {@code DELETE ... WHERE id IN (SELECT id ... LIMIT :limit)}。两级问题：
     * ① MySQL 不允许 {@code LIMIT} 直接出现在 {@code IN} 子查询（错误 1235，解析期即失败），
     * 包派生表虽可绕过，但 ② Spring Data 的 SQL 校验器不接受 {@code LIMIT} 后跟绑定参数
     * （无论是否派生表），@Query 上始终报 SQL_SYNTAX。现改为不依赖 LIMIT 的可移植写法：
     * JPQL 取号（{@link Pageable} 分页，各库方言均安全）+ 按主键分批删除
     * （{@link #deleteByIdInBatch}，每批独立事务）。与日志保留清理同一模式。</p>
     */
    @Query("""
        select e.id from AuditEventEntity e
        where e.occurredAt < :cutoff
        order by e.occurredAt
        """)
    List<UUID> findExpiredIds(@Param("cutoff") java.time.Instant cutoff, Pageable pageable);

    /** 按主键批量删除（矩阵 I1）：每批一个独立事务，避免大表长事务。 */
    @Modifying
    @Transactional
    @Query("delete from AuditEventEntity e where e.id in :ids")
    int deleteByIdInBatch(@Param("ids") List<UUID> ids);
}
