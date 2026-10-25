package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.SiteTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SiteTemplateRepository extends JpaRepository<SiteTemplateEntity, UUID> {
    List<SiteTemplateEntity> findBySiteIdOrderByNameAsc(UUID siteId);
    List<SiteTemplateEntity> findBySiteIdAndThemeIdOrderByNameAsc(UUID siteId, UUID themeId);
    List<SiteTemplateEntity> findBySiteIdAndNameContainingIgnoreCaseOrderByNameAsc(UUID siteId, String name);
}
