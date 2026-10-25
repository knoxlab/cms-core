package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "release")
public class ReleaseEntity {
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_DEPLOYED = "DEPLOYED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    @Id private UUID id;
    @Column(name = "site_id", nullable = false)
    private UUID siteId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 1000)
    private String description;
    @Column(nullable = false, length = 20)
    private String status = STATUS_DRAFT;
    @Column(name = "template_project_id") private UUID templateProjectId;
    @Column(name = "template_revision") private Integer templateRevision;
    @Column(name = "site_snapshot", columnDefinition = "text") private String siteSnapshot;
    @Column(name = "route_manifest", columnDefinition = "text") private String routeManifest;
    @Column(name = "input_digest", length = 128) private String inputDigest;
    @Column(name = "global_input_digest", length = 128) private String globalInputDigest;
    @Column(name = "frozen_at")
    private Instant frozenAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); Instant n = Instant.now(); createdAt = n; updatedAt = n; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getDescription() { return description; } public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public UUID getTemplateProjectId() { return templateProjectId; } public void setTemplateProjectId(UUID v) { templateProjectId = v; }
    public Integer getTemplateRevision() { return templateRevision; } public void setTemplateRevision(Integer v) { templateRevision = v; }
    public String getSiteSnapshot() { return siteSnapshot; } public void setSiteSnapshot(String v) { siteSnapshot = v; }
    public String getRouteManifest() { return routeManifest; } public void setRouteManifest(String v) { routeManifest = v; }
    public String getInputDigest() { return inputDigest; } public void setInputDigest(String v) { inputDigest = v; }
    public String getGlobalInputDigest() { return globalInputDigest; } public void setGlobalInputDigest(String v) { globalInputDigest = v; }
    public Instant getFrozenAt() { return frozenAt; } public void setFrozenAt(Instant v) { this.frozenAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
