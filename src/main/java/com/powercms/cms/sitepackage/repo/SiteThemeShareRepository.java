package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.SiteThemeShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface SiteThemeShareRepository extends JpaRepository<SiteThemeShareEntity, UUID> {
    Optional<SiteThemeShareEntity> findByThemeIdAndConsumerSiteId(UUID themeId, UUID consumerSiteId);
    List<SiteThemeShareEntity> findByThemeIdOrderByCreatedAtAsc(UUID themeId);
    List<SiteThemeShareEntity> findByConsumerSiteIdAndStatus(UUID consumerSiteId, String status);
    boolean existsByThemeIdAndPinnedVersionAndStatus(UUID themeId, int pinnedVersion, String status);
}
