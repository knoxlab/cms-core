package com.powercms.cms.media.repo;

import com.powercms.cms.media.entity.MediaAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAssetEntity, UUID> {
    List<MediaAssetEntity> findBySiteId(UUID siteId);
    List<MediaAssetEntity> findBySiteIdOrderByCreatedAtDesc(UUID siteId);
    Optional<MediaAssetEntity> findBySiteIdAndContentHash(UUID siteId, String contentHash);

    /** 站点媒体累计字节数（FR-KNX-010 站点配额校验）。 */
    @Query("select coalesce(sum(a.fileSize), 0L) from MediaAssetEntity a where a.siteId = :siteId")
    long sumFileSizeBySite(@Param("siteId") UUID siteId);

    /** 全部媒体累计字节数（License mediaGb 配额水位）。 */
    @Query("select coalesce(sum(a.fileSize), 0L) from MediaAssetEntity a")
    long sumFileSize();

    /** 有效媒体条目数（License mediaItems 配额水位）。 */
    long countByStatus(String status);

    /** 已归类但尚未同步内容项的资产（ApplicationReady 存量补齐用，FR-CM-MEDIA-011）。 */
    List<MediaAssetEntity> findByMediaTypeIdNotNullAndContentItemIdIsNull();

    /** kind 存量纠偏（FR-CM-MEDIA-040 扩展）：扫描疑似 MIME 误报的资产。 */
    List<MediaAssetEntity> findByKindIn(List<String> kinds);

    /** 关系字段候选枚举（EDIT-004/MCP-003）：站点 + ACTIVE + 全部 kind + 最近更新 Top 100。 */
    List<MediaAssetEntity> findTop100BySiteIdAndStatusOrderByUpdatedAtDesc(UUID siteId, String status);

    /** 关系字段候选枚举（EDIT-004/MCP-003）：站点 + ACTIVE + 限定 kind + 最近更新 Top 100。 */
    List<MediaAssetEntity> findTop100BySiteIdAndStatusAndKindInOrderByUpdatedAtDesc(
        UUID siteId, String status, Collection<String> kinds);

    /** 未归类存量（FR-KNX-015 存量修复）：分片上传通道早期未自动归类的资产。 */
    List<MediaAssetEntity> findByMediaTypeIdIsNullAndKindIsNull();

    /** FR-AI-SRC-002 AI 引用守卫：按 MEDIA 内容项批量取资产（校验有效期）。 */
    List<MediaAssetEntity> findByContentItemIdIn(Collection<UUID> contentItemIds);

    // ── 批次 5 媒体写面（FR-AGT-MEDIA-003/005）：站点域内定位、目录挂载与统计 ──────────

    /** 站点域内定位资产（越权访问统一返回 NOT_FOUND，避免探测他站资产存在性）。 */
    Optional<MediaAssetEntity> findByIdAndSiteId(UUID id, UUID siteId);

    Optional<MediaAssetEntity> findBySiteIdAndFolderPath(UUID siteId, String folderPath);

    long countBySiteIdAndFolderPath(UUID siteId, String folderPath);

    long countBySiteIdAndFolderPathStartingWith(UUID siteId, String pathPrefix);

    /** 站点全部资产的目录挂载点（目录树统计一次读取，避免逐目录 N+1 查询）。 */
    @Query("select a.folderPath from MediaAssetEntity a where a.siteId = :siteId")
    List<String> findFolderPathsBySite(@Param("siteId") UUID siteId);

    /** 目录搬移：批量重写资产的 folder_path 前缀，保持资产与目录树一致（FR-AGT-MEDIA-005）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MediaAssetEntity a set a.folderPath = concat(:newPath, substring(a.folderPath, :oldLen + 1)) "
        + "where a.siteId = :siteId and (a.folderPath = :oldPath or a.folderPath like concat(:oldPath, '/%'))")
    int rewriteFolderPathPrefix(@Param("siteId") UUID siteId, @Param("oldPath") String oldPath,
                                @Param("newPath") String newPath, @Param("oldLen") int oldLen);
}
