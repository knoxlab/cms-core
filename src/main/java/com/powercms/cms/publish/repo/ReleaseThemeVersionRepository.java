package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.ReleaseThemeVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ReleaseThemeVersionRepository extends JpaRepository<ReleaseThemeVersionEntity, UUID> {
    List<ReleaseThemeVersionEntity> findByReleaseIdOrderByThemeIdAscThemeVersionAsc(UUID releaseId);
    boolean existsByThemeRevisionId(UUID themeRevisionId);
}
