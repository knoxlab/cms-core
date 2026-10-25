package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.SiteTemplateRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteTemplateRevisionRepository extends JpaRepository<SiteTemplateRevisionEntity, UUID> {
    List<SiteTemplateRevisionEntity> findByTemplateIdOrderByVersionDesc(UUID templateId);
    Optional<SiteTemplateRevisionEntity> findByTemplateIdAndVersion(UUID templateId, int version);
}
