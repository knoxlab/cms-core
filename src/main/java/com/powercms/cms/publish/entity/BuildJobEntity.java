package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "build_job")
public class BuildJobEntity {
    public static final String JOB_PREVIEW = "PREVIEW";
    public static final String JOB_RELEASE = "RELEASE";
    public static final String JOB_RELEASE_INCREMENTAL = "RELEASE_INCREMENTAL";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id private UUID id;
    @Column(name = "template_project_id", nullable = false) private UUID templateProjectId;
    @Column(nullable = false) private int revision;
    @Column(name = "release_id") private UUID releaseId;
    @Column(name = "job_type", nullable = false, length = 20) private String jobType = JOB_PREVIEW;
    @Column(nullable = false, length = 20) private String status = STATUS_QUEUED;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "artifact_url", length = 1000) private String artifactUrl;
    @Column(name = "created_by", length = 100) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getTemplateProjectId() { return templateProjectId; } public void setTemplateProjectId(UUID v) { this.templateProjectId = v; }
    public int getRevision() { return revision; } public void setRevision(int v) { this.revision = v; }
    public UUID getReleaseId() { return releaseId; } public void setReleaseId(UUID v) { this.releaseId = v; }
    public String getJobType() { return jobType; } public void setJobType(String v) { this.jobType = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public Instant getStartedAt() { return startedAt; } public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; } public void setFinishedAt(Instant v) { this.finishedAt = v; }
    public String getArtifactUrl() { return artifactUrl; } public void setArtifactUrl(String v) { this.artifactUrl = v; }
    public String getCreatedBy() { return createdBy; } public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { this.createdAt = v; }
}
