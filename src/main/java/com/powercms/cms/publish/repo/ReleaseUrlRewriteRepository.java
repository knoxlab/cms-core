package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.ReleaseUrlRewriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReleaseUrlRewriteRepository extends JpaRepository<ReleaseUrlRewriteEntity, ReleaseUrlRewriteEntity.Key> {
    List<ReleaseUrlRewriteEntity> findByReleaseIdOrderByPriorityDescNameAsc(UUID releaseId);
}
