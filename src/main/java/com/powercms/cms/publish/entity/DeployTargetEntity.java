package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deploy_target", uniqueConstraints = @UniqueConstraint(columnNames = {"site_id", "name"}))
public class DeployTargetEntity {
    public static final String TYPE_LOCAL_NGINX = "LOCAL_NGINX";
    public static final String TYPE_GIT = "GIT";

    @Id private UUID id;
    @Column(name = "site_id", nullable = false) private UUID siteId;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "target_type", nullable = false, length = 20) private String targetType = TYPE_LOCAL_NGINX;
    @Column(nullable = false, columnDefinition = "text") private String config;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); Instant n = Instant.now(); createdAt = n; updatedAt = n; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID v) { this.siteId = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public String getTargetType() { return targetType; } public void setTargetType(String v) { this.targetType = v; }
    public String getConfig() { return config; } public void setConfig(String v) { this.config = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
