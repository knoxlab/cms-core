package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.SiteDropZoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SiteDropZoneRepository extends JpaRepository<SiteDropZoneEntity, UUID> {
    List<SiteDropZoneEntity> findByTemplateIdOrderBySortOrderAsc(UUID templateId);

    /** 内容模型变更影响评估（FR-AGT-MODEL-008）：反查绑定了指定容器的投放区（用于定位受影响模板）。 */
    List<SiteDropZoneEntity> findByContainerIdIn(Collection<UUID> containerIds);
}
