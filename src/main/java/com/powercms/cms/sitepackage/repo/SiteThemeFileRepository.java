package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.SiteThemeFileEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SiteThemeFileRepository extends JpaRepository<SiteThemeFileEntity, UUID> {
    List<SiteThemeFileEntity> findByThemeIdOrderByPathAsc(UUID themeId);
    Optional<SiteThemeFileEntity> findByThemeIdAndPath(UUID themeId, String path);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SiteThemeFileEntity f where f.themeId = :themeId")
    void deleteByThemeId(@Param("themeId") UUID themeId);
}
