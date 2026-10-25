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
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 发布监控与异常自动回滚（FR-AI-AGENT-004）。
 *
 * <p>监控口径（站点策略默认关闭，显式开启后生效）：
 * <ul>
 *   <li><b>DEPLOY_FAILED</b>：统计窗口内失败部署数达到阈值 → 记事件；若动作为 AUTO_ROLLBACK，
 *       对目标当前仍处于 DEPLOYED 的部署执行既有回滚（发布流水线已在发布后健康检查失败时内联回滚，
 *       此处兜底窗口内反复失败且未恢复的场景）；</li>
 *   <li><b>HEALTH_DEGRADED</b>：目标当前 DEPLOYED 部署的健康检查变为不健康 → 记事件；
 *       AUTO_ROLLBACK 时执行既有 {@link DeployService#rollback}；</li>
 *   <li><b>ROLLBACK_DRILL</b>：回滚策略演练（只评估影响并留档，不真正回退）；</li>
 *   <li>同一部署同一类型不重复记事件；动作与结果全部落审计。</li>
 * </ul>
 *
 * <p>不使用第二套回滚实现：自动回退一律复用 {@link DeployService#rollback}（按目标类型切换指针/重放产物 +
 * 回滚后健康检查），监控层只负责判定与留痕。
 */
@Service
public class ReleaseMonitorService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseMonitorService.class);

    private static final int MIN_WINDOW_MINUTES = 5;
    private static final int MAX_WINDOW_MINUTES = 1440;
    private static final int MAX_FAILURE_THRESHOLD = 100;

    private final ReleaseMonitorPolicyRepository policyRepo;
    private final ReleaseIncidentRepository incidentRepo;
    private final DeploymentRepository deploymentRepo;
    private final DeployTargetRepository targetRepo;
    private final DeployService deployService;
    private final DeploymentHealthChecker healthChecker;
    private final AuditService audit;

    public ReleaseMonitorService(ReleaseMonitorPolicyRepository policyRepo,
                                 ReleaseIncidentRepository incidentRepo,
                                 DeploymentRepository deploymentRepo,
                                 DeployTargetRepository targetRepo,
                                 DeployService deployService,
                                 DeploymentHealthChecker healthChecker,
                                 AuditService audit) {
        this.policyRepo = policyRepo;
        this.incidentRepo = incidentRepo;
        this.deploymentRepo = deploymentRepo;
        this.targetRepo = targetRepo;
        this.deployService = deployService;
        this.healthChecker = healthChecker;
        this.audit = audit;
    }

    public record PolicyView(UUID siteId, boolean enabled, int windowMinutes, int failureThreshold,
                             java.math.BigDecimal errorRateThreshold, int degradedDurationMinutes,
                             String action, String updatedBy, Instant updatedAt) {}

    public record PolicyUpdate(Boolean enabled, Integer windowMinutes, Integer failureThreshold,
                               java.math.BigDecimal errorRateThreshold, Integer degradedDurationMinutes,
                               String action) {}

    /**
     * 监控窗口口径（G9-④）：窗口内部署总数 / 失败数 / 失败率与生效阈值，
     * 以及健康劣化持续时长阈值与当前最长观测中时长（分钟，无观测中事件为 {@code null}）。
     */
    public record MonitorMetrics(int windowMinutes, int deployments, int failed, Double errorRate,
                                 java.math.BigDecimal errorRateThreshold, int degradedDurationMinutes,
                                 Long openDegradedDurationMinutes) {}

    public record IncidentView(UUID id, UUID siteId, String incidentType, UUID deploymentId, UUID releaseId,
                               String status, String action, String detail, UUID rolledBackDeploymentId,
                               Instant detectedAt, Instant resolvedAt) {}

    public record TargetHealth(UUID targetId, String targetName, UUID deploymentId, UUID releaseId,
                               String deploymentStatus, String health, String detail,
                               long degradedDurationMinutes) {}

    // ======================================================================
    // 策略配置
    // ======================================================================

    public PolicyView getPolicy(UUID siteId) {
        return view(policyRepo.findById(siteId).orElse(null), siteId);
    }

    @Transactional
    public PolicyView updatePolicy(UUID siteId, PolicyUpdate req, String actor) {
        ReleaseMonitorPolicyEntity policy = policyRepo.findById(siteId)
            .orElseGet(ReleaseMonitorPolicyEntity::new);
        if (policy.getSiteId() == null) {
            policy.setSiteId(siteId);
        }
        if (req != null) {
            if (req.enabled() != null) policy.setEnabled(req.enabled());
            if (req.windowMinutes() != null) {
                if (req.windowMinutes() < MIN_WINDOW_MINUTES || req.windowMinutes() > MAX_WINDOW_MINUTES) {
                    throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                        "windowMinutes 必须在 [" + MIN_WINDOW_MINUTES + ", " + MAX_WINDOW_MINUTES + "] 区间");
                }
                policy.setWindowMinutes(req.windowMinutes());
            }
            if (req.failureThreshold() != null) {
                if (req.failureThreshold() < 1 || req.failureThreshold() > MAX_FAILURE_THRESHOLD) {
                    throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                        "failureThreshold 必须在 [1, " + MAX_FAILURE_THRESHOLD + "] 区间");
                }
                policy.setFailureThreshold(req.failureThreshold());
            }
            if (req.action() != null && !req.action().isBlank()) {
                String action = req.action().trim().toUpperCase(java.util.Locale.ROOT);
                if (!ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK.equals(action)
                    && !ReleaseMonitorPolicyEntity.ACTION_NOTIFY_ONLY.equals(action)) {
                    throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                        "action 仅支持 AUTO_ROLLBACK / NOTIFY_ONLY");
                }
                policy.setAction(action);
            }
            if (req.errorRateThreshold() != null) {
                java.math.BigDecimal rate = req.errorRateThreshold();
                if (rate.signum() < 0 || rate.compareTo(java.math.BigDecimal.ONE) > 0) {
                    throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                        "errorRateThreshold 必须在 [0, 1] 区间（0 表示关闭错误率口径）");
                }
                // 0 显式关闭：回到仅用失败计数阈值的旧行为
                policy.setErrorRateThreshold(rate.signum() == 0 ? null : rate);
            }
            if (req.degradedDurationMinutes() != null) {
                if (req.degradedDurationMinutes() < 0 || req.degradedDurationMinutes() > MAX_WINDOW_MINUTES) {
                    throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                        "degradedDurationMinutes 必须在 [0, " + MAX_WINDOW_MINUTES + "] 区间（0 表示立即动作）");
                }
                policy.setDegradedDurationMinutes(req.degradedDurationMinutes());
            }
        }
        policy.setUpdatedBy(actor);
        policyRepo.save(policy);
        audit.write(actor, "release.monitor.policy.update", "ReleaseMonitorPolicy", siteId.toString(), siteId,
            "SUCCESS", "enabled=" + policy.isEnabled() + " window=" + policy.getWindowMinutes()
                + " threshold=" + policy.getFailureThreshold() + " errorRate=" + policy.getErrorRateThreshold()
                + " degradedDuration=" + policy.getDegradedDurationMinutes()
                + " action=" + policy.getAction());
        return view(policy, siteId);
    }

    public List<IncidentView> listIncidents(UUID siteId) {
        return incidentRepo.findTop50BySiteIdOrderByDetectedAtDesc(siteId).stream().map(this::view).toList();
    }

    /**
     * 监控窗口口径（G9-④，只读出口）：窗口内部署总数、失败数与失败率、生效阈值，
     * 以及健康劣化持续时长阈值与当前最长观测中时长（分钟）。
     */
    public MonitorMetrics metrics(UUID siteId) {
        PolicyView policy = getPolicy(siteId);
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(policy.windowMinutes() * 60L);
        List<DeploymentEntity> windowDeployments = deploymentRepo.findBySiteIdOrderByCreatedAtDesc(siteId).stream()
            .filter(d -> d.getCreatedAt() != null && d.getCreatedAt().isAfter(windowStart))
            .toList();
        int failed = (int) windowDeployments.stream()
            .filter(d -> DeploymentEntity.STATUS_FAILED.equals(d.getStatus()))
            .count();
        Double errorRate = windowDeployments.isEmpty() ? null : (double) failed / windowDeployments.size();
        Long openDuration = openDegradedDurationMinutes(siteId, now);
        return new MonitorMetrics(policy.windowMinutes(), windowDeployments.size(), failed, errorRate,
            policy.errorRateThreshold(), policy.degradedDurationMinutes(), openDuration);
    }

    /** 当前「持续观测中」事件的最长已持续分钟数（无观测中事件返回 {@code null}）。 */
    private Long openDegradedDurationMinutes(UUID siteId, Instant now) {
        return incidentRepo.findBySiteIdAndIncidentTypeAndStatus(
                siteId, ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED, ReleaseIncidentEntity.STATUS_DEGRADED_PENDING)
            .stream()
            .filter(i -> i.getDetectedAt() != null)
            .map(i -> java.time.Duration.between(i.getDetectedAt(), now).toMinutes())
            .max(Long::compareTo)
            .orElse(null);
    }

    /** 目标当前部署与健康状态（get_release_health 只读出口）；含持续观测中时长（分钟，无观测为 0）。 */
    public List<TargetHealth> currentHealth(UUID siteId) {
        List<TargetHealth> result = new ArrayList<>();
        for (DeployTargetEntity target : targetRepo.findBySiteId(siteId)) {
            DeploymentEntity current = deploymentRepo
                .findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(target.getId(), DeploymentEntity.STATUS_DEPLOYED)
                .orElse(null);
            if (current == null) {
                result.add(new TargetHealth(target.getId(), target.getName(), null, null, null,
                    DeploymentEntity.HEALTH_PENDING, "当前无已部署发布", 0L));
                continue;
            }
            DeploymentHealthChecker.Result health = healthChecker.check(target);
            result.add(new TargetHealth(target.getId(), target.getName(), current.getId(), current.getReleaseId(),
                current.getStatus(), healthLabel(health), health.detail(),
                degradedDurationMinutes(current.getId(), Instant.now())));
        }
        return result;
    }

    /** 指定部署「持续观测中」的已持续分钟数（无观测中事件返回 0）。 */
    private long degradedDurationMinutes(UUID deploymentId, Instant now) {
        return incidentRepo
            .findFirstByDeploymentIdAndIncidentTypeOrderByDetectedAtDesc(
                deploymentId, ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED)
            .filter(i -> ReleaseIncidentEntity.STATUS_DEGRADED_PENDING.equals(i.getStatus()))
            .filter(i -> i.getDetectedAt() != null)
            .map(i -> java.time.Duration.between(i.getDetectedAt(), now).toMinutes())
            .orElse(0L);
    }

    // ======================================================================
    // 回滚演练
    // ======================================================================

    /** 回滚策略演练（FR-AI-AGENT-004）：评估影响并留 SIMULATED 事件，不执行任何回退。 */
    @Transactional
    public IncidentView recordDrill(UUID siteId, UUID deploymentId, String actor) {
        DeploymentEntity deployment = resolveDrillTarget(siteId, deploymentId);
        String detail = drillImpact(deployment);
        ReleaseIncidentEntity incident = new ReleaseIncidentEntity();
        incident.setSiteId(siteId);
        incident.setIncidentType(ReleaseIncidentEntity.TYPE_ROLLBACK_DRILL);
        incident.setDeploymentId(deployment.getId());
        incident.setReleaseId(deployment.getReleaseId());
        incident.setStatus(ReleaseIncidentEntity.STATUS_SIMULATED);
        incident.setAction(ReleaseIncidentEntity.ACTION_DRILL);
        incident.setDetail(detail);
        incident.setResolvedAt(Instant.now());
        incidentRepo.save(incident);
        audit.write(actor, "release.rollback.drill", "Deployment", deployment.getId().toString(), siteId,
            "SUCCESS", detail);
        return view(incident);
    }

    private DeploymentEntity resolveDrillTarget(UUID siteId, UUID deploymentId) {
        if (deploymentId != null) {
            DeploymentEntity deployment = deploymentRepo.findById(deploymentId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "部署记录不存在"));
            if (!deployment.getSiteId().equals(siteId)) {
                throw new BusinessException(ErrorCodes.NOT_FOUND, "部署记录不存在");
            }
            return deployment;
        }
        for (DeployTargetEntity target : targetRepo.findBySiteId(siteId)) {
            var current = deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(
                target.getId(), DeploymentEntity.STATUS_DEPLOYED);
            if (current.isPresent()) {
                return current.get();
            }
        }
        throw new BusinessException(ErrorCodes.CONFLICT, "站点当前无已部署发布，无法演练回滚");
    }

    private String drillImpact(DeploymentEntity deployment) {
        UUID previousId = deployment.getPreviousDeploymentId();
        StringBuilder sb = new StringBuilder();
        sb.append("[演练] 回滚部署 ").append(deployment.getId())
            .append("（发布 ").append(deployment.getReleaseId())
            .append("，目标 ").append(deployment.getDeployTargetId()).append("）");
        if (previousId == null) {
            sb.append("\n回退目标：无（首次部署），演练结论：无可回退历史，实际异常时需人工介入");
        } else {
            sb.append("\n回退目标：部署 ").append(previousId);
            sb.append("\n演练结论：具备回退路径，执行将复用既有部署回滚并做回滚后健康检查");
        }
        return sb.toString();
    }

    // ======================================================================
    // 监控扫描
    // ======================================================================

    /** 扫描全部启用监控策略的站点（分钟内定时调用；单站点失败隔离）。 */
    public void scan(Instant now) {
        for (ReleaseMonitorPolicyEntity policy : policyRepo.findByEnabledTrue()) {
            try {
                evaluate(policy, now);
            } catch (RuntimeException e) {
                log.warn("发布监控站点 {} 判定失败：{}", policy.getSiteId(), e.getMessage());
            }
        }
    }

    private void evaluate(ReleaseMonitorPolicyEntity policy, Instant now) {
        UUID siteId = policy.getSiteId();
        Instant windowStart = now.minusSeconds(policy.getWindowMinutes() * 60L);
        evaluateFailureThreshold(policy, siteId, windowStart);
        evaluateHealthDegradation(policy, siteId, now);
    }

    /**
     * 失败口径（G9-④）：窗口内失败计数达到阈值**或**窗口内失败率达到阈值（两者「或」关系）即判定异常；
     * 未配置错误率阈值时仅用计数口径（等价于旧行为）。同一部署同一类型仍不重复记事件。
     */
    private void evaluateFailureThreshold(ReleaseMonitorPolicyEntity policy, UUID siteId, Instant windowStart) {
        List<DeploymentEntity> windowDeployments = deploymentRepo.findBySiteIdOrderByCreatedAtDesc(siteId).stream()
            .filter(d -> d.getCreatedAt() != null && d.getCreatedAt().isAfter(windowStart))
            .toList();
        List<DeploymentEntity> recentFailed = windowDeployments.stream()
            .filter(d -> DeploymentEntity.STATUS_FAILED.equals(d.getStatus()))
            .toList();
        List<String> triggers = new ArrayList<>();
        if (recentFailed.size() >= policy.getFailureThreshold()) {
            triggers.add("窗口 " + policy.getWindowMinutes() + " 分钟内失败部署 " + recentFailed.size()
                + " 次（计数阈值 " + policy.getFailureThreshold() + "）");
        }
        Double errorRate = windowDeployments.isEmpty() ? null
            : (double) recentFailed.size() / windowDeployments.size();
        if (policy.getErrorRateThreshold() != null && errorRate != null
            && policy.getErrorRateThreshold().doubleValue() <= errorRate) {
            triggers.add("窗口内失败率 " + percent(errorRate) + "（错误率阈值 "
                + percent(policy.getErrorRateThreshold().doubleValue()) + "，"
                + recentFailed.size() + "/" + windowDeployments.size() + "）");
        }
        if (triggers.isEmpty()) {
            return;
        }
        String reason = String.join("；", triggers);
        for (DeploymentEntity failed : recentFailed) {
            if (!incidentRepo.findByDeploymentIdAndIncidentTypeAndDetectedAtAfter(
                    failed.getId(), ReleaseIncidentEntity.TYPE_DEPLOY_FAILED, windowStart).isEmpty()) {
                continue;
            }
            String detail = reason + "：部署 " + failed.getId()
                + "（发布 " + failed.getReleaseId() + "）"
                + (failed.getNote() == null ? "" : "；原因：" + failed.getNote());
            // 发布流水线已在发布后健康检查失败时内联回滚；窗口内反复失败统一告警，不再二次回退健康部署。
            record(siteId, ReleaseIncidentEntity.TYPE_DEPLOY_FAILED, failed.getId(), failed.getReleaseId(),
                ReleaseIncidentEntity.STATUS_NOTIFIED, ReleaseIncidentEntity.ACTION_NOTIFY, detail, null);
        }
    }

    private static String percent(double value) {
        return new java.math.BigDecimal(value * 100).setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * 健康劣化口径（G9-④）：持续时长阈值 {@code > 0} 时先落「持续观测中」事件留痕，
     * 达到阈值才通知/自动回退；观测期内恢复或部署被取代则关单 {@code RECOVERED}。
     * 阈值 {@code = 0} 时保持既有「立即动作」行为。
     */
    private void evaluateHealthDegradation(ReleaseMonitorPolicyEntity policy, UUID siteId, Instant now) {
        for (DeployTargetEntity target : targetRepo.findBySiteId(siteId)) {
            DeploymentEntity current = deploymentRepo
                .findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(target.getId(), DeploymentEntity.STATUS_DEPLOYED)
                .orElse(null);
            if (current == null) {
                continue;
            }
            DeploymentHealthChecker.Result health = healthChecker.check(target);
            if (!health.configured()) {
                continue;
            }
            ReleaseIncidentEntity pending = pendingDegradation(current.getId());
            if (health.healthy()) {
                if (pending != null) {
                    resolveRecovered(pending, "观测期内健康恢复（持续 "
                        + java.time.Duration.between(pending.getDetectedAt(), now).toMinutes() + " 分钟）");
                }
                continue;
            }
            if (policy.getDegradedDurationMinutes() > 0) {
                if (pending == null) {
                    if (incidentRepo.existsByDeploymentIdAndIncidentType(
                            current.getId(), ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED)) {
                        continue;
                    }
                    recordPending(siteId, current, health.detail(), now, policy.getDegradedDurationMinutes());
                    continue;
                }
                long elapsed = java.time.Duration.between(pending.getDetectedAt(), now).toMinutes();
                if (elapsed < policy.getDegradedDurationMinutes()) {
                    continue;
                }
                handleHealthDegradation(policy, siteId, current, health.detail(), pending, elapsed);
                continue;
            }
            if (incidentRepo.existsByDeploymentIdAndIncidentType(
                    current.getId(), ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED)) {
                continue;
            }
            handleHealthDegradation(policy, siteId, current, health.detail(), null, 0);
        }
        closeStalePendingIncidents(siteId, now);
    }

    private ReleaseIncidentEntity pendingDegradation(UUID deploymentId) {
        return incidentRepo
            .findFirstByDeploymentIdAndIncidentTypeOrderByDetectedAtDesc(
                deploymentId, ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED)
            .filter(i -> ReleaseIncidentEntity.STATUS_DEGRADED_PENDING.equals(i.getStatus()))
            .orElse(null);
    }

    /** 落「持续观测中」事件：留痕但不动作（resolved_at 留空表示仍在观测）。 */
    private void recordPending(UUID siteId, DeploymentEntity current, String healthDetail, Instant now,
                               int thresholdMinutes) {
        String detail = "目标 " + current.getDeployTargetId() + " 当前部署 " + current.getId()
            + "（发布 " + current.getReleaseId() + "）健康检查不健康：" + healthDetail
            + "\n持续观测中：阈值 " + thresholdMinutes + " 分钟，未达阈值暂不通知/回退";
        ReleaseIncidentEntity incident = new ReleaseIncidentEntity();
        incident.setSiteId(siteId);
        incident.setIncidentType(ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED);
        incident.setDeploymentId(current.getId());
        incident.setReleaseId(current.getReleaseId());
        incident.setStatus(ReleaseIncidentEntity.STATUS_DEGRADED_PENDING);
        incident.setAction(ReleaseIncidentEntity.ACTION_OBSERVE);
        incident.setDetail(detail);
        incident.setDetectedAt(now);
        incidentRepo.save(incident);
        audit.write("system:release-monitor", "release.incident.health_degraded",
            "Deployment", current.getId().toString(), siteId, ReleaseIncidentEntity.STATUS_DEGRADED_PENDING, detail);
    }

    /** 观测期内恢复（或部署被取代）：关单为 RECOVERED，不执行任何回退。 */
    private void resolveRecovered(ReleaseIncidentEntity incident, String reason) {
        incident.setStatus(ReleaseIncidentEntity.STATUS_RECOVERED);
        incident.setDetail((incident.getDetail() == null ? "" : incident.getDetail() + "\n") + "已关单：" + reason);
        incident.setResolvedAt(Instant.now());
        incidentRepo.save(incident);
        audit.write("system:release-monitor", "release.incident.health_degraded",
            "Deployment", incident.getDeploymentId() == null ? "-" : incident.getDeploymentId().toString(),
            incident.getSiteId(), ReleaseIncidentEntity.STATUS_RECOVERED, incident.getDetail());
    }

    /** 部署已被后续部署取代/已回退的「持续观测中」事件关单，避免观测数据长期悬空。 */
    private void closeStalePendingIncidents(UUID siteId, Instant now) {
        for (ReleaseIncidentEntity pending : incidentRepo.findBySiteIdAndIncidentTypeAndStatus(
                siteId, ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED,
                ReleaseIncidentEntity.STATUS_DEGRADED_PENDING)) {
            if (pending.getDeploymentId() == null) {
                continue;
            }
            boolean stillDeployed = deploymentRepo.findById(pending.getDeploymentId())
                .map(d -> DeploymentEntity.STATUS_DEPLOYED.equals(d.getStatus()))
                .orElse(false);
            if (!stillDeployed) {
                resolveRecovered(pending, "部署已结束或被后续部署取代");
            }
        }
    }

    private void handleHealthDegradation(ReleaseMonitorPolicyEntity policy, UUID siteId,
                                         DeploymentEntity current, String healthDetail,
                                         ReleaseIncidentEntity pending, long elapsedMinutes) {
        String duration = pending == null ? ""
            : "（已持续 " + elapsedMinutes + " 分钟，达到阈值 " + policy.getDegradedDurationMinutes() + " 分钟）";
        String detail = "目标 " + current.getDeployTargetId() + " 当前部署 " + current.getId()
            + "（发布 " + current.getReleaseId() + "）健康检查不健康：" + healthDetail + duration;
        boolean auto = ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK.equals(policy.getAction());
        String status = ReleaseIncidentEntity.STATUS_NOTIFIED;
        String action = ReleaseIncidentEntity.ACTION_NOTIFY;
        UUID rolledBack = null;
        if (auto && current.getPreviousDeploymentId() != null) {
            action = ReleaseIncidentEntity.ACTION_AUTO_ROLLBACK;
            try {
                DeployService.DeploymentInfo rollback = deployService.rollback(current.getId());
                rolledBack = rollback.id();
                status = ReleaseIncidentEntity.STATUS_AUTO_ROLLED_BACK;
                detail = detail + "\n已自动回滚：新部署 " + rollback.id() + "，状态 " + rollback.status()
                    + (rollback.healthCheckAfter() == null ? "" : "，回滚后健康 " + rollback.healthCheckAfter());
            } catch (RuntimeException e) {
                status = ReleaseIncidentEntity.STATUS_ROLLBACK_FAILED;
                detail = detail + "\n自动回滚失败：" + e.getMessage();
            }
        } else if (auto) {
            detail = detail + "\n无历史部署可回退，转为告警";
        }
        if (pending != null) {
            pending.setStatus(status);
            pending.setAction(action);
            pending.setDetail(detail);
            pending.setRolledBackDeploymentId(rolledBack);
            if (!ReleaseIncidentEntity.STATUS_NOTIFIED.equals(status)) {
                pending.setResolvedAt(Instant.now());
            }
            incidentRepo.save(pending);
            audit.write("system:release-monitor", "release.incident.health_degraded",
                "Deployment", current.getId().toString(), siteId, status, detail);
            return;
        }
        record(siteId, ReleaseIncidentEntity.TYPE_HEALTH_DEGRADED, current.getId(), current.getReleaseId(),
            status, action, detail, rolledBack);
    }

    private void record(UUID siteId, String type, UUID deploymentId, UUID releaseId,
                        String status, String action, String detail, UUID rolledBackDeploymentId) {
        ReleaseIncidentEntity incident = new ReleaseIncidentEntity();
        incident.setSiteId(siteId);
        incident.setIncidentType(type);
        incident.setDeploymentId(deploymentId);
        incident.setReleaseId(releaseId);
        incident.setStatus(status);
        incident.setAction(action);
        incident.setDetail(detail);
        incident.setRolledBackDeploymentId(rolledBackDeploymentId);
        if (!ReleaseIncidentEntity.STATUS_NOTIFIED.equals(status)) {
            incident.setResolvedAt(Instant.now());
        }
        incidentRepo.save(incident);
        audit.write("system:release-monitor", "release.incident." + type.toLowerCase(java.util.Locale.ROOT),
            "Deployment", deploymentId == null ? "-" : deploymentId.toString(), siteId, status, detail);
    }

    private static String healthLabel(DeploymentHealthChecker.Result health) {
        if (!health.configured()) {
            return DeploymentEntity.HEALTH_PENDING;
        }
        return health.healthy() ? DeploymentEntity.HEALTH_HEALTHY : DeploymentEntity.HEALTH_UNHEALTHY;
    }

    private static PolicyView view(ReleaseMonitorPolicyEntity policy, UUID siteId) {
        if (policy == null) {
            return new PolicyView(siteId, false, 60, 1, null, 0,
                ReleaseMonitorPolicyEntity.ACTION_AUTO_ROLLBACK, null, null);
        }
        return new PolicyView(policy.getSiteId(), policy.isEnabled(), policy.getWindowMinutes(),
            policy.getFailureThreshold(), policy.getErrorRateThreshold(), policy.getDegradedDurationMinutes(),
            policy.getAction(), policy.getUpdatedBy(), policy.getUpdatedAt());
    }

    private IncidentView view(ReleaseIncidentEntity incident) {
        return new IncidentView(incident.getId(), incident.getSiteId(), incident.getIncidentType(),
            incident.getDeploymentId(), incident.getReleaseId(), incident.getStatus(), incident.getAction(),
            incident.getDetail(), incident.getRolledBackDeploymentId(), incident.getDetectedAt(),
            incident.getResolvedAt());
    }
}
