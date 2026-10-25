package com.powercms.cms.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "content_item")
public class ContentItemEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_IN_REVIEW = "IN_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id private UUID id;
    @Column(name = "site_id", nullable = false)
    private UUID siteId;
    @Column(name = "category_id")
    private UUID categoryId;
    @Column(name = "folder_id")
    private UUID folderId;
    @Column(name = "content_type_id", nullable = false)
    private UUID contentTypeId;
    @Column(name = "route_path", length = 1000)
    private String routePath;
    @Column(name = "template_path", length = 500)
    private String templatePath;
    @Column(nullable = false, length = 500)
    private String title;
    @Column(nullable = false, length = 20)
    private String status = STATUS_DRAFT;
    @Column(name = "current_version", nullable = false)
    private int currentVersion;
    @Column(name = "published_version")
    private Integer publishedVersion;
    @Column(name = "created_by", length = 100)
    private String createdBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "view_count", nullable = false)
    private long viewCount;
    @Column(name = "in_workflow", nullable = false)
    private boolean inWorkflow;
    @Column(name = "last_rejection_reason", length = 2000)
    private String lastRejectionReason;
    @Column(name = "scheduled_publish_at")
    private Instant scheduledPublishAt;
    @Column(name = "scheduled_withdraw_at")
    private Instant scheduledWithdrawAt;
    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;
    @Column(name = "ai_model", length = 200)
    private String aiModel;
    @Column(name = "ai_prompt_version")
    private Integer aiPromptVersion;
    @Column(name = "ai_generated_at")
    private Instant aiGeneratedAt;

    /**
     * FR-AI-SRC-003 来源域标记：CMS（默认/人工创建）/ DAM:&lt;端点名&gt;（Knox 媒体同步）/
     * PIM:&lt;数据源名&gt;（SRC-001 同步）。联合检索命中带此标注，跨源导航按此分组。
     */
    @Column(name = "source_domain", nullable = false, length = 100)
    private String sourceDomain = "CMS";

    /** FR-AI-SRC-004 可信等级：0=BLOCKED（AI 引用排除）/ 1=LOW / 2=NORMAL（默认）/ 3=VERIFIED。 */
    public static final int TRUST_BLOCKED = 0;
    public static final int TRUST_LOW = 1;
    public static final int TRUST_NORMAL = 2;
    public static final int TRUST_VERIFIED = 3;

    @Column(name = "trust_level", nullable = false)
    private int trustLevel = TRUST_NORMAL;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); Instant n = Instant.now(); createdAt = n; updatedAt = n; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getCategoryId() { return categoryId; } public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public UUID getFolderId() { return folderId; } public void setFolderId(UUID folderId) { this.folderId = folderId; }
    public UUID getContentTypeId() { return contentTypeId; } public void setContentTypeId(UUID contentTypeId) { this.contentTypeId = contentTypeId; }
    public String getRoutePath() { return routePath; } public void setRoutePath(String routePath) { this.routePath = routePath; }
    public String getTemplatePath() { return templatePath; } public void setTemplatePath(String templatePath) { this.templatePath = templatePath; }
    public String getTitle() { return title; } public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public int getCurrentVersion() { return currentVersion; } public void setCurrentVersion(int currentVersion) { this.currentVersion = currentVersion; }
    public Integer getPublishedVersion() { return publishedVersion; } public void setPublishedVersion(Integer publishedVersion) { this.publishedVersion = publishedVersion; }
    public String getCreatedBy() { return createdBy; } public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getViewCount() { return viewCount; }
    public void setViewCount(long viewCount) { this.viewCount = viewCount; }
    public boolean isInWorkflow() { return inWorkflow; }
    public void setInWorkflow(boolean inWorkflow) { this.inWorkflow = inWorkflow; }
    public String getLastRejectionReason() { return lastRejectionReason; }
    public void setLastRejectionReason(String lastRejectionReason) { this.lastRejectionReason = lastRejectionReason; }
    public Instant getScheduledPublishAt() { return scheduledPublishAt; }
    public void setScheduledPublishAt(Instant scheduledPublishAt) { this.scheduledPublishAt = scheduledPublishAt; }
    public Instant getScheduledWithdrawAt() { return scheduledWithdrawAt; }
    public void setScheduledWithdrawAt(Instant scheduledWithdrawAt) { this.scheduledWithdrawAt = scheduledWithdrawAt; }
    public boolean isAiGenerated() { return aiGenerated; }
    public void setAiGenerated(boolean aiGenerated) { this.aiGenerated = aiGenerated; }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public Integer getAiPromptVersion() { return aiPromptVersion; }
    public void setAiPromptVersion(Integer aiPromptVersion) { this.aiPromptVersion = aiPromptVersion; }
    public Instant getAiGeneratedAt() { return aiGeneratedAt; }
    public void setAiGeneratedAt(Instant aiGeneratedAt) { this.aiGeneratedAt = aiGeneratedAt; }
    public String getSourceDomain() { return sourceDomain; }
    public void setSourceDomain(String sourceDomain) {
        this.sourceDomain = sourceDomain == null || sourceDomain.isBlank() ? "CMS" : sourceDomain;
    }
    public int getTrustLevel() { return trustLevel; }
    public void setTrustLevel(int trustLevel) {
        this.trustLevel = Math.max(TRUST_BLOCKED, Math.min(TRUST_VERIFIED, trustLevel));
    }
}
