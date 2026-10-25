package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment")
public class DeploymentEntity {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_VALIDATING = "VALIDATING";
    public static final String STATUS_DEPLOYING = "DEPLOYING";
    public static final String STATUS_INDEXING = "INDEXING";
    public static final String STATUS_DEPLOYED = "DEPLOYED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String HEALTH_HEALTHY = "HEALTHY";
    public static final String HEALTH_UNHEALTHY = "UNHEALTHY";
    public static final String HEALTH_PENDING = "PENDING";

    @Id private UUID id;
    @Column(name = "site_id", nullable = false) private UUID siteId;
    @Column(name = "release_id", nullable = false) private UUID releaseId;
    @Column(name = "deploy_target_id", nullable = false) private UUID deployTargetId;
    @Column(name = "artifact_id") private UUID artifactId;
    @Column(name = "build_type", nullable = false, length = 20) private String buildType = "FULL";
    @Column(nullable = false, length = 20) private String status = STATUS_QUEUED;
    @Column(name = "previous_deployment_id") private UUID previousDeploymentId;
    @Column(name = "health_check_before", length = 20) private String healthCheckBefore;
    @Column(name = "health_check_after", length = 20) private String healthCheckAfter;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "scheduled_at") private Instant scheduledAt;
    @Column(name = "deployed_by", length = 100) private String deployedBy;
    @Column(length = 1000) private String note;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID v) { this.siteId = v; }
    public UUID getReleaseId() { return releaseId; } public void setReleaseId(UUID v) { this.releaseId = v; }
    public UUID getDeployTargetId() { return deployTargetId; } public void setDeployTargetId(UUID v) { this.deployTargetId = v; }
    public UUID getArtifactId() { return artifactId; } public void setArtifactId(UUID v) { this.artifactId = v; }
    public String getBuildType() { return buildType; } public void setBuildType(String v) { this.buildType = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public UUID getPreviousDeploymentId() { return previousDeploymentId; } public void setPreviousDeploymentId(UUID v) { this.previousDeploymentId = v; }
    public String getHealthCheckBefore() { return healthCheckBefore; } public void setHealthCheckBefore(String v) { this.healthCheckBefore = v; }
    public String getHealthCheckAfter() { return healthCheckAfter; } public void setHealthCheckAfter(String v) { this.healthCheckAfter = v; }
    public Instant getStartedAt() { return startedAt; } public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; } public void setFinishedAt(Instant v) { this.finishedAt = v; }
    public Instant getScheduledAt() { return scheduledAt; } public void setScheduledAt(Instant v) { this.scheduledAt = v; }
    public String getDeployedBy() { return deployedBy; } public void setDeployedBy(String v) { this.deployedBy = v; }
    public String getNote() { return note; } public void setNote(String v) { this.note = v; }
    public Instant getCreatedAt() { return createdAt; }
    /** 测试装配用；生产 createdAt 由 {@link PrePersist} 生成。 */
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
