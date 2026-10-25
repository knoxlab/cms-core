package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "build_artifact")
public class BuildArtifactEntity {
    public static final String BUILD_FULL = "FULL";
    public static final String BUILD_INCREMENTAL = "INCREMENTAL";

    @Id private UUID id;
    @Column(name = "build_job_id") private UUID buildJobId;
    @Column(name = "release_id", nullable = false) private UUID releaseId;
    @Column(name = "artifact_path", nullable = false, length = 500) private String artifactPath;
    @Column(name = "file_count", nullable = false) private int fileCount;
    @Column(name = "size_bytes", nullable = false) private long sizeBytes;
    @Column(name = "route_manifest", columnDefinition = "text") private String routeManifest;
    @Column(name = "file_manifest", columnDefinition = "text") private String fileManifest;
    @Column(name = "input_digest", length = 128) private String inputDigest;
    @Column(name = "build_type", nullable = false, length = 20) private String buildType = BUILD_FULL;
    @Column(name = "base_artifact_id") private UUID baseArtifactId;
    @Column(name = "artifact_digest", length = 128) private String artifactDigest;
    @Column(name = "changed_file_manifest", columnDefinition = "text") private String changedFileManifest;
    @Column(name = "deleted_file_manifest", columnDefinition = "text") private String deletedFileManifest;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getBuildJobId() { return buildJobId; } public void setBuildJobId(UUID v) { this.buildJobId = v; }
    public UUID getReleaseId() { return releaseId; } public void setReleaseId(UUID v) { this.releaseId = v; }
    public String getArtifactPath() { return artifactPath; } public void setArtifactPath(String v) { this.artifactPath = v; }
    public int getFileCount() { return fileCount; } public void setFileCount(int v) { this.fileCount = v; }
    public long getSizeBytes() { return sizeBytes; } public void setSizeBytes(long v) { this.sizeBytes = v; }
    public String getRouteManifest() { return routeManifest; } public void setRouteManifest(String v) { this.routeManifest = v; }
    public String getFileManifest() { return fileManifest; } public void setFileManifest(String v) { this.fileManifest = v; }
    public String getInputDigest() { return inputDigest; } public void setInputDigest(String v) { this.inputDigest = v; }
    public String getBuildType() { return buildType; } public void setBuildType(String v) { this.buildType = v; }
    public UUID getBaseArtifactId() { return baseArtifactId; } public void setBaseArtifactId(UUID v) { this.baseArtifactId = v; }
    public String getArtifactDigest() { return artifactDigest; } public void setArtifactDigest(String v) { this.artifactDigest = v; }
    public String getChangedFileManifest() { return changedFileManifest; } public void setChangedFileManifest(String v) { this.changedFileManifest = v; }
    public String getDeletedFileManifest() { return deletedFileManifest; } public void setDeletedFileManifest(String v) { this.deletedFileManifest = v; }
    public Instant getCreatedAt() { return createdAt; }
}
