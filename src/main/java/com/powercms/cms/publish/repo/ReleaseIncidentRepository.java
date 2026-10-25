package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.ReleaseIncidentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseIncidentRepository extends JpaRepository<ReleaseIncidentEntity, UUID> {
    List<ReleaseIncidentEntity> findTop50BySiteIdOrderByDetectedAtDesc(UUID siteId);

    boolean existsByDeploymentIdAndIncidentType(UUID deploymentId, String incidentType);

    List<ReleaseIncidentEntity> findByDeploymentIdAndIncidentTypeAndDetectedAtAfter(
        UUID deploymentId, String incidentType, Instant after);

    Optional<ReleaseIncidentEntity> findFirstByDeploymentIdAndIncidentTypeOrderByDetectedAtDesc(
        UUID deploymentId, String incidentType);

    /** 持续观测中的事件（G9-④）：按站点 + 类型 + 状态扫描，用于「恢复关单」与「陈旧观测清理」。 */
    List<ReleaseIncidentEntity> findBySiteIdAndIncidentTypeAndStatus(
        UUID siteId, String incidentType, String status);
}
