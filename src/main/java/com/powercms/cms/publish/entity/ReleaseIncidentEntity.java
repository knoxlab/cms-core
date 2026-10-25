package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 发布异常事件（FR-AI-AGENT-004）：监控命中的失败/劣化记录，以及回滚演练。
 * 同时作为「自动回滚是否由监控触发」的审计载体。
 */
@Entity
@Table(name = "release_incident")
public class ReleaseIncidentEntity {

    public static final String TYPE_DEPLOY_FAILED = "DEPLOY_FAILED";
    public static final String TYPE_HEALTH_DEGRADED = "HEALTH_DEGRADED";
    public static final String TYPE_ROLLBACK_DRILL = "ROLLBACK_DRILL";

    public static final String STATUS_NOTIFIED = "NOTIFIED";
    public static final String STATUS_AUTO_ROLLED_BACK = "AUTO_ROLLED_BACK";
    public static final String STATUS_ROLLBACK_FAILED = "ROLLBACK_FAILED";
    public static final String STATUS_SIMULATED = "SIMULATED";
    /** 持续观测中（G9-④）：健康劣化已达观测起点、持续时长未达阈值，暂不动作。 */
    public static final String STATUS_DEGRADED_PENDING = "DEGRADED_PENDING";
    /** 观测期内恢复（G9-④）：持续时长判定关单，不动作。 */
    public static final String STATUS_RECOVERED = "RECOVERED";

    public static final String ACTION_NOTIFY = "NOTIFY";
    public static final String ACTION_AUTO_ROLLBACK = "AUTO_ROLLBACK";
    public static final String ACTION_DRILL = "DRILL";
    /** 持续观测（G9-④）：留痕但不动作。 */
    public static final String ACTION_OBSERVE = "OBSERVE";

    @Id
    private UUID id;
    @Column(name = "site_id", nullable = false)
    private UUID siteId;
    @Column(name = "incident_type", nullable = false, length = 40)
    private String incidentType;
    @Column(name = "deployment_id")
    private UUID deploymentId;
    @Column(name = "release_id")
    private UUID releaseId;
    @Column(nullable = false, length = 30)
    private String status;
    @Column(nullable = false, length = 30)
    private String action;
    @Column(columnDefinition = "text")
    private String detail;
    @Column(name = "rolled_back_deployment_id")
    private UUID rolledBackDeploymentId;
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (detectedAt == null) detectedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getIncidentType() { return incidentType; }
    public void setIncidentType(String incidentType) { this.incidentType = incidentType; }
    public UUID getDeploymentId() { return deploymentId; }
    public void setDeploymentId(UUID deploymentId) { this.deploymentId = deploymentId; }
    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID releaseId) { this.releaseId = releaseId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public UUID getRolledBackDeploymentId() { return rolledBackDeploymentId; }
    public void setRolledBackDeploymentId(UUID v) { this.rolledBackDeploymentId = v; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
