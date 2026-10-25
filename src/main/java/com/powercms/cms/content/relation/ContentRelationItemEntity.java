package com.powercms.cms.content.relation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** 内容关系实例（FR-CM-RELATION-013）：source 与 target 内容项之间的实际关联。 */
@Entity
@Table(
    name = "content_relation_item",
    uniqueConstraints =
        @UniqueConstraint(columnNames = {"relation_id", "source_content_id", "target_content_id"}))
public class ContentRelationItemEntity {
  @Id private UUID id;
  @Column(name = "relation_id", nullable = false)
  private UUID relationId;
  @Column(name = "source_content_id", nullable = false)
  private UUID sourceContentId;
  @Column(name = "target_content_id", nullable = false)
  private UUID targetContentId;
  @Column(name = "sort_order", nullable = false)
  private int sortOrder;
  @Column(name = "created_by", nullable = false, length = 100)
  private String createdBy;
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void create() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID v) { id = v; }
  public UUID getRelationId() { return relationId; }
  public void setRelationId(UUID v) { relationId = v; }
  public UUID getSourceContentId() { return sourceContentId; }
  public void setSourceContentId(UUID v) { sourceContentId = v; }
  public UUID getTargetContentId() { return targetContentId; }
  public void setTargetContentId(UUID v) { targetContentId = v; }
  public int getSortOrder() { return sortOrder; }
  public void setSortOrder(int v) { sortOrder = v; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String v) { createdBy = v; }
  public Instant getCreatedAt() { return createdAt; }
}
