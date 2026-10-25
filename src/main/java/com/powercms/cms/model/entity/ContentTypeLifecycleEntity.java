package com.powercms.cms.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "content_type_lifecycle",
    uniqueConstraints = @UniqueConstraint(columnNames = {"content_type_id", "event"}))
public class ContentTypeLifecycleEntity {
  @Id private UUID id;

  @Column(name = "content_type_id", nullable = false)
  private UUID contentTypeId;

  @Column(nullable = false, length = 20)
  private String event;

  @Column(name = "script_content", nullable = false, columnDefinition = "text")
  private String scriptContent;

  @Column(name = "script_lang", nullable = false, length = 20)
  private String scriptLang = "GROOVY";

  @Column(name = "timeout_ms", nullable = false)
  private int timeoutMs = 5000;

  /** WEBHOOK 事件监听白名单（逗号分隔，空/NULL 表示监听全部 Knox webhook 事件，FR-CM-MEDIA-012）。 */
  @Column(name = "listen_events", length = 500)
  private String listenEvents;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "created_by", nullable = false, length = 100)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void create() {
    if (id == null) id = UUID.randomUUID();
    createdAt = updatedAt = Instant.now();
  }

  @PreUpdate
  void update() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getContentTypeId() {
    return contentTypeId;
  }

  public void setContentTypeId(UUID v) {
    contentTypeId = v;
  }

  public String getEvent() {
    return event;
  }

  public void setEvent(String v) {
    event = v;
  }

  public String getScriptContent() {
    return scriptContent;
  }

  public void setScriptContent(String v) {
    scriptContent = v;
  }

  public String getScriptLang() {
    return scriptLang;
  }

  public void setScriptLang(String v) {
    scriptLang = v;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(int v) {
    timeoutMs = v;
  }

  public String getListenEvents() {
    return listenEvents;
  }

  public void setListenEvents(String v) {
    listenEvents = v;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String v) {
    createdBy = v;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
