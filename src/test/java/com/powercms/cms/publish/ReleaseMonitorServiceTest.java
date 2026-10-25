package com.powercms.cms.publish;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.publish.entity.DeployTargetEntity;
import com.powercms.cms.publish.entity.DeploymentEntity;
import com.powercms.cms.publish.entity.ReleaseIncidentEntity;
import com.powercms.cms.publish.entity.ReleaseMonitorPolicyEntity;
import com.powercms.cms.publish.repo.DeployTargetRepository;
import com.powercms.cms.publish.repo.DeploymentRepository;
import com.powercms.cms.publish.repo.ReleaseIncidentRepository;
import com.powercms.cms.publish.repo.ReleaseMonitorPolicyRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-AI-AGENT-004：发布监控（失败阈值 / 健康度劣化）与异常自动回滚、回滚演练。
 */
@ExtendWith(MockitoExtension.class)
class ReleaseMonitorServiceTest {

    @Mock ReleaseMonitorPolicyRepository policyRepo;
    @Mock ReleaseIncidentRepository incidentRepo;
    @Mock DeploymentRepository deploymentRepo;
    @Mock DeployTargetRepository targetRepo;
    @Mock DeployService deployService;
    @Mock DeploymentHealthChecker healthChecker;
    @Mock AuditService audit;

    private ReleaseMonitorService service;

    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReleaseMonitorService(policyRepo, incidentRepo, deploymentRepo, targetRepo,
            deployService, healthChecker, audit);
    }

    @Test
    void defaultPolicyIsDisabledWithSafeDefaults() {
        when(policyRepo.findById(siteId)).thenReturn(Optional.empty());

        ReleaseMonitorService.PolicyView view = service.getPolicy(siteId);

        assertThat(view.enabled()).isFalse();
        assertThat(view.action()).isEqualTo(ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        assertThat(view.windowMinutes()).isEqualTo(60);
    }

    @Test
    void updatePolicyValidatesBoundsAndRecordsAudit() {
        when(policyRepo.findById(siteId)).thenReturn(Optional.empty());
        when(policyRepo.save(any(ReleaseMonitorPolicyEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ReleaseMonitorService.PolicyView view = service.updatePolicy(siteId,
            new ReleaseMonitorService.PolicyUpdate(true, 30, 2, new java.math.BigDecimal("0.2500"), 15,
                "notify_only"), "admin");

        assertThat(view.enabled()).isTrue();
        assertThat(view.windowMinutes()).isEqualTo(30);
        assertThat(view.failureThreshold()).isEqualTo(2);
        assertThat(view.errorRateThreshold()).isEqualByComparingTo("0.2500");
        assertThat(view.degradedDurationMinutes()).isEqualTo(15);
        assertThat(view.action()).isEqualTo(ReleaseMonitorPolicyEntity.ACTION_NOTIFY_ONLY);
        verify(audit).write(eq("admin"), eq("release.monitor.policy.update"), anyString(), anyString(),
            eq(siteId), eq("SUCCESS"), anyString());
    }

    @Test
    void updatePolicyRejectsInvalidWindowAndAction() {
        assertThatThrownBy(() -> service.updatePolicy(siteId,
            new ReleaseMonitorService.PolicyUpdate(true, 1, 1, null, null, null), "admin"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("windowMinutes");
        assertThatThrownBy(() -> service.updatePolicy(siteId,
            new ReleaseMonitorService.PolicyUpdate(true, 60, 1, null, null, "DROP_TABLE"), "admin"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("action");
    }

    @Test
    void updatePolicyValidatesMetricBoundsAndZeroDisablesErrorRate() {
        when(policyRepo.findById(siteId)).thenReturn(Optional.empty());
        when(policyRepo.save(any(ReleaseMonitorPolicyEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.updatePolicy(siteId,
            new ReleaseMonitorService.PolicyUpdate(null, null, null, new java.math.BigDecimal("1.5"), null, null),
            "admin"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("errorRateThreshold");
        assertThatThrownBy(() -> service.updatePolicy(siteId,
            new ReleaseMonitorService.PolicyUpdate(null, null, null, null, 1441, null), "admin"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("degradedDurationMinutes");

        // 0 表示显式关闭错误率口径（回到仅计数阈值的旧行为）
        ReleaseMonitorService.PolicyView view = service.updatePolicy(siteId,
            new ReleaseMonitorService.PolicyUpdate(null, null, null, java.math.BigDecimal.ZERO, 0, null), "admin");
        assertThat(view.errorRateThreshold()).isNull();
        assertThat(view.degradedDurationMinutes()).isZero();
    }

    @Test
    void healthDegradationTriggersAutoRollbackWhenEnabled() {
        ReleaseMonitorPolicyEntity policy = policy(true, 60, 1, ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        DeployTargetEntity target = target();
        DeploymentEntity current = deployed(target.getId(), UUID.randomUUID());
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(targetRepo.findBySiteId(siteId)).thenReturn(List.of(target));
        when(deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(
            target.getId(), DeploymentEntity.STATUS_DEPLOYED)).thenReturn(Optional.of(current));
        when(healthChecker.check(target)).thenReturn(new DeploymentHealthChecker.Result(true, false, "HTTP 500"));
        when(incidentRepo.existsByDeploymentIdAndIncidentType(
            current.getId(), ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED)).thenReturn(false);
        DeployService.DeploymentInfo rollback = deploymentInfo(UUID.randomUUID(), siteId, "DEPLOYED");
        when(deployService.rollback(current.getId())).thenReturn(rollback);

        service.scan(Instant.now());

        ArgumentCaptor<ReleaseIncidentEntity> captor = ArgumentCaptor.forClass(ReleaseIncidentEntity.class);
        verify(incidentRepo).save(captor.capture());
        ReleaseIncidentEntity incident = captor.getValue();
        assertThat(incident.getIncidentType()).isEqualTo(ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED);
        assertThat(incident.getStatus()).isEqualTo(ReleaseIncidentEntity.STATUS_AUTO_ROLLED_BACK);
        assertThat(incident.getRolledBackDeploymentId()).isEqualTo(rollback.id());
        verify(deployService).rollback(current.getId());
    }

    @Test
    void healthDegradationOnlyNotifiesWhenActionIsNotifyOnly() {
        ReleaseMonitorPolicyEntity policy = policy(true, 60, 1, ReleaseMonitorPolicyEntity.ACTION_NOTIFY_ONLY);
        DeployTargetEntity target = target();
        DeploymentEntity current = deployed(target.getId(), UUID.randomUUID());
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(targetRepo.findBySiteId(siteId)).thenReturn(List.of(target));
        when(deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(
            target.getId(), DeploymentEntity.STATUS_DEPLOYED)).thenReturn(Optional.of(current));
        when(healthChecker.check(target)).thenReturn(new DeploymentHealthChecker.Result(true, false, "HTTP 500"));

        service.scan(Instant.now());

        ArgumentCaptor<ReleaseIncidentEntity> captor = ArgumentCaptor.forClass(ReleaseIncidentEntity.class);
        verify(incidentRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReleaseIncidentEntity.STATUS_NOTIFIED);
        verify(deployService, never()).rollback(any());
    }

    @Test
    void deployFailureThresholdRecordsIncidentWithinWindow() {
        ReleaseMonitorPolicyEntity policy = policy(true, 60, 1, ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        DeploymentEntity failed = new DeploymentEntity();
        failed.setId(UUID.randomUUID());
        failed.setSiteId(siteId);
        failed.setReleaseId(UUID.randomUUID());
        failed.setStatus(DeploymentEntity.STATUS_FAILED);
        failed.setCreatedAt(Instant.now().minusSeconds(120));
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(deploymentRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(List.of(failed));
        when(incidentRepo.findByDeploymentIdAndIncidentTypeAndDetectedAtAfter(
            eq(failed.getId()), eq(ReleaseIncidentEntity.TYPE_DEPLOY_FAILED), any())).thenReturn(List.of());

        service.scan(Instant.now());

        ArgumentCaptor<ReleaseIncidentEntity> captor = ArgumentCaptor.forClass(ReleaseIncidentEntity.class);
        verify(incidentRepo).save(captor.capture());
        ReleaseIncidentEntity incident = captor.getValue();
        assertThat(incident.getIncidentType()).isEqualTo(ReleaseIncidentEntity.TYPE_DEPLOY_FAILED);
        assertThat(incident.getStatus()).isEqualTo(ReleaseIncidentEntity.STATUS_NOTIFIED);
        assertThat(incident.getAction()).isEqualTo(ReleaseIncidentEntity.ACTION_NOTIFY);
        // 窗口内反复失败统一告警，不对健康部署做二次回退
        verify(deployService, never()).rollback(any());
    }

    @Test
    void failureThresholdBelowLimitRecordsNothing() {
        ReleaseMonitorPolicyEntity policy = policy(true, 5, 3, ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        DeploymentEntity failed = new DeploymentEntity();
        failed.setId(UUID.randomUUID());
        failed.setSiteId(siteId);
        failed.setReleaseId(UUID.randomUUID());
        failed.setStatus(DeploymentEntity.STATUS_FAILED);
        failed.setCreatedAt(Instant.now().minusSeconds(60));
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(deploymentRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(List.of(failed));

        service.scan(Instant.now());

        verify(incidentRepo, never()).save(any());
    }

    @Test
    void recordDrillResolvesExplicitDeploymentAndPersistsSimulatedEvent() {
        DeploymentEntity deployment = new DeploymentEntity();
        deployment.setId(UUID.randomUUID());
        deployment.setSiteId(siteId);
        deployment.setReleaseId(UUID.randomUUID());
        deployment.setDeployTargetId(UUID.randomUUID());
        deployment.setStatus(DeploymentEntity.STATUS_DEPLOYED);
        deployment.setPreviousDeploymentId(UUID.randomUUID());
        when(deploymentRepo.findById(deployment.getId())).thenReturn(Optional.of(deployment));
        when(incidentRepo.save(any(ReleaseIncidentEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ReleaseMonitorService.IncidentView view = service.recordDrill(siteId, deployment.getId(), "admin");

        assertThat(view.incidentType()).isEqualTo(ReleaseIncidentEntity.TYPE_ROLLBACK_DRILL);
        assertThat(view.status()).isEqualTo(ReleaseIncidentEntity.STATUS_SIMULATED);
        assertThat(view.detail()).contains("演练结论");
        assertThat(view.detail()).contains(deployment.getPreviousDeploymentId().toString());
        verify(deployService, never()).rollback(any());
    }

    @Test
    void recordDrillRejectsCrossSiteDeployment() {
        DeploymentEntity deployment = new DeploymentEntity();
        deployment.setId(UUID.randomUUID());
        deployment.setSiteId(UUID.randomUUID());
        when(deploymentRepo.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        assertThatThrownBy(() -> service.recordDrill(siteId, deployment.getId(), "admin"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
    }

    @Test
    void errorRateThresholdTriggersIncidentWhenCountThresholdNotMet() {
        ReleaseMonitorPolicyEntity policy = policyWithMetrics(true, 60, 2,
            new java.math.BigDecimal("0.3000"), 0, ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        DeploymentEntity failed = failedDeployment();
        DeploymentEntity ok1 = deployed(UUID.randomUUID(), null);
        DeploymentEntity ok2 = deployed(UUID.randomUUID(), null);
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(deploymentRepo.findBySiteIdOrderByCreatedAtDesc(siteId))
            .thenReturn(List.of(failed, ok1, ok2));
        when(incidentRepo.findByDeploymentIdAndIncidentTypeAndDetectedAtAfter(
            eq(failed.getId()), eq(ReleaseIncidentEntity.TYPE_DEPLOY_FAILED), any())).thenReturn(List.of());

        service.scan(Instant.now());

        ArgumentCaptor<ReleaseIncidentEntity> captor = ArgumentCaptor.forClass(ReleaseIncidentEntity.class);
        verify(incidentRepo).save(captor.capture());
        // 3 次部署 1 次失败 = 33.3% ≥ 30%，计数阈值 2 未达但错误率口径命中
        assertThat(captor.getValue().getIncidentType()).isEqualTo(ReleaseIncidentEntity.TYPE_DEPLOY_FAILED);
        assertThat(captor.getValue().getDetail()).contains("失败率").contains("33.33%");
    }

    @Test
    void errorRateThresholdUnsetKeepsCountOnlyBehavior() {
        ReleaseMonitorPolicyEntity policy = policy(true, 60, 2, ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        DeploymentEntity failed = failedDeployment();
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(deploymentRepo.findBySiteIdOrderByCreatedAtDesc(siteId))
            .thenReturn(List.of(failed, deployed(UUID.randomUUID(), null), deployed(UUID.randomUUID(), null)));

        service.scan(Instant.now());

        verify(incidentRepo, never()).save(any());
    }

    @Test
    void metricsComputesErrorRateAndOpenDegradedDuration() {
        ReleaseMonitorPolicyEntity policy = policyWithMetrics(true, 60, 1,
            new java.math.BigDecimal("0.2500"), 15, ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        when(policyRepo.findById(siteId)).thenReturn(Optional.of(policy));
        when(deploymentRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(List.of(
            failedDeployment(), deployed(UUID.randomUUID(), null),
            deployed(UUID.randomUUID(), null), deployed(UUID.randomUUID(), null)));
        ReleaseIncidentEntity pending = new ReleaseIncidentEntity();
        pending.setId(UUID.randomUUID());
        pending.setDeploymentId(UUID.randomUUID());
        pending.setDetectedAt(Instant.now().minusSeconds(1200));
        pending.setStatus(ReleaseIncidentEntity.STATUS_DEGRADED_PENDING);
        when(incidentRepo.findBySiteIdAndIncidentTypeAndStatus(siteId,
            ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED, ReleaseIncidentEntity.STATUS_DEGRADED_PENDING))
            .thenReturn(List.of(pending));

        ReleaseMonitorService.MonitorMetrics metrics = service.metrics(siteId);

        assertThat(metrics.deployments()).isEqualTo(4);
        assertThat(metrics.failed()).isEqualTo(1);
        assertThat(metrics.errorRate()).isEqualTo(0.25);
        assertThat(metrics.errorRateThreshold()).isEqualByComparingTo("0.2500");
        assertThat(metrics.degradedDurationMinutes()).isEqualTo(15);
        assertThat(metrics.openDegradedDurationMinutes()).isEqualTo(20L);
    }

    @Test
    void degradedDurationBelowThresholdRecordsPendingWithoutRollback() {
        ReleaseMonitorPolicyEntity policy = policyWithMetrics(true, 60, 1, null, 15,
            ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        DeployTargetEntity target = target();
        DeploymentEntity current = deployed(target.getId(), UUID.randomUUID());
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(targetRepo.findBySiteId(siteId)).thenReturn(List.of(target));
        when(deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(
            target.getId(), DeploymentEntity.STATUS_DEPLOYED)).thenReturn(Optional.of(current));
        when(healthChecker.check(target)).thenReturn(new DeploymentHealthChecker.Result(true, false, "HTTP 500"));

        service.scan(Instant.now());

        ArgumentCaptor<ReleaseIncidentEntity> captor = ArgumentCaptor.forClass(ReleaseIncidentEntity.class);
        verify(incidentRepo).save(captor.capture());
        ReleaseIncidentEntity incident = captor.getValue();
        assertThat(incident.getStatus()).isEqualTo(ReleaseIncidentEntity.STATUS_DEGRADED_PENDING);
        assertThat(incident.getAction()).isEqualTo(ReleaseIncidentEntity.ACTION_OBSERVE);
        assertThat(incident.getDetectedAt()).isNotNull();
        assertThat(incident.getDetail()).contains("持续观测中").contains("15 分钟");
        verify(deployService, never()).rollback(any());
    }

    @Test
    void degradedDurationReachedTriggersAutoRollbackAndUpgradesPending() {
        ReleaseMonitorPolicyEntity policy = policyWithMetrics(true, 60, 1, null, 15,
            ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        DeployTargetEntity target = target();
        DeploymentEntity current = deployed(target.getId(), UUID.randomUUID());
        ReleaseIncidentEntity pending = new ReleaseIncidentEntity();
        pending.setId(UUID.randomUUID());
        pending.setSiteId(siteId);
        pending.setIncidentType(ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED);
        pending.setDeploymentId(current.getId());
        pending.setReleaseId(current.getReleaseId());
        pending.setStatus(ReleaseIncidentEntity.STATUS_DEGRADED_PENDING);
        pending.setAction(ReleaseIncidentEntity.ACTION_OBSERVE);
        pending.setDetail("持续观测中");
        pending.setDetectedAt(Instant.now().minusSeconds(20 * 60));
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(targetRepo.findBySiteId(siteId)).thenReturn(List.of(target));
        when(deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(
            target.getId(), DeploymentEntity.STATUS_DEPLOYED)).thenReturn(Optional.of(current));
        when(healthChecker.check(target)).thenReturn(new DeploymentHealthChecker.Result(true, false, "HTTP 500"));
        when(incidentRepo.findFirstByDeploymentIdAndIncidentTypeOrderByDetectedAtDesc(
            current.getId(), ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED)).thenReturn(Optional.of(pending));
        DeployService.DeploymentInfo rollback = deploymentInfo(UUID.randomUUID(), siteId, "DEPLOYED");
        when(deployService.rollback(current.getId())).thenReturn(rollback);

        service.scan(Instant.now());

        assertThat(pending.getStatus()).isEqualTo(ReleaseIncidentEntity.STATUS_AUTO_ROLLED_BACK);
        assertThat(pending.getAction()).isEqualTo(ReleaseIncidentEntity.ACTION_AUTO_ROLLBACK);
        assertThat(pending.getRolledBackDeploymentId()).isEqualTo(rollback.id());
        assertThat(pending.getDetectedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(pending.getResolvedAt()).isNotNull();
        assertThat(pending.getDetail()).contains("已持续").contains("达到阈值 15 分钟");
        verify(deployService).rollback(current.getId());
    }

    @Test
    void healthyWithinObservationWindowClosesPendingAsRecovered() {
        ReleaseMonitorPolicyEntity policy = policyWithMetrics(true, 60, 1, null, 15,
            ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK);
        DeployTargetEntity target = target();
        DeploymentEntity current = deployed(target.getId(), UUID.randomUUID());
        ReleaseIncidentEntity pending = new ReleaseIncidentEntity();
        pending.setId(UUID.randomUUID());
        pending.setDeploymentId(current.getId());
        pending.setStatus(ReleaseIncidentEntity.STATUS_DEGRADED_PENDING);
        pending.setDetail("持续观测中");
        pending.setDetectedAt(Instant.now().minusSeconds(300));
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(targetRepo.findBySiteId(siteId)).thenReturn(List.of(target));
        when(deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(
            target.getId(), DeploymentEntity.STATUS_DEPLOYED)).thenReturn(Optional.of(current));
        when(healthChecker.check(target)).thenReturn(new DeploymentHealthChecker.Result(true, true, "HTTP 200"));
        when(incidentRepo.findFirstByDeploymentIdAndIncidentTypeOrderByDetectedAtDesc(
            current.getId(), ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED)).thenReturn(Optional.of(pending));

        service.scan(Instant.now());

        assertThat(pending.getStatus()).isEqualTo(ReleaseIncidentEntity.STATUS_RECOVERED);
        assertThat(pending.getResolvedAt()).isNotNull();
        assertThat(pending.getDetail()).contains("已关单").contains("健康恢复");
        verify(deployService, never()).rollback(any());
    }

    private ReleaseMonitorPolicyEntity policy(boolean enabled, int window, int threshold, String action) {
        ReleaseMonitorPolicyEntity policy = new ReleaseMonitorPolicyEntity();
        policy.setSiteId(siteId);
        policy.setEnabled(enabled);
        policy.setWindowMinutes(window);
        policy.setFailureThreshold(threshold);
        policy.setAction(action);
        return policy;
    }

    private ReleaseMonitorPolicyEntity policyWithMetrics(boolean enabled, int window, int threshold,
                                                         java.math.BigDecimal errorRate,
                                                         int degradedDurationMinutes, String action) {
        ReleaseMonitorPolicyEntity policy = policy(enabled, window, threshold, action);
        policy.setErrorRateThreshold(errorRate);
        policy.setDegradedDurationMinutes(degradedDurationMinutes);
        return policy;
    }

    private DeploymentEntity failedDeployment() {
        DeploymentEntity failed = new DeploymentEntity();
        failed.setId(UUID.randomUUID());
        failed.setSiteId(siteId);
        failed.setReleaseId(UUID.randomUUID());
        failed.setStatus(DeploymentEntity.STATUS_FAILED);
        failed.setCreatedAt(Instant.now().minusSeconds(60));
        return failed;
    }

    private DeployTargetEntity target() {
        DeployTargetEntity target = new DeployTargetEntity();
        target.setId(UUID.randomUUID());
        target.setSiteId(siteId);
        target.setName("prod");
        target.setTargetType(DeployTargetEntity.TYPE_LOCAL_NGINX);
        target.setConfig("{}");
        return target;
    }

    private DeploymentEntity deployed(UUID targetId, UUID previousId) {
        DeploymentEntity d = new DeploymentEntity();
        d.setId(UUID.randomUUID());
        d.setSiteId(siteId);
        d.setReleaseId(UUID.randomUUID());
        d.setDeployTargetId(targetId);
        d.setStatus(DeploymentEntity.STATUS_DEPLOYED);
        d.setPreviousDeploymentId(previousId);
        d.setCreatedAt(Instant.now().minusSeconds(30));
        return d;
    }

    private DeployService.DeploymentInfo deploymentInfo(UUID id, UUID ownerSite, String status) {
        return new DeployService.DeploymentInfo(id, ownerSite, UUID.randomUUID(), UUID.randomUUID(),
            null, "FULL", status, null, null, "HEALTHY", Instant.now(), Instant.now(), null,
            "system", null, Instant.now());
    }
}
