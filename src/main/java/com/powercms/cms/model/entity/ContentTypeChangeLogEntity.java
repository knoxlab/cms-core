package com.powercms.cms.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "content_type_change_log")
public class ContentTypeChangeLogEntity {
  @Id private UUID id;

  @Column(name = "content_type_id", nullable = false)
  private UUID contentTypeId;

  @Column(name = "from_version")
  private Integer fromVersion;

  @Column(name = "to_version", nullable = false)
  private int toVersion;

  @Column(name = "change_summary", nullable = false, columnDefinition = "text")
  private String changeSummary;

  @Column(name = "migration_rules", columnDefinition = "text")
  private String migrationRules;

  @Column(nullable = false, length = 100)
  private String operator;

  @Column(name = "operated_at", nullable = false)
  private Instant operatedAt;

  @PrePersist
  void create() {
    if (id == null) id = UUID.randomUUID();
    if (operatedAt == null) operatedAt = Instant.now();
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

  public Integer getFromVersion() {
    return fromVersion;
  }

  public void setFromVersion(Integer v) {
    fromVersion = v;
  }

  public int getToVersion() {
    return toVersion;
  }

  public void setToVersion(int v) {
    toVersion = v;
  }

  public String getChangeSummary() {
    return changeSummary;
  }

  public void setChangeSummary(String v) {
    changeSummary = v;
  }

  public String getMigrationRules() {
    return migrationRules;
  }

  public void setMigrationRules(String v) {
    migrationRules = v;
  }

  public String getOperator() {
    return operator;
  }

  public void setOperator(String v) {
    operator = v;
  }

  public Instant getOperatedAt() {
    return operatedAt;
  }
}
