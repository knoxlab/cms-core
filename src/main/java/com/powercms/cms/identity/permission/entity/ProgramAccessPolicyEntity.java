package com.powercms.cms.identity.permission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 条件访问策略（FR-ACCESS-003/004/005）。
 * 各维度列 NULL 表示沿对象链继承；显式空列表表示该维度不限制。
 */
@Entity
@Table(name = "program_access_policy",
    uniqueConstraints = @UniqueConstraint(name = "uq_access_policy", columnNames = {"object_type", "object_id"}))
public class ProgramAccessPolicyEntity {

    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "object_type", nullable = false, length = 30)
    private String objectType;

    @Column(name = "object_id", nullable = false)
    private UUID objectId;

    @Column(name = "ip_allowlist")
    private String ipAllowlist;

    @Column(name = "geo_allowlist")
    private String geoAllowlist;

    @Column(name = "time_windows")
    private String timeWindows;

    @Column(name = "download_enabled")
    private Boolean downloadEnabled;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ProgramAccessPolicyEntity create(UUID siteId, String objectType, UUID objectId, UUID actor) {
        ProgramAccessPolicyEntity row = new ProgramAccessPolicyEntity();
        row.id = UUID.randomUUID();
        row.siteId = siteId;
        row.objectType = objectType;
        row.objectId = objectId;
        row.createdBy = actor;
        Instant now = Instant.now();
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public String getObjectType() { return objectType; }
    public UUID getObjectId() { return objectId; }
    public String getIpAllowlist() { return ipAllowlist; }
    public void setIpAllowlist(String ipAllowlist) { this.ipAllowlist = ipAllowlist; }
    public String getGeoAllowlist() { return geoAllowlist; }
    public void setGeoAllowlist(String geoAllowlist) { this.geoAllowlist = geoAllowlist; }
    public String getTimeWindows() { return timeWindows; }
    public void setTimeWindows(String timeWindows) { this.timeWindows = timeWindows; }
    public Boolean getDownloadEnabled() { return downloadEnabled; }
    public void setDownloadEnabled(Boolean downloadEnabled) { this.downloadEnabled = downloadEnabled; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
