package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.ReleaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseRepository extends JpaRepository<ReleaseEntity, UUID> {
    List<ReleaseEntity> findBySiteIdOrderByCreatedAtDesc(UUID siteId);
    Optional<ReleaseEntity> findBySiteIdAndStatus(UUID siteId, String status);
}
