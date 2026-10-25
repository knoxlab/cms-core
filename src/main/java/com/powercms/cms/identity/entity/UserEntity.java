package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户（FR-IAM-001～003）：密码只存自适应散列，禁止明文。
 * 状态：ACTIVE / DISABLED / LOCKED；连续失败达到策略阈值后锁定（FR-IAM-002）。
 */
@Entity
@Table(name = "sys_user")
public class UserEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_LOCKED = "LOCKED";
    /** 自助注册待审核。通过前不能登录。 */
    public static final String STATUS_PENDING = "PENDING";

    public static final String KIND_BACKEND = "BACKEND";
    public static final String KIND_FRONTEND = "FRONTEND";

    public static final String LOGIN_MODE_BACKEND = "BACKEND";
    public static final String LOGIN_MODE_FRONTEND = "FRONTEND";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(length = 320)
    private String email;

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    /** BACKEND（员工）或 FRONTEND（会员）；创建后只能通过转换 API 更改（spec 5.1）。 */
    @Column(nullable = false, length = 10)
    private String kind = KIND_BACKEND;

    /** 前台会员必填；后台用户可空（spec 5.1）。 */
    @Column(name = "home_site_id")
    private UUID homeSiteId;

    /** 指向资料内容实例：后台 → system.user-profile，前台 → system.frontend-user（spec 5.1）。 */
    @Column(name = "profile_content_id")
    private UUID profileContentId;

    /** 最近一次登录通道：BACKEND / FRONTEND（spec 8.3）。 */
    @Column(name = "last_login_mode", length = 10)
    private String lastLoginMode;

    /** 界面语言偏好（FR-I18N-004）：zh-CN/en-US/ja-JP/ko-KR/fr-FR/es-ES；NULL = 跟随组织默认。 */
    @Column(name = "language", length = 20)
    private String language;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "externally_managed", nullable = false)
    private boolean externallyManaged;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_login_ip", length = 64)
    private String lastLoginIp;

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
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind == null ? KIND_BACKEND : kind; }
    public UUID getHomeSiteId() { return homeSiteId; }
    public void setHomeSiteId(UUID homeSiteId) { this.homeSiteId = homeSiteId; }
    public UUID getProfileContentId() { return profileContentId; }
    public void setProfileContentId(UUID profileContentId) { this.profileContentId = profileContentId; }
    public String getLastLoginMode() { return lastLoginMode; }
    public void setLastLoginMode(String lastLoginMode) { this.lastLoginMode = lastLoginMode; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public int getFailedLogins() { return failedLogins; }
    public void setFailedLogins(int failedLogins) { this.failedLogins = failedLogins; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(Instant passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }
    public boolean isExternallyManaged() { return externallyManaged; }
    public void setExternallyManaged(boolean externallyManaged) { this.externallyManaged = externallyManaged; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public String getLastLoginIp() { return lastLoginIp; }
    public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
}
