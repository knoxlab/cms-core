package com.powercms.cms.content.relation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** 内容关系定义（FR-CM-RELATION-001）：站点级双向内容关联的元数据。 */
@Entity
@Table(
    name = "content_relation",
    uniqueConstraints = @UniqueConstraint(columnNames = {"site_id", "relation_name"}))
public class ContentRelationEntity {
  public static final String ONE_TO_ONE = "ONE_TO_ONE";
  public static final String ONE_TO_MANY = "ONE_TO_MANY";
  public static final String MANY_TO_ONE = "MANY_TO_ONE";
  public static final String MANY_TO_MANY = "MANY_TO_MANY";

  @Id private UUID id;
  @Column(name = "site_id", nullable = false)
  private UUID siteId;
  @Column(name = "relation_name", nullable = false, length = 200)
  private String relationName;
  @Column(nullable = false, length = 20)
  private String cardinality;
  @Column(name = "source_type_id", nullable = false)
  private UUID sourceTypeId;
  @Column(name = "target_type_id", nullable = false)
  private UUID targetTypeId;
  @Column(length = 500)
  private String description;
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
  public UUID getSiteId() { return siteId; }
  public void setSiteId(UUID v) { siteId = v; }
  public String getRelationName() { return relationName; }
  public void setRelationName(String v) { relationName = v; }
  public String getCardinality() { return cardinality; }
  public void setCardinality(String v) { cardinality = v; }
  public UUID getSourceTypeId() { return sourceTypeId; }
  public void setSourceTypeId(UUID v) { sourceTypeId = v; }
  public UUID getTargetTypeId() { return targetTypeId; }
  public void setTargetTypeId(UUID v) { targetTypeId = v; }
  public String getDescription() { return description; }
  public void setDescription(String v) { description = v; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String v) { createdBy = v; }
  public Instant getCreatedAt() { return createdAt; }
}
