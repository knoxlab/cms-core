package com.powercms.cms.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "form_layout")
public class FormLayoutEntity {
  @Id private UUID id;

  @Column(name = "content_type_id", nullable = false)
  private UUID contentTypeId;

  @Column(name = "element_type", nullable = false, length = 20)
  private String elementType;

  @Column(name = "element_id", nullable = false)
  private UUID elementId;

  @Column(name = "parent_id")
  private UUID parentId;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void create() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID v) {
    id = v;
  }

  public UUID getContentTypeId() {
    return contentTypeId;
  }

  public void setContentTypeId(UUID v) {
    contentTypeId = v;
  }

  public String getElementType() {
    return elementType;
  }

  public void setElementType(String v) {
    elementType = v;
  }

  public UUID getElementId() {
    return elementId;
  }

  public void setElementId(UUID v) {
    elementId = v;
  }

  public UUID getParentId() {
    return parentId;
  }

  public void setParentId(UUID v) {
    parentId = v;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int v) {
    sortOrder = v;
  }
}
