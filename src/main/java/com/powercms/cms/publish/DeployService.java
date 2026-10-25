package com.powercms.cms.publish;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.publish.entity.BuildArtifactEntity;
import com.powercms.cms.publish.entity.BuildJobEntity;
import com.powercms.cms.publish.entity.DeployTargetEntity;
import com.powercms.cms.publish.entity.DeploymentEntity;
import com.powercms.cms.publish.entity.ReleaseEntity;
import com.powercms.cms.publish.repo.BuildArtifactRepository;
import com.powercms.cms.publish.repo.BuildJobRepository;
import com.powercms.cms.publish.repo.DeployTargetRepository;
import com.powercms.cms.publish.repo.DeploymentRepository;
import com.powercms.cms.publish.repo.ReleaseRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 构建产物、发布目标、部署与回滚（P4）。
 * 验收口径：FR-PUBLISH-001~007。
 */
@Service
@Transactional
public class DeployService {

    private final DeployTargetRepository targetRepo;
    private final BuildArtifactRepository artifactRepo;
    private final DeploymentRepository deploymentRepo;
    private final BuildJobRepository buildJobRepo;
    private final ReleaseRepository releaseRepo;
    private final LocalNginxPublisher publisher;
    private final GitPublisher gitPublisher;
    private final DeploymentHealthChecker healthChecker;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final StaticPublishGuard staticGuard;

    /** FR-PUBLISH-004：进行中（非终态）部署，新部署须与其串行。 */
    private static final List<String> ACTIVE_DEPLOYMENT_STATUSES = List.of(
        DeploymentEntity.STATUS_QUEUED, DeploymentEntity.STATUS_BUILDING,
        DeploymentEntity.STATUS_VALIDATING, DeploymentEntity.STATUS_DEPLOYING,
        DeploymentEntity.STATUS_INDEXING);

    DeployService(DeployTargetRepository targetRepo, BuildArtifactRepository artifactRepo,
                  DeploymentRepository deploymentRepo, BuildJobRepository buildJobRepo,
                  ReleaseRepository releaseRepo, LocalNginxPublisher publisher, GitPublisher gitPublisher,
                  DeploymentHealthChecker healthChecker, ApplicationEventPublisher events, AuditService audit,
                  StaticPublishGuard staticGuard) {
        this.targetRepo = targetRepo;
        this.artifactRepo = artifactRepo;
        this.deploymentRepo = deploymentRepo;
        this.buildJobRepo = buildJobRepo;
        this.releaseRepo = releaseRepo;
        this.publisher = publisher;
        this.gitPublisher = gitPublisher;
        this.healthChecker = healthChecker;
        this.events = events;
        this.audit = audit;
        this.staticGuard = staticGuard;
    }

    // ========== Deploy Target ==========

    /**
     * 部署目标读模型：{@code config} **脱敏后**输出（TC-M34-011）。
     *
     * <p>写库仍是原文（发布执行需要真实凭据），但任何读口（详情/列表/创建回显）都不得把
     * password/token 类字段明文回给前端——此前直接返回 {@link DeployTargetEntity#getConfig()}。</p>
     */
    public record TargetInfo(UUID id, UUID siteId, String name, String targetType,
                              String config, Instant createdAt) {
        public static TargetInfo from(DeployTargetEntity t) {
            return new TargetInfo(t.getId(), t.getSiteId(), t.getName(), t.getTargetType(),
                maskConfig(t.getConfig()), t.getCreatedAt());
        }
    }

    /** 掩码占位符：只暴露「已配置」这一事实，不暴露取值。 */
    private static final String MASK = "******";

    /** 凭据类字段名（小写包含匹配）：password / pwd / secret / token / credential / apiKey…。 */
    private static final List<String> SECRET_KEY_HINTS = List.of(
        "password", "passwd", "pwd", "secret", "token", "credential", "apikey", "accesskey",
        "privatekey", "clientsecret", "passphrase");

    private static final com.fasterxml.jackson.databind.ObjectMapper MASK_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** 递归掩码 JSON 配置中的凭据字段；非 JSON 形态退化为逐条 key:value 正则掩码。 */
    static String maskConfig(String config) {
        if (config == null || config.isBlank()) return config;
        try {
            com.fasterxml.jackson.databind.JsonNode root = MASK_MAPPER.readTree(config);
            if (root == null || root.isMissingNode()) return config;
            maskNode(root);
            return MASK_MAPPER.writeValueAsString(root);
        } catch (Exception notJson) {
            String masked = config;
            for (String hint : SECRET_KEY_HINTS) {
                masked = masked.replaceAll("(?i)(\"" + java.util.regex.Pattern.quote(hint)
                    + "[^\"]*\"\\s*:\\s*\")([^\"]*)(\")", "$1" + MASK + "$3");
            }
            return masked;
        }
    }

    private static void maskNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode obj =
                (com.fasterxml.jackson.databind.node.ObjectNode) node;
            java.util.Iterator<String> names = obj.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                com.fasterxml.jackson.databind.JsonNode value = obj.get(key);
                if (isSecretKey(key)) {
                    obj.put(key, MASK);
                } else {
                    maskNode(value);
                }
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) maskNode(child);
        }
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return SECRET_KEY_HINTS.stream().anyMatch(lower::contains);
    }

    public TargetInfo createTarget(UUID siteId, String name, String targetType, String config) {
        var t = new DeployTargetEntity();
        t.setSiteId(siteId);
        t.setName(name);
        t.setTargetType(targetType != null ? targetType : DeployTargetEntity.TYPE_LOCAL_NGINX);
        t.setConfig(config != null ? config : "{}");
        t = targetRepo.save(t);
        return TargetInfo.from(t);
    }

    public List<TargetInfo> listTargets(UUID siteId) {
        return targetRepo.findBySiteId(siteId).stream().map(TargetInfo::from).toList();
    }

    public TargetInfo getTarget(UUID id) { return TargetInfo.from(requireTarget(id)); }

    public TargetInfo updateTarget(UUID id, String config) {
        var t = requireTarget(id);
        t.setConfig(config);
        t = targetRepo.save(t);
        return TargetInfo.from(t);
    }

    public void deleteTarget(UUID id) { targetRepo.delete(requireTarget(id)); }

    // ========== Artifact (FR-BUILD-007) ==========

    public record ArtifactInfo(UUID id, UUID buildJobId, UUID releaseId, String artifactPath,
                                int fileCount, long sizeBytes, String routeManifest,
                                String fileManifest, String inputDigest,
                                String buildType, Instant createdAt) {
        public static ArtifactInfo from(BuildArtifactEntity a) {
            return new ArtifactInfo(a.getId(), a.getBuildJobId(), a.getReleaseId(),
                a.getArtifactPath(), a.getFileCount(), a.getSizeBytes(),
                a.getRouteManifest(), a.getFileManifest(), a.getInputDigest(),
                a.getBuildType(), a.getCreatedAt());
        }
    }

    public ArtifactInfo createArtifact(UUID buildJobId, UUID releaseId, String artifactPath,
                                        int fileCount, long sizeBytes,
                                        String routeManifest, String fileManifest,
                                        String inputDigest, String buildType) {
        var a = new BuildArtifactEntity();
        a.setBuildJobId(buildJobId);
        a.setReleaseId(releaseId);
        a.setArtifactPath(artifactPath);
        a.setFileCount(fileCount);
        a.setSizeBytes(sizeBytes);
        a.setRouteManifest(routeManifest);
        a.setFileManifest(fileManifest);
        a.setInputDigest(inputDigest);
        a.setBuildType(buildType != null ? buildType : BuildArtifactEntity.BUILD_FULL);
        a = artifactRepo.save(a);
        return ArtifactInfo.from(a);
    }

    public List<ArtifactInfo> listArtifacts(UUID releaseId) {
        return artifactRepo.findByReleaseId(releaseId).stream().map(ArtifactInfo::from).toList();
    }

    public ArtifactInfo getArtifact(UUID id) { return ArtifactInfo.from(requireArtifact(id)); }

    /**
     * 构建成功后自动注册 Artifact（FR-BUILD-007）。
     * 在 BuildJob 标记为 SUCCESS 时由 BuildService 或外部 Worker 调用。
     */
    public ArtifactInfo registerArtifactFromBuild(UUID buildJobId, String artifactPath,
                                                    int fileCount, long sizeBytes,
                                                    String routeManifest, String fileManifest,
                                                    String inputDigest) {
        var job = buildJobRepo.findById(buildJobId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "构建任务不存在"));
        if (!BuildJobEntity.STATUS_SUCCESS.equals(job.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "构建未成功，不能注册产物");
        }
        var existing = artifactRepo.findByBuildJobId(buildJobId);
        if (existing.isPresent()) {
            return ArtifactInfo.from(existing.get());
        }
        return createArtifact(buildJobId, job.getReleaseId(), artifactPath,
            fileCount, sizeBytes, routeManifest, fileManifest, inputDigest,
            job.getReleaseId() != null ? BuildArtifactEntity.BUILD_FULL : BuildArtifactEntity.BUILD_INCREMENTAL);
    }

    // ========== Deployment (FR-PUBLISH-002, 004~007) ==========

    public record DeploymentInfo(UUID id, UUID siteId, UUID releaseId, UUID deployTargetId,
                                  UUID artifactId, String buildType, String status,
                                  UUID previousDeploymentId,
                                  String healthCheckBefore, String healthCheckAfter,
                                  Instant startedAt, Instant finishedAt, Instant scheduledAt,
                                  String deployedBy, String note, Instant createdAt) {
        public static DeploymentInfo from(DeploymentEntity d) {
            return new DeploymentInfo(d.getId(), d.getSiteId(), d.getReleaseId(),
                d.getDeployTargetId(), d.getArtifactId(), d.getBuildType(),
                d.getStatus(), d.getPreviousDeploymentId(),
                d.getHealthCheckBefore(), d.getHealthCheckAfter(),
                d.getStartedAt(), d.getFinishedAt(), d.getScheduledAt(),
                d.getDeployedBy(), d.getNote(), d.getCreatedAt());
        }
    }

    /** 创建部署任务，支持排期（FR-PUBLISH-004）。仅已冻结 Release 可发起；同目标串行。 */
    public DeploymentInfo createDeployment(UUID siteId, UUID releaseId, UUID deployTargetId,
                                            UUID artifactId, String buildType,
                                            Instant scheduledAt, String deployedBy) {
        // TC-M34-012：入参缺失须 4xx——此前 artifactId=null 直抵 requireArtifact(null)，
        // JPA 的「given id must not be null」异常穿过全局处理器变成 500 INTERNAL_ERROR。
        if (artifactId == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "artifactId 不能为空");
        }
        var target = requireTarget(deployTargetId);
        var release = releaseRepo.findById(releaseId).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "release not found"));
        if (!target.getSiteId().equals(siteId) || !release.getSiteId().equals(siteId)) throw new BusinessException(ErrorCodes.CONFLICT, "site/target/release mismatch");
        var artifact = requireArtifact(artifactId);
        if (!artifact.getReleaseId().equals(releaseId)) throw new BusinessException(ErrorCodes.CONFLICT, "artifact/release mismatch");

        // FR-PUBLISH-001：发布前必须经过批准并冻结（服务端强制，不依赖前端）
        if (!ReleaseEntity.STATUS_FROZEN.equals(release.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "只能为已冻结 Release 创建部署");
        }
        // FR-PUBLISH-004：同一发布目标并发发布串行化——存在进行中部署时拒绝
        if (deploymentRepo.existsByDeployTargetIdAndStatusIn(deployTargetId, ACTIVE_DEPLOYMENT_STATUSES)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "该发布目标存在进行中的部署，发布须串行执行");
        }

        // 记录上一个成功部署用于回滚
        UUID prevDeploymentId = deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(
            deployTargetId, DeploymentEntity.STATUS_DEPLOYED)
            .map(DeploymentEntity::getId).orElse(null);

        var d = new DeploymentEntity();
        d.setSiteId(siteId);
        d.setReleaseId(releaseId);
        d.setDeployTargetId(deployTargetId);
        d.setArtifactId(artifactId);
        d.setBuildType(buildType != null ? buildType : "FULL");
        d.setScheduledAt(scheduledAt);
        d.setDeployedBy(deployedBy);
        d.setPreviousDeploymentId(prevDeploymentId);
        d = deploymentRepo.save(d);

        audit.write(actor(deployedBy), "DEPLOYMENT_CREATE", "deployment", d.getId().toString(), siteId, "SUCCESS", null,
            "release=" + releaseId + " target=" + deployTargetId + (scheduledAt != null ? " scheduledAt=" + scheduledAt : ""));
        return DeploymentInfo.from(d);
    }

    /**
     * FR-AI-AGENT-003：规则化调度发布——为已冻结 Release 在目标上创建「未来排期」的排队部署。
     * 构建产物缺省取该 Release 最新产物（Agent 无需感知构建细节）。
     */
    public DeploymentInfo scheduleDeployment(UUID siteId, UUID releaseId, UUID deployTargetId,
                                             Instant scheduledAt, String deployedBy) {
        SchedulePlan plan = planSchedule(siteId, releaseId, deployTargetId, scheduledAt);
        return createDeployment(plan.release().getSiteId(), releaseId, plan.target().getId(),
            plan.artifact().getId(), null, scheduledAt, deployedBy);
    }

    /** 调度发布影响说明（dry-run）：校验并给出将排期的发布、目标、时间与产物，不创建任何记录。 */
    public String scheduleImpact(UUID siteId, UUID releaseId, UUID deployTargetId, Instant scheduledAt) {
        SchedulePlan plan = planSchedule(siteId, releaseId, deployTargetId, scheduledAt);
        return "排期发布「" + plan.release().getName() + "」（" + plan.release().getId()
            + "，状态 " + plan.release().getStatus() + "）\n"
            + "发布目标：" + plan.target().getName() + "（" + plan.target().getId()
            + "，类型 " + plan.target().getTargetType() + "）\n"
            + "排期时间：" + scheduledAt + "\n"
            + "构建产物：" + plan.artifact().getId() + "（文件 " + plan.artifact().getFileCount() + " 个）\n"
            + "执行方式：到期由发布调度器排队执行，发布前/后做健康检查（FR-PUBLISH-004/007）";
    }

    private record SchedulePlan(DeployTargetEntity target, ReleaseEntity release, BuildArtifactEntity artifact) {}

    private SchedulePlan planSchedule(UUID siteId, UUID releaseId, UUID deployTargetId, Instant scheduledAt) {
        if (scheduledAt == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "排期时间 scheduled_at 不能为空");
        }
        if (!scheduledAt.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "排期时间必须晚于当前时间");
        }
        var release = releaseRepo.findById(releaseId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "release not found"));
        if (siteId != null && !release.getSiteId().equals(siteId)) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "release not found");
        }
        if (!ReleaseEntity.STATUS_FROZEN.equals(release.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "只能为已冻结 Release 排期发布，当前状态 " + release.getStatus());
        }
        DeployTargetEntity target;
        if (deployTargetId != null) {
            target = requireTarget(deployTargetId);
            if (!target.getSiteId().equals(release.getSiteId())) {
                throw new BusinessException(ErrorCodes.NOT_FOUND, "发布目标不存在");
            }
        } else {
            target = targetRepo.findBySiteId(release.getSiteId()).stream().findFirst().orElse(null);
            if (target == null) {
                throw new BusinessException(ErrorCodes.CONFLICT, "站点尚未配置发布目标");
            }
        }
        if (deploymentRepo.existsByDeployTargetIdAndStatusIn(target.getId(), ACTIVE_DEPLOYMENT_STATUSES)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "该发布目标存在进行中的部署，发布须串行执行");
        }
        BuildArtifactEntity artifact = artifactRepo.findByReleaseId(releaseId).stream()
            .max(Comparator.comparing(BuildArtifactEntity::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElseThrow(() -> new BusinessException(ErrorCodes.CONFLICT, "该发布尚无构建产物，请先完成构建再排期"));
        return new SchedulePlan(target, release, artifact);
    }

    /** 推进部署状态（FR-PUBLISH-005 状态机）。 */
    public DeploymentInfo publish(UUID deploymentId) {
        var d = requireDeployment(deploymentId);
        if (!DeploymentEntity.STATUS_QUEUED.equals(d.getStatus()))
            throw new BusinessException(ErrorCodes.CONFLICT, "只有排队中的部署可以发布");
        if (d.getScheduledAt() != null && d.getScheduledAt().isAfter(Instant.now()))
            throw new BusinessException(ErrorCodes.CONFLICT, "尚未到排期发布时间");
        var target = requireTarget(d.getDeployTargetId());
        var artifact = requireArtifact(d.getArtifactId());
        var before = healthChecker.check(target);
        d.setStartedAt(Instant.now());
        d.setStatus(DeploymentEntity.STATUS_VALIDATING);
        d.setHealthCheckBefore(healthLabel(before));
        deploymentRepo.save(d);
        // FR-PUBLISH-007：发布前健康检查不健康时终止，不进入写盘/切换
        if (before.configured() && !before.healthy()) {
            d.setStatus(DeploymentEntity.STATUS_FAILED);
            d.setFinishedAt(Instant.now());
            d.setNote("发布前健康检查失败，未执行发布: " + before.detail());
            var blocked = deploymentRepo.save(d);
            audit.write(actor(blocked), "DEPLOYMENT_PUBLISH", "deployment", blocked.getId().toString(), blocked.getSiteId(), "FAILED", null, blocked.getNote());
            return DeploymentInfo.from(blocked);
        }
        try {
            d.setStatus(DeploymentEntity.STATUS_DEPLOYING); deploymentRepo.save(d);
            writeArtifact(target, d.getSiteId(), d.getReleaseId(), artifact, d.getId());
            var after = healthChecker.check(target);
            d.setHealthCheckAfter(healthLabel(after));
            if (!after.healthy()) {
                if (d.getPreviousDeploymentId() != null) {
                    var previous = requireDeployment(d.getPreviousDeploymentId());
                    BuildArtifactEntity prevArtifact = previous.getArtifactId() != null
                        ? artifactRepo.findById(previous.getArtifactId()).orElse(null) : null;
                    try {
                        switchToHistoricalRelease(target, d.getSiteId(), previous.getReleaseId(), prevArtifact, d.getId());
                        d.setNote("发布后健康检查失败，已自动回滚: " + after.detail());
                    } catch (RuntimeException rollbackError) {
                        d.setNote("发布后健康检查失败且自动回滚未完成: " + after.detail() + "；回滚原因: " + rollbackError.getMessage());
                    }
                } else {
                    d.setNote("发布后健康检查失败: " + after.detail());
                }
                d.setStatus(DeploymentEntity.STATUS_FAILED);
                d.setFinishedAt(Instant.now());
                var failed = deploymentRepo.save(d);
                audit.write(actor(d), "DEPLOYMENT_PUBLISH", "deployment", d.getId().toString(), d.getSiteId(), "FAILED", null, d.getNote());
                return DeploymentInfo.from(failed);
            }
            d.setStatus(DeploymentEntity.STATUS_DEPLOYED);
            d.setFinishedAt(Instant.now());
            deploymentRepo.save(d);
            markReleaseDeployed(d);
            audit.write(actor(d), "DEPLOYMENT_PUBLISH", "deployment", d.getId().toString(), d.getSiteId(), "SUCCESS", null,
                "release=" + d.getReleaseId() + " target=" + target.getName());
            return DeploymentInfo.from(d);
        } catch (RuntimeException e) {
            var failed = deployFailed(deploymentId, e.getMessage());
            audit.write(actor(failed.deployedBy()), "DEPLOYMENT_PUBLISH", "deployment", deploymentId.toString(), failed.siteId(), "FAILED", null, failed.note());
            return failed;
        }
    }

    public DeploymentInfo advanceStatus(UUID deploymentId, String nextStatus) {
        var d = requireDeployment(deploymentId);
        validateTransition(d.getStatus(), nextStatus);
        d.setStatus(nextStatus);
        if (DeploymentEntity.STATUS_BUILDING.equals(nextStatus) && d.getStartedAt() == null) {
            d.setStartedAt(Instant.now());
        }
        if (isTerminal(nextStatus)) {
            d.setFinishedAt(Instant.now());
        }
        d = deploymentRepo.save(d);
        return DeploymentInfo.from(d);
    }

    /** 标记部署成功并执行健康检查 */
    public DeploymentInfo deploySuccess(UUID deploymentId, boolean preHealthOk, boolean postHealthOk) {
        var d = requireDeployment(deploymentId);
        if (!preHealthOk || !postHealthOk) return deployFailed(deploymentId, "health check failed");
        d.setStatus(DeploymentEntity.STATUS_DEPLOYED);
        d.setFinishedAt(Instant.now());
        d.setHealthCheckBefore(preHealthOk ? DeploymentEntity.HEALTH_HEALTHY : DeploymentEntity.HEALTH_UNHEALTHY);
        d.setHealthCheckAfter(postHealthOk ? DeploymentEntity.HEALTH_HEALTHY : DeploymentEntity.HEALTH_UNHEALTHY);
        d = deploymentRepo.save(d);
        markReleaseDeployed(d);
        return DeploymentInfo.from(d);
    }

    /** 标记部署失败 */
    public DeploymentInfo deployFailed(UUID deploymentId, String note) {
        var d = requireDeployment(deploymentId);
        d.setStatus(DeploymentEntity.STATUS_FAILED);
        d.setFinishedAt(Instant.now());
        if (note != null) d.setNote(note);
        d = deploymentRepo.save(d);
        return DeploymentInfo.from(d);
    }

    /**
     * 回滚到历史成功部署（FR-PUBLISH-006）。
     * 先按目标类型切换（Nginx 切指针 / Git 重放历史产物提交），再记录真实健康检查结果。
     */
    public DeploymentInfo rollback(UUID deploymentId) {
        var current = requireDeployment(deploymentId);
        if (!DeploymentEntity.STATUS_DEPLOYED.equals(current.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "仅已部署的记录可回滚");
        }
        UUID prevId = current.getPreviousDeploymentId();
        if (prevId == null) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "无可回滚的历史部署");
        }
        var prev = requireDeployment(prevId);
        var target = requireTarget(current.getDeployTargetId());
        BuildArtifactEntity prevArtifact = prev.getArtifactId() != null
            ? artifactRepo.findById(prev.getArtifactId()).orElse(null) : null;
        switchToHistoricalRelease(target, current.getSiteId(), prev.getReleaseId(), prevArtifact, current.getId());

        current.setStatus(DeploymentEntity.STATUS_ROLLED_BACK);
        current.setFinishedAt(Instant.now());
        current.setNote("回滚到部署 " + prevId);
        deploymentRepo.save(current);

        // 基于历史部署创建回滚部署记录，并做发布后健康检查
        var rollback = new DeploymentEntity();
        rollback.setSiteId(current.getSiteId());
        rollback.setReleaseId(prev.getReleaseId());
        rollback.setDeployTargetId(current.getDeployTargetId());
        rollback.setArtifactId(prev.getArtifactId());
        rollback.setBuildType(prev.getBuildType());
        rollback.setPreviousDeploymentId(prev.getPreviousDeploymentId());
        rollback.setStartedAt(Instant.now());
        rollback.setFinishedAt(Instant.now());
        rollback.setDeployedBy(current.getDeployedBy());
        rollback.setNote("回滚自 " + deploymentId);
        var after = healthChecker.check(target);
        rollback.setHealthCheckBefore(healthLabel(after));
        rollback.setHealthCheckAfter(healthLabel(after));
        if (after.healthy()) {
            rollback.setStatus(DeploymentEntity.STATUS_DEPLOYED);
            syncReleaseStateAfterRollback(current.getSiteId(), current.getReleaseId(), prev.getReleaseId());
        } else {
            rollback.setStatus(DeploymentEntity.STATUS_FAILED);
            rollback.setNote("回滚后健康检查失败: " + after.detail());
        }
        rollback = deploymentRepo.save(rollback);
        audit.write(actor(current), "DEPLOYMENT_ROLLBACK", "deployment", current.getId().toString(), current.getSiteId(),
            after.healthy() ? "SUCCESS" : "FAILED", null, rollback.getNote());
        return DeploymentInfo.from(rollback);
    }

    public void cancel(UUID deploymentId) {
        var d = requireDeployment(deploymentId);
        if (d.getFinishedAt() != null) {
            throw new BusinessException(ErrorCodes.CONFLICT, "部署已结束");
        }
        d.setStatus(DeploymentEntity.STATUS_CANCELLED);
        d.setFinishedAt(Instant.now());
        deploymentRepo.save(d);
        audit.write(actor(d), "DEPLOYMENT_CANCEL", "deployment", d.getId().toString(), d.getSiteId(), "SUCCESS", null, null);
    }

    // ========== Queries ==========

    public List<DeploymentInfo> listDeployments(UUID siteId) {
        return deploymentRepo.findBySiteIdOrderByCreatedAtDesc(siteId).stream()
            .map(DeploymentInfo::from).toList();
    }

    public List<DeploymentInfo> listDeploymentsByTarget(UUID targetId) {
        return deploymentRepo.findByDeployTargetIdOrderByCreatedAtDesc(targetId).stream()
            .map(DeploymentInfo::from).toList();
    }

    public DeploymentInfo getDeployment(UUID id) { return DeploymentInfo.from(requireDeployment(id)); }

    /** 获取目标最后成功的部署（用于 current 指针） */
    public DeploymentInfo getCurrentDeployment(UUID targetId) {
        return deploymentRepo.findTopByDeployTargetIdAndStatusOrderByCreatedAtDesc(
            targetId, DeploymentEntity.STATUS_DEPLOYED)
            .map(DeploymentInfo::from).orElse(null);
    }

    /** FR-AI-AGENT-004：读取发布目标当前健康检查结果（供发布监控判定使用）。 */
    public DeploymentHealthChecker.Result checkTargetHealth(UUID deployTargetId) {
        return healthChecker.check(requireTarget(deployTargetId));
    }

    /** FR-AI-AGENT-003：列出站点发布目标（供调度发布解析缺省目标）。 */
    public List<TargetInfo> targets(UUID siteId) {
        return listTargets(siteId);
    }

    // ========== helpers ==========

    /** FR-PUBLISH-002/006：按目标类型将产物写入目标（LOCAL_NGINX 原子切指针 / GIT 提交推送）。 */
    private void writeArtifact(DeployTargetEntity target, UUID siteId, UUID releaseId, BuildArtifactEntity artifact, UUID taskId) {
        if (artifact == null || !Files.isDirectory(Paths.get(artifact.getArtifactPath()))) {
            throw new BusinessException(ErrorCodes.CONFLICT, "产物目录不存在: " + (artifact == null ? "null" : artifact.getArtifactPath()));
        }
        if (DeployTargetEntity.TYPE_LOCAL_NGINX.equals(target.getTargetType())) {
            Path published = publisher.publish(target, releaseId, Paths.get(artifact.getArtifactPath()));
            sealStatic(published, siteId);
        } else if (DeployTargetEntity.TYPE_GIT.equals(target.getTargetType())) {
            gitPublisher.publish(target, siteId, releaseId, Paths.get(artifact.getArtifactPath()), taskId);
        } else {
            throw new BusinessException(ErrorCodes.CONFLICT, "不支持的发布目标类型");
        }
    }

    /** FR-PUBLISH-006：回滚按目标类型切换（Nginx 切指针 / Git 重放历史产物，产物缺失则拒绝）。 */
    private void switchToHistoricalRelease(DeployTargetEntity target, UUID siteId, UUID releaseId, BuildArtifactEntity artifact, UUID taskId) {
        if (DeployTargetEntity.TYPE_LOCAL_NGINX.equals(target.getTargetType())) {
            Path rolled = publisher.rollback(target, releaseId);
            sealStatic(rolled, siteId);
        } else if (DeployTargetEntity.TYPE_GIT.equals(target.getTargetType())) {
            if (artifact == null || !Files.isDirectory(Paths.get(artifact.getArtifactPath()))) {
                throw new BusinessException(ErrorCodes.CONFLICT, "历史产物目录缺失，无法回滚: " + (artifact == null ? "null" : artifact.getArtifactPath()));
            }
            gitPublisher.rollback(target, siteId, releaseId, Paths.get(artifact.getArtifactPath()), taskId);
        } else {
            throw new BusinessException(ErrorCodes.CONFLICT, "不支持的发布目标类型");
        }
    }

    private void sealStatic(Path releaseDir, UUID siteId) {
        try {
            staticGuard.seal(releaseDir, siteId);
        } catch (java.io.IOException ex) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "静态发布兜底失败: " + ex.getMessage());
        }
    }

    /** 发布成功后将当前 Release 置为 DEPLOYED，并降级站点此前 DEPLOYED 的 Release（保持单一 DEPLOYED 不变量）。 */
    private void markReleaseDeployed(DeploymentEntity d) {
        releaseRepo.findById(d.getReleaseId()).ifPresent(rel -> {
            releaseRepo.findBySiteIdAndStatus(d.getSiteId(), ReleaseEntity.STATUS_DEPLOYED)
                .filter(old -> !old.getId().equals(rel.getId()))
                .ifPresent(old -> { old.setStatus(ReleaseEntity.STATUS_ROLLED_BACK); releaseRepo.save(old); });
            rel.setStatus(ReleaseEntity.STATUS_DEPLOYED);
            releaseRepo.save(rel);
            events.publishEvent(new SiteCacheWarmupRequested(d.getSiteId(), "DEPLOY"));
        });
    }

    /** 回滚成功后：当前 Release -> ROLLED_BACK，历史 Release -> DEPLOYED（FR-PUBLISH-006 指向同一历史 Release）。 */
    private void syncReleaseStateAfterRollback(UUID siteId, UUID currentReleaseId, UUID previousReleaseId) {
        releaseRepo.findById(currentReleaseId).ifPresent(r -> {
            if (ReleaseEntity.STATUS_DEPLOYED.equals(r.getStatus())) {
                r.setStatus(ReleaseEntity.STATUS_ROLLED_BACK);
                releaseRepo.save(r);
            }
        });
        releaseRepo.findById(previousReleaseId).ifPresent(r -> {
            r.setStatus(ReleaseEntity.STATUS_DEPLOYED);
            releaseRepo.save(r);
        });
    }

    private static String actor(DeploymentEntity d) {
        return actor(d.getDeployedBy());
    }

    private static String actor(String deployedBy) {
        return (deployedBy == null || deployedBy.isBlank()) ? "system" : deployedBy;
    }

    private static String healthLabel(DeploymentHealthChecker.Result r) {
        return r.configured()
            ? (r.healthy() ? DeploymentEntity.HEALTH_HEALTHY : DeploymentEntity.HEALTH_UNHEALTHY)
            : DeploymentEntity.HEALTH_PENDING;
    }

    private static final int[][] VALID_TRANSITIONS = {
        {s(DeploymentEntity.STATUS_QUEUED), s(DeploymentEntity.STATUS_BUILDING)},
        {s(DeploymentEntity.STATUS_BUILDING), s(DeploymentEntity.STATUS_VALIDATING)},
        {s(DeploymentEntity.STATUS_BUILDING), s(DeploymentEntity.STATUS_FAILED)},
        {s(DeploymentEntity.STATUS_VALIDATING), s(DeploymentEntity.STATUS_DEPLOYING)},
        {s(DeploymentEntity.STATUS_VALIDATING), s(DeploymentEntity.STATUS_FAILED)},
        {s(DeploymentEntity.STATUS_DEPLOYING), s(DeploymentEntity.STATUS_INDEXING)},
        {s(DeploymentEntity.STATUS_DEPLOYING), s(DeploymentEntity.STATUS_FAILED)},
        {s(DeploymentEntity.STATUS_INDEXING), s(DeploymentEntity.STATUS_DEPLOYED)},
        {s(DeploymentEntity.STATUS_INDEXING), s(DeploymentEntity.STATUS_FAILED)},
    };

    private static int s(String status) {
        return switch (status) {
            case DeploymentEntity.STATUS_QUEUED -> 0;
            case DeploymentEntity.STATUS_BUILDING -> 1;
            case DeploymentEntity.STATUS_VALIDATING -> 2;
            case DeploymentEntity.STATUS_DEPLOYING -> 3;
            case DeploymentEntity.STATUS_INDEXING -> 4;
            case DeploymentEntity.STATUS_DEPLOYED -> 5;
            case DeploymentEntity.STATUS_FAILED -> 6;
            case DeploymentEntity.STATUS_ROLLED_BACK -> 7;
            case DeploymentEntity.STATUS_CANCELLED -> 8;
            default -> -1;
        };
    }

    private void validateTransition(String from, String to) {
        int f = s(from), t = s(to);
        for (int[] edge : VALID_TRANSITIONS) {
            if (edge[0] == f && edge[1] == t) return;
        }
        if (isTerminal(from)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "终态不可转移：" + from + " -> " + to);
        }
        throw new BusinessException(ErrorCodes.CONFLICT, "非法状态转移：" + from + " -> " + to);
    }

    private boolean isTerminal(String s) {
        return DeploymentEntity.STATUS_DEPLOYED.equals(s)
            || DeploymentEntity.STATUS_FAILED.equals(s)
            || DeploymentEntity.STATUS_ROLLED_BACK.equals(s)
            || DeploymentEntity.STATUS_CANCELLED.equals(s);
    }

    DeployTargetEntity requireTarget(UUID id) {
        return targetRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "发布目标不存在"));
    }

    BuildArtifactEntity requireArtifact(UUID id) {
        return artifactRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "产物不存在"));
    }

    DeploymentEntity requireDeployment(UUID id) {
        return deploymentRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "部署不存在"));
    }
}
