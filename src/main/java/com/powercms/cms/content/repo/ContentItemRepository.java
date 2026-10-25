package com.powercms.cms.content.repo;

import com.powercms.cms.content.entity.ContentItemEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContentItemRepository extends JpaRepository<ContentItemEntity, UUID> {
    List<ContentItemEntity> findByCategoryId(UUID categoryId);
    List<ContentItemEntity> findByFolderId(UUID folderId);
    long countByFolderId(UUID folderId);
    /** 栏目（含子树）关联内容条数（FR-AGT-SITE-001 dry-run 影响评估）。 */
    long countByCategoryIdIn(Collection<UUID> categoryIds);
    List<ContentItemEntity> findBySiteIdAndStatus(UUID siteId, String status);
    List<ContentItemEntity> findBySiteId(UUID siteId);

    /**
     * 分页扫描用于 embedding 全量重建（FR-AI-RAG-001 / M0）。按 updated_at + id 稳定排序保证分页无重复/漏扫。
     *
     * <p><b>口径统一（访客索引，FR-SEARCH-002）</b>：向量重建的人群已收敛为
     * 「已部署 Release 成员 ∩ PUBLISHED」（{@code PublicIndexScope}），其成员清单是**不可变 Release**
     * 的快照，故不再需要「按站点全状态 keyset 扫描 + 水位上界」这套机制（原
     * {@code findRebuildFirstPage}/{@code findRebuildPageAfterCursor}/{@code findMaxUpdatedAtBySiteId}/
     * {@code countRebuildItemsInSnapshot} 已随口径统一移除）；本分页方法保留供其他按站点扫描的场景使用。
     */
    org.springframework.data.domain.Page<ContentItemEntity> findBySiteId(
        UUID siteId, org.springframework.data.domain.Pageable pageable);
    long countBySiteId(UUID siteId);

    /** License 用量水位：页面路由数（有 routePath 的内容条目）。 */
    long countByRoutePathIsNotNull();

    // ==== 工作台 widget 查询（Dashboard FR）====

    /** 最近修改的内容（全站活动流数据源），按 updated_at 倒序。 */
    List<ContentItemEntity> findTop50BySiteIdOrderByUpdatedAtDesc(UUID siteId);

    /** 指定用户的未发布工作：状态在给定集合内（DRAFT/IN_REVIEW/APPROVED/WITHDRAWN），按 updated_at 倒序。 */
    List<ContentItemEntity> findTop50BySiteIdAndCreatedByAndStatusInOrderByUpdatedAtDesc(
        UUID siteId, String createdBy, List<String> statuses);

    /** 当前用户的最近修改内容（My Activity 数据源，不限状态）。 */
    List<ContentItemEntity> findTop50BySiteIdAndCreatedByOrderByUpdatedAtDesc(UUID siteId, String createdBy);

    @Query("select c.id from ContentItemEntity c where c.siteId = :siteId and c.createdBy = :createdBy")
    List<UUID> findIdsBySiteIdAndCreatedBy(@Param("siteId") UUID siteId, @Param("createdBy") String createdBy,
                                           Pageable pageable);

    /** 定时发布：APPROVED 且 scheduledPublishAt 已设置，按时间正序。 */
    List<ContentItemEntity> findBySiteIdAndStatusAndScheduledPublishAtIsNotNullOrderByScheduledPublishAtAsc(
        UUID siteId, String status);

    /** 最近发布：PUBLISHED 且有已发布版本，按 updated_at 倒序（发布发生时会刷新 updated_at）。 */
    List<ContentItemEntity> findTop20BySiteIdAndStatusAndPublishedVersionNotNullOrderByUpdatedAtDesc(
        UUID siteId, String status);

    /** 即将到期：PUBLISHED 且已设 scheduledWithdrawAt，按时间正序（含已到期待系统撤回的）。 */
    List<ContentItemEntity> findBySiteIdAndStatusAndScheduledWithdrawAtIsNotNullOrderByScheduledWithdrawAtAsc(
        UUID siteId, String status);

    // ==== 工作台 summary 计数 ====
    long countBySiteIdAndCreatedByAndStatusIn(UUID siteId, String createdBy, List<String> statuses);
    long countBySiteIdAndStatusAndScheduledPublishAtIsNotNull(UUID siteId, String status);
    long countBySiteIdAndStatusAndScheduledWithdrawAtIsNotNull(UUID siteId, String status);
    long countBySiteIdAndStatusAndPublishedVersionNotNull(UUID siteId, String status);

    List<ContentItemEntity> findByContentTypeId(UUID contentTypeId);
    List<ContentItemEntity> findByContentTypeIdIn(List<UUID> contentTypeIds);

    /** 内容模型变更影响评估（FR-AGT-MODEL-008）：受影响内容条数。 */
    long countByContentTypeId(UUID contentTypeId);

    /** 关系字段候选枚举（EDIT-004/MCP-003）：站点 + 允许类型 + 非归档/撤回 + 最近更新 Top N。 */
    List<ContentItemEntity> findTop100BySiteIdAndContentTypeIdInAndStatusNotInOrderByUpdatedAtDesc(
        UUID siteId, Collection<UUID> contentTypeIds, Collection<String> statuses);
    boolean existsBySiteIdAndRoutePath(UUID siteId, String routePath);
    boolean existsBySiteIdAndRoutePathAndIdNot(UUID siteId, String routePath, UUID id);
    java.util.Optional<ContentItemEntity> findBySiteIdAndRoutePathAndStatus(UUID siteId, String routePath, String status);

    /** spec 5.3 引用迁移：用户为内容 owner（createdBy=username）。 */
    List<ContentItemEntity> findByCreatedBy(String createdBy);

    /** spec 5.3 引用迁移：内容 owner 计数。 */
    long countByCreatedBy(String createdBy);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from ContentItemEntity item where item.id = :id and item.siteId = :siteId")
    java.util.Optional<ContentItemEntity> findByIdAndSiteIdForUpdate(@Param("id") UUID id, @Param("siteId") UUID siteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from ContentItemEntity item where item.siteId = :siteId order by item.routePath, item.id")
    List<ContentItemEntity> findBySiteIdForUpdate(@Param("siteId") UUID siteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from ContentItemEntity item where item.id = :id")
    java.util.Optional<ContentItemEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        select item.id from ContentItemEntity item
        where item.status = 'PUBLISHED'
          and item.scheduledWithdrawAt is not null
          and item.scheduledWithdrawAt <= :now
        """)
    List<UUID> findDueWithdrawItemIds(@Param("now") java.time.Instant now);
}
