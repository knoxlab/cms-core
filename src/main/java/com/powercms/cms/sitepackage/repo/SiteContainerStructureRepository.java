package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.SiteContainerStructureEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SiteContainerStructureRepository extends JpaRepository<SiteContainerStructureEntity, UUID> {
    List<SiteContainerStructureEntity> findByContainerIdOrderBySortOrderAsc(UUID containerId);
    Optional<SiteContainerStructureEntity> findByContainerIdAndStructureId(UUID containerId, UUID structureId);

    /** 内容模型变更影响评估（FR-AGT-MODEL-008）：反查引用了该内容类型的容器映射。 */
    List<SiteContainerStructureEntity> findByStructureId(UUID structureId);

    List<SiteContainerStructureEntity> findByStructureIdIn(Collection<UUID> structureIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SiteContainerStructureEntity link where link.containerId = :containerId")
    void deleteByContainerId(@Param("containerId") UUID containerId);
}
