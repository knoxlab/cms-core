package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.SitePackageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SitePackageRepository extends JpaRepository<SitePackageEntity, UUID> {
    List<SitePackageEntity> findAllByOrderByCreatedAtDesc();
    Optional<SitePackageEntity> findByPackageIdAndVersion(String packageId, String version);
}
