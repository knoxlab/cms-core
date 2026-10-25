package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.ReleaseContentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReleaseContentRepository extends JpaRepository<ReleaseContentEntity, ReleaseContentEntity.PK> {
    List<ReleaseContentEntity> findByReleaseId(UUID releaseId);
    void deleteByReleaseId(UUID releaseId);
}
