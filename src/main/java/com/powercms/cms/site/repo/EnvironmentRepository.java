package com.powercms.cms.site.repo;

import com.powercms.cms.site.entity.EnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, UUID> {

    List<EnvironmentEntity> findBySiteId(UUID siteId);
}
