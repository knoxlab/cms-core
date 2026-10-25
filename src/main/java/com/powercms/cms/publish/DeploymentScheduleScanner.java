package com.powercms.cms.publish;

import com.powercms.cms.publish.entity.DeploymentEntity;
import com.powercms.cms.publish.repo.DeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * FR-PUBLISH-004：到期排期部署自动执行。
 * 扫描 QUEUED 且 scheduledAt 已过的部署并逐个发布；单项失败仅告警，不影响其他到期项。
 * 通过 powercms.publish.schedule-scan-enabled=false 可关闭。
 */
@Component
@ConditionalOnProperty(name = "powercms.publish.schedule-scan-enabled", havingValue = "true", matchIfMissing = true)
public class DeploymentScheduleScanner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentScheduleScanner.class);

    private final DeploymentRepository deployments;
    private final DeployService deployService;

    DeploymentScheduleScanner(DeploymentRepository deployments, DeployService deployService) {
        this.deployments = deployments;
        this.deployService = deployService;
    }

    @Scheduled(fixedDelayString = "${powercms.publish.schedule-scan-ms:60000}")
    public void scan() {
        for (DeploymentEntity d : deployments.findByStatusAndScheduledAtIsNotNullAndScheduledAtLessThanEqual(
                DeploymentEntity.STATUS_QUEUED, Instant.now())) {
            try {
                deployService.publish(d.getId());
                log.info("排期发布已执行 deployment={} release={} target={}", d.getId(), d.getReleaseId(), d.getDeployTargetId());
            } catch (RuntimeException e) {
                log.warn("排期发布执行失败 deployment={}: {}", d.getId(), e.getMessage());
            }
        }
    }
}
