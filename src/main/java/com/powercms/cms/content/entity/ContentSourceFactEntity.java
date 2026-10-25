package com.powercms.cms.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-AI-SRC-004 统一来源事实元数据（外部来源同步投影 1:1）。
 *
 * <p>承载来源侧元数据：来源系统、外部对象 ID、来源版本、来源审批状态、有效期、可信等级、
 * 版权、同步游标。CMS 原生内容无本行；引用闸门仅在存在本行时追加来源侧判定。
 */
@Entity
@Table(name = "content_source_fact")
public class ContentSourceFactEntity {

    /** 来源审批状态：可引用 / 待审 / 已拒。PENDING、REJECTED 不进入 AI 引用。 */
    public static final String APPROVAL_APPROVED = "APPROVED";
    public static final String APPROVAL_PENDING = "PENDING";
    public static final String APPROVAL_REJECTED = "REJECTED";

    @Id
    @Column(name = "content_item_id")
    private UUID contentItemId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    /** 来源系统：CMS / DAM:&lt;端点&gt; / PIM:&lt;数据源&gt;。 */
    @Column(name = "source_system", nullable = false, length = 100)
    private String sourceSystem;

    /** 来源侧对象主键（如 SRC-001 的 externalKey）。 */
    @Column(name = "source_object_id", length = 500)
    private String sourceObjectId;

    /** 来源侧版本号（外部系统提供时记录）。 */
    @Column(name = "source_version", length = 100)
    private String sourceVersion;

    @Column(name = "approval_status", nullable = false, length = 20)
    private String approvalStatus = APPROVAL_APPROVED;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "trust_level", nullable = false)
    private int trustLevel = 2;

    @Column(length = 1000)
    private String copyright;

    @Column(name = "sync_cursor", length = 500)
    private String syncCursor;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getContentItemId() { return contentItemId; }
    public void setContentItemId(UUID contentItemId) { this.contentItemId = contentItemId; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceObjectId() { return sourceObjectId; }
    public void setSourceObjectId(String sourceObjectId) { this.sourceObjectId = sourceObjectId; }
    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) {
        this.approvalStatus = approvalStatus == null || approvalStatus.isBlank()
            ? APPROVAL_APPROVED : approvalStatus;
    }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public int getTrustLevel() { return trustLevel; }
    public void setTrustLevel(int trustLevel) {
        this.trustLevel = Math.max(0, Math.min(3, trustLevel));
    }
    public String getCopyright() { return copyright; }
    public void setCopyright(String copyright) { this.copyright = copyright; }
    public String getSyncCursor() { return syncCursor; }
    public void setSyncCursor(String syncCursor) { this.syncCursor = syncCursor; }
    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
