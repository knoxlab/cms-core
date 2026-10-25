package com.powercms.cms.sitepackage.repo;
import com.powercms.cms.sitepackage.entity.SiteThemeRevisionFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SiteThemeRevisionFileRepository extends JpaRepository<SiteThemeRevisionFileEntity, UUID> {
    List<SiteThemeRevisionFileEntity> findByThemeRevisionIdOrderByPathAsc(UUID revisionId);
}
