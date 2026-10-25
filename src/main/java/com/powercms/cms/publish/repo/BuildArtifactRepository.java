package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.BuildArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BuildArtifactRepository extends JpaRepository<BuildArtifactEntity, UUID> {
    Optional<BuildArtifactEntity> findByBuildJobId(UUID buildJobId);
    List<BuildArtifactEntity> findByReleaseId(UUID releaseId);
    List<BuildArtifactEntity> findByReleaseIdInOrderByCreatedAtDesc(Collection<UUID> releaseIds);
}
