package com.powercms.cms.content.relation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-AI-SRC-003 关系模板：站点级「外部记录 → 锚点内容」自动挂链规则。
 *
 * <p>数据源同步（SRC-001）成功 upsert 条目后，按模板把新条目与锚点产品建立
 * {@link ContentRelationItemEntity} 实例：锚点条目最新版本 fieldValues 中
 * {@code anchorField} 的值 == 记录 fieldValues 中 {@code recordField} 的值。
 */
@Entity
@Table(name = "relation_template",
    uniqueConstraints = @UniqueConstraint(columnNames = {"site_id", "name"}))
public class RelationTemplateEntity {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";

    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(nullable = false, length = 200)
    private String name;

    /** PIM 数据源（datasource_registration.id，SRC-001）。 */
    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    /** 目标关系定义名（content_relation.relation_name，须为站点级已存在定义）。 */
    @Column(name = "relation_name", nullable = false, length = 200)
    private String relationName;

    /** 锚点字段：产品条目 fieldValues 中的字段 code（如 productCode）。 */
    @Column(name = "anchor_field", nullable = false, length = 100)
    private String anchorField;

    /** 记录字段：同步条目 fieldValues 中携带锚点值的字段 code（如 product_ref）。 */
    @Column(name = "record_field", nullable = false, length = 100)
    private String recordField;

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void create() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { id = v; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID v) { siteId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public UUID getDatasourceId() { return datasourceId; }
    public void setDatasourceId(UUID v) { datasourceId = v; }
    public String getRelationName() { return relationName; }
    public void setRelationName(String v) { relationName = v; }
    public String getAnchorField() { return anchorField; }
    public void setAnchorField(String v) { anchorField = v; }
    public String getRecordField() { return recordField; }
    public void setRecordField(String v) { recordField = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
