package com.powercms.cms.site.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 站点（FR-SITE-001）：全局唯一 siteKey。
 * 状态：ACTIVE / FROZEN / ARCHIVED（FR-SITE-003）。
 */
@Entity
@Table(name = "site")
public class SiteEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "site_key", nullable = false, unique = true, length = 100)
    private String siteKey;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "default_locale", nullable = false, length = 20)
    private String defaultLocale = "zh-CN";

    @Column(nullable = false, length = 60)
    private String timezone = "Asia/Shanghai";

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    /** 打开后：未登录访问会员档 302，已登录无权 403。默认关闭，一律 404。 */
    @Column(name = "access_guidance_enabled", nullable = false)
    private boolean accessGuidanceEnabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getSiteKey() { return siteKey; }
    public void setSiteKey(String siteKey) { this.siteKey = siteKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDefaultLocale() { return defaultLocale; }
    public void setDefaultLocale(String defaultLocale) { this.defaultLocale = defaultLocale; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isAccessGuidanceEnabled() { return accessGuidanceEnabled; }
    public void setAccessGuidanceEnabled(boolean accessGuidanceEnabled) { this.accessGuidanceEnabled = accessGuidanceEnabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
