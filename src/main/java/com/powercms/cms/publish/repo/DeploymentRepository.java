package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.DeploymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<DeploymentEntity, UUID> {
    List<DeploymentEntity> findBySiteIdOrderByCreatedAtDesc(UUID siteId);
    List<DeploymentEntity> findByDeployTargetIdOrderByCreatedAtDesc(UUID targetId);
    Optional<DeploymentEntity> findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(UUID targetId, String status);
    List<DeploymentEntity> findByDeployTargetIdAndStatusOrderByCreatedAtDesc(UUID targetId, String status);
    /** FR-PUBLISH-004：同一发布目标是否存在进行中（非终态）部署，用于发布串行化约束。 */
    boolean existsByDeployTargetIdAndStatusIn(UUID deployTargetId, Collection<String> statuses);
    /** FR-PUBLISH-004：到期未执行的排期部署（QUEUED 且 scheduledAt 已过）。 */
    List<DeploymentEntity> findByStatusAndScheduledAtIsNotNullAndScheduledAtLessThanEqual(String status, Instant now);
}
