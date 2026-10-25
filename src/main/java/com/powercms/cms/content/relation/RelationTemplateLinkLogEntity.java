package com.powercms.cms.content.relation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-AI-SRC-003 挂链审计：模板触发的自动关联记录。
 * 唯一约束（template + record + anchor）保证同一模板对同一对内容只记一次（去重）。
 */
@Entity
@Table(name = "relation_template_link_log",
    uniqueConstraints = @UniqueConstraint(columnNames =
        {"template_id", "record_content_id", "anchor_content_id"}))
public class RelationTemplateLinkLogEntity {

    @Id
    private UUID id;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    /** 同步创建/更新的条目（PIM 侧）。 */
    @Column(name = "record_content_id", nullable = false)
    private UUID recordContentId;

    /** 锚点条目（CMS 产品）。 */
    @Column(name = "anchor_content_id", nullable = false)
    private UUID anchorContentId;

    @Column(name = "relation_id", nullable = false)
    private UUID relationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void create() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTemplateId() { return templateId; }
    public UUID getDatasourceId() { return datasourceId; }
    public UUID getRecordContentId() { return recordContentId; }
    public UUID getAnchorContentId() { return anchorContentId; }
    public UUID getRelationId() { return relationId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setTemplateId(UUID v) { templateId = v; }
    public void setDatasourceId(UUID v) { datasourceId = v; }
    public void setRecordContentId(UUID v) { recordContentId = v; }
    public void setAnchorContentId(UUID v) { anchorContentId = v; }
    public void setRelationId(UUID v) { relationId = v; }
}
