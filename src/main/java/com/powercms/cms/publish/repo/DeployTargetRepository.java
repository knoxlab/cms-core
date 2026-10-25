package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.DeployTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeployTargetRepository extends JpaRepository<DeployTargetEntity, UUID> {
    List<DeployTargetEntity> findBySiteId(UUID siteId);
}
