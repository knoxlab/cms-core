package com.powercms.cms.search.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 字段级索引映射的已应用基线（FR-MODEL-006 / AC-CM-009）。
 *
 * <p>存「最近一次成功重建时的 schema 指纹」，用于把**模型属性变更**翻译为
 * 「该索引需重建」的可比对状态 —— OpenSearch mapping 近乎不可变，属性改了不重建就
 * 只能表现为「排序/过滤请求 400」，运维无从知道该做什么。
 */
@Entity
@Table(name = "search_index_mapping_state")
public class SearchIndexMappingStateEntity {

    public static final String TARGET_ADMIN = "ADMIN";
    public static final String TARGET_PUBLIC = "PUBLIC";

    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(nullable = false, length = 16)
    private String target;

    @Column(name = "applied_fingerprint", length = 64)
    private String appliedFingerprint;

    @Column(name = "desired_fingerprint", length = 64)
    private String desiredFingerprint;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getAppliedFingerprint() { return appliedFingerprint; }
    public void setAppliedFingerprint(String appliedFingerprint) { this.appliedFingerprint = appliedFingerprint; }
    public String getDesiredFingerprint() { return desiredFingerprint; }
    public void setDesiredFingerprint(String desiredFingerprint) { this.desiredFingerprint = desiredFingerprint; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
