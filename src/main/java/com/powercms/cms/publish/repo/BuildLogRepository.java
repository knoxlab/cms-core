package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.BuildLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BuildLogRepository extends JpaRepository<BuildLogEntity, Long> {
    List<BuildLogEntity> findByBuildJobIdOrderBySeq(UUID jobId);
}
