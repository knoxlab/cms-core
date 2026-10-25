package com.powercms.cms.sitepackage.repo;
import com.powercms.cms.sitepackage.entity.SiteThemeRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SiteThemeRevisionRepository extends JpaRepository<SiteThemeRevisionEntity, UUID> {
    List<SiteThemeRevisionEntity> findByThemeIdOrderByVersionDesc(UUID themeId);
    Optional<SiteThemeRevisionEntity> findByThemeIdAndVersion(UUID themeId, int version);
    boolean existsByParentThemeIdAndParentThemeVersion(UUID parentThemeId, int parentThemeVersion);
}
