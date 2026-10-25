package com.powercms.cms.site.repo;

import com.powercms.cms.site.entity.SiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteRepository extends JpaRepository<SiteEntity, UUID> {

    Optional<SiteEntity> findBySiteKey(String siteKey);

    List<SiteEntity> findByOrganizationIdAndStatus(UUID organizationId, String status);

    List<SiteEntity> findByOrganizationIdAndStatusIn(UUID organizationId, List<String> statuses);

    boolean existsBySiteKey(String siteKey);
}
