package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.ReleaseMonitorPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReleaseMonitorPolicyRepository extends JpaRepository<ReleaseMonitorPolicyEntity, UUID> {
    List<ReleaseMonitorPolicyEntity> findByEnabledTrue();
}
