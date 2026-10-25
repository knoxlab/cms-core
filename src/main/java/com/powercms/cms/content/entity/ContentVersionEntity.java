package com.powercms.cms.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "content_version", uniqueConstraints = @UniqueConstraint(columnNames = {"content_item_id", "version"}))
public class ContentVersionEntity {

    @Id private UUID id;
    @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;
    @Column(nullable = false)
    private int version;
    @Column(name = "field_values", nullable = false, columnDefinition = "text")
    private String fieldValues;
    /** FR-CV-VER-001/003：条目级系统字段快照（早期行由 V100 迁移按内容当前值回填）。 */
    @Column(length = 500)
    private String title;
    @Column(name = "route_path", length = 1000)
    private String routePath;
    @Column(name = "template_path", length = 500)
    private String templatePath;
    @Column(name = "model_version", nullable = false)
    private int modelVersion;
    @Column(length = 1000)
    private String note;
    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;
    @Column(name = "ai_model", length = 200)
    private String aiModel;
    @Column(name = "ai_prompt_version")
    private Integer aiPromptVersion;
    @Column(name = "ai_generated_at")
    private Instant aiGeneratedAt;
    /**
     * OBS-11-10：本版本由「恢复到 v{n}」产生时的来源版本号；非恢复产生的版本为 {@code null}。
     *
     * <p>恢复版本的内容等同于来源版本快照，仅靠备注文案无法机器判定来源，故显式落列，
     * 供版本历史标注来源、并把默认对比区间收敛为「来源版本 → 新版本」。</p>
     */
    @Column(name = "reverted_from")
    private Integer revertedFrom;
    /**
     * DEF-11-02：本版本由「发布」动作生成（版本历史中的发布节点）。
     *
     * <p>发布版本的内容快照等于发布时刻条目的最新快照，用于回答「线上跑的是哪一版、那一版发布了
     * 什么」；普通保存 / AI 采纳 / 恢复产生的版本为 {@code false}。</p>
     */
    @Column(nullable = false)
    private boolean published;

    public static ContentVersionEntity create(UUID contentItemId, int version, String fieldValues,
                                               int modelVersion, String createdBy, String note) {
        return create(contentItemId, version, fieldValues, modelVersion, createdBy, note,
            null, null);
    }

    /** GOV-003：创建 AI 参与的版本（版本链保留 AI 身份，回滚不丢失）。 */
    public static ContentVersionEntity create(UUID contentItemId, int version, String fieldValues,
                                               int modelVersion, String createdBy, String note,
                                               String aiModel, Integer aiPromptVersion) {
        ContentVersionEntity v = new ContentVersionEntity();
        v.id = UUID.randomUUID();
        v.contentItemId = contentItemId;
        v.version = version;
        v.fieldValues = fieldValues;
        v.modelVersion = modelVersion;
        v.createdBy = createdBy;
        v.note = note;
        v.createdAt = Instant.now();
        v.aiGenerated = aiModel != null || aiPromptVersion != null;
        v.aiModel = aiModel;
        v.aiPromptVersion = aiPromptVersion;
        if (v.aiGenerated) v.aiGeneratedAt = Instant.now();
        return v;
    }

    /** FR-CV-VER-001/003：写入条目级系统字段快照（标题 / 页面路径 / 页面模板）。 */
    public ContentVersionEntity withItemSnapshot(String title, String routePath, String templatePath) {
        this.title = title;
        this.routePath = routePath;
        this.templatePath = templatePath;
        return this;
    }

    /** OBS-11-10：标记本版本由「恢复到 v{sourceVersion}」产生（仅恢复路径调用）。 */
    public ContentVersionEntity withRevertedFrom(Integer sourceVersion) {
        this.revertedFrom = sourceVersion;
        return this;
    }

    /** DEF-11-02：标记本版本由「发布」动作生成（仅发布路径调用）。 */
    public ContentVersionEntity withPublished(boolean published) {
        this.published = published;
        return this;
    }

    public UUID getId() { return id; }
    public UUID getContentItemId() { return contentItemId; }
    public int getVersion() { return version; }
    public String getFieldValues() { return fieldValues; }
    public void setFieldValues(String fieldValues) { this.fieldValues = fieldValues; }
    public String getTitle() { return title; }
    public String getRoutePath() { return routePath; }
    public String getTemplatePath() { return templatePath; }
    public int getModelVersion() { return modelVersion; }
    public String getNote() { return note; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean isAiGenerated() { return aiGenerated; }
    public String getAiModel() { return aiModel; }
    public Integer getAiPromptVersion() { return aiPromptVersion; }
    public Instant getAiGeneratedAt() { return aiGeneratedAt; }
    /** OBS-11-10：来源版本号（仅「恢复到 v{n}」产生的版本有值）。 */
    public Integer getRevertedFrom() { return revertedFrom; }
    /** DEF-11-02：是否「发布」动作生成的版本（版本历史中的发布节点）。 */
    public boolean isPublished() { return published; }
}
