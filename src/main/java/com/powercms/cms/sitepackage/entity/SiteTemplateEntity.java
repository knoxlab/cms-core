package com.powercms.cms.sitepackage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_template", uniqueConstraints = @UniqueConstraint(columnNames = {"site_id", "name"}))
public class SiteTemplateEntity {
    @Id private UUID id;
    @Column(name = "site_id", nullable = false) private UUID siteId;
    @Column(name = "theme_id") private UUID themeId;
    @Column(nullable = false, length = 200) private String name;
    @Column(length = 1000) private String description;
    @Column(name = "layout_json", nullable = false, columnDefinition = "text") private String layoutJson = "{}";
    @Column(nullable = false, length = 20) private String status = "DRAFT";
    @Column(name = "current_version", nullable = false) private int currentVersion;
    @Version @Column(name = "lock_version", nullable = false) private long lockVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void create() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate void update() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID v) { id = v; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID v) { siteId = v; }
    public UUID getThemeId() { return themeId; }
    public void setThemeId(UUID v) { themeId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getLayoutJson() { return layoutJson; }
    public void setLayoutJson(String v) { layoutJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public int getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(int v) { currentVersion = v; }
    public long getLockVersion() { return lockVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
