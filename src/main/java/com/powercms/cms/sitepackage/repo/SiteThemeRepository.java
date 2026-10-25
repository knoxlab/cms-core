package com.powercms.cms.sitepackage.repo;
import com.powercms.cms.sitepackage.entity.SiteThemeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SiteThemeRepository extends JpaRepository<SiteThemeEntity, UUID> {
    List<SiteThemeEntity> findBySiteIdOrderByNameAsc(UUID siteId);
    Optional<SiteThemeEntity> findBySiteIdAndNameIgnoreCase(UUID siteId, String name);
}
