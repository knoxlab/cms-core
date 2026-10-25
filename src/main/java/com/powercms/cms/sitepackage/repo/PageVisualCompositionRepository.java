package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PageVisualCompositionRepository extends JpaRepository<PageVisualCompositionEntity, UUID> {
    Optional<PageVisualCompositionEntity> findByPageId(UUID pageId);
    List<PageVisualCompositionEntity> findByTemplateId(UUID templateId);
    long countByTemplateId(UUID templateId);
}
