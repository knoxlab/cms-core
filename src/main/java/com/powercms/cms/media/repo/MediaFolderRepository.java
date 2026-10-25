package com.powercms.cms.media.repo;

import com.powercms.cms.media.entity.MediaFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFolderRepository extends JpaRepository<MediaFolderEntity, UUID> {

    List<MediaFolderEntity> findBySiteIdOrderByPathAsc(UUID siteId);

    Optional<MediaFolderEntity> findBySiteIdAndPath(UUID siteId, String path);

    Optional<MediaFolderEntity> findByIdAndSiteId(UUID id, UUID siteId);

    List<MediaFolderEntity> findBySiteIdAndParentIdOrderBySortOrderAscNameAsc(UUID siteId, UUID parentId);

    boolean existsBySiteIdAndPath(UUID siteId, String path);

    /** 同一父目录下重名判定（parent_id 为空表示根，用 is null 语义单独判定）。 */
    boolean existsBySiteIdAndParentIdAndName(UUID siteId, UUID parentId, String name);

    boolean existsBySiteIdAndParentIdIsNullAndName(UUID siteId, String name);

    /** 目录搬移：批量重写后代目录的物化路径前缀（FR-AGT-MEDIA-005）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MediaFolderEntity f set f.path = concat(:newPath, substring(f.path, :oldLen + 1)) "
        + "where f.siteId = :siteId and f.path like concat(:oldPath, '/%')")
    int rewriteDescendantPaths(@Param("siteId") UUID siteId, @Param("oldPath") String oldPath,
                               @Param("newPath") String newPath, @Param("oldLen") int oldLen);

    /** 同一父目录下的同级目录数（排序值缺省用）。 */
    long countBySiteIdAndParentId(UUID siteId, UUID parentId);

    long countBySiteIdAndParentIdIsNull(UUID siteId);
}
