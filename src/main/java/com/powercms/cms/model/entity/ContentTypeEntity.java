package com.powercms.cms.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "content_type",
    uniqueConstraints = @UniqueConstraint(columnNames = {"site_id", "code", "version"}))
public class ContentTypeEntity {
  public static final String STATUS_DRAFT = "DRAFT", STATUS_PUBLISHED = "PUBLISHED",
      STATUS_DEPRECATED = "DEPRECATED";
  @Id private UUID id;

  @Column(name = "site_id", nullable = false)
  private UUID siteId;

  @Column(nullable = false, length = 100)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(length = 1000)
  private String description;

  @Column(nullable = false)
  private int version = 1;

  @Column(nullable = false, length = 20)
  private String status = STATUS_DRAFT;

  @Column(name = "template_type", nullable = false, length = 20)
  private String templateType = "PAGE";

  @Column(name = "display_template", length = 500)
  private String displayTemplate;

  @Column(length = 100)
  private String icon;

  @Column(length = 500)
  private String thumbnail;

  @Column(name = "published_version")
  private Integer publishedVersion;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "published_by", length = 100)
  private String publishedBy;

  @Column(name = "object_type", nullable = false, length = 20)
  private String objectType = "PAGE";
  @Column(name = "controller_path", length = 500) private String controllerPath;
  @Column(name = "form_controller", length = 1000) private String formController;
  @Column(name = "merge_strategy", nullable = false, length = 20) private String mergeStrategy = "NONE";
  @Column(name = "parent_type_id") private UUID parentTypeId;
  @Column(name = "quick_create", nullable = false) private boolean quickCreate;
  @Column(name = "destination_pattern", length = 500) private String destinationPattern;
  @Column(name = "preview_image", length = 500) private String previewImage;
  @Column(name = "type_config", columnDefinition = "text") private String typeConfig;
  @Column(name = "flatten_components", nullable = false) private boolean flattenComponents = true;
  @Column(length = 20) private String subtype;
  @Column(name = "copied_from_type_id") private UUID copiedFromTypeId;
  @Column(name = "form_notification_emails", columnDefinition = "text") private String formNotificationEmails;
  @Column(name = "content_menu_enabled", nullable = false) private boolean contentMenuEnabled;
  @Column(name = "content_menu_label", length = 200) private String contentMenuLabel;
  @Column(name = "content_menu_order", nullable = false) private int contentMenuOrder;
  /** 媒体内容类型专属：video/audio/image/document/font/other（objectType=MEDIA 时必填，FR-CM-MEDIA-006）。 */
  @Column(name = "media_kind", length = 20) private String mediaKind;
  /** 默认存储路径模式（宏 {yyyy}/{mm}/{dd}/{objectId}/{kind}/{siteId}，FR-CM-MEDIA-050）。 */
  @Column(name = "storage_path_pattern", length = 500) private String storagePathPattern;
  /** 类型级 MIME 白名单（逗号分隔，FR-CM-MEDIA-040）。 */
  @Column(name = "media_accept", length = 1000) private String mediaAccept;
  /** 类型级上传字节上限（FR-CM-MEDIA-040）。 */
  @Column(name = "media_max_file_length") private Long mediaMaxFileLength;
  /** 系统内容类型（system=true，spec 8.9）：不可删除、不可改 code/objectType。 */
  @Column(name = "is_system", nullable = false) private boolean isSystem;

  /** FR-AI-GEO-001：内容类型对应的 schema.org 类型（默认 Article；可选 NewsArticle/FAQPage/Product/Organization 等）。 */
  @Column(name = "schema_org_type", nullable = false, length = 50)
  private String schemaOrgType = "Article";
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    if (id == null) id = UUID.randomUUID();
    Instant n = Instant.now();
    createdAt = n;
    updatedAt = n;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID v) {
    id = v;
  }

  public UUID getSiteId() {
    return siteId;
  }

  public void setSiteId(UUID v) {
    siteId = v;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String v) {
    code = v;
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String v) {
    description = v;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int v) {
    version = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public String getTemplateType() {
    return templateType;
  }

  public void setTemplateType(String v) {
    templateType = v;
  }

  public String getDisplayTemplate() {
    return displayTemplate;
  }

  public void setDisplayTemplate(String v) {
    displayTemplate = v;
  }

  public String getIcon() {
    return icon;
  }

  public void setIcon(String v) {
    icon = v;
  }

  public String getThumbnail() {
    return thumbnail;
  }

  public void setThumbnail(String v) {
    thumbnail = v;
  }

  public Integer getPublishedVersion() {
    return publishedVersion;
  }

  public void setPublishedVersion(Integer v) {
    publishedVersion = v;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant v) {
    publishedAt = v;
  }

  public String getPublishedBy() {
    return publishedBy;
  }

  public void setPublishedBy(String v) {
    publishedBy = v;
  }

  public String getObjectType() { return objectType; }
  public void setObjectType(String v) { objectType = v; }
  public String getControllerPath() { return controllerPath; }
  public void setControllerPath(String v) { controllerPath = v; }
  public String getFormController() { return formController; }
  public void setFormController(String v) { formController = v; }
  public String getMergeStrategy() { return mergeStrategy; }
  public void setMergeStrategy(String v) { mergeStrategy = v; }
  public UUID getParentTypeId() { return parentTypeId; }
  public void setParentTypeId(UUID v) { parentTypeId = v; }
  public boolean isQuickCreate() { return quickCreate; }
  public void setQuickCreate(boolean v) { quickCreate = v; }
  public String getDestinationPattern() { return destinationPattern; }
  public void setDestinationPattern(String v) { destinationPattern = v; }
  public String getPreviewImage() { return previewImage; }
  public void setPreviewImage(String v) { previewImage = v; }
  public String getTypeConfig() { return typeConfig; }
  public void setTypeConfig(String v) { typeConfig = v; }
  public boolean isFlattenComponents() { return flattenComponents; }
  public void setFlattenComponents(boolean v) { flattenComponents = v; }
  public String getSubtype() { return subtype; }
  public void setSubtype(String v) { subtype = v; }
  public UUID getCopiedFromTypeId() { return copiedFromTypeId; }
  public void setCopiedFromTypeId(UUID v) { copiedFromTypeId = v; }
  public String getFormNotificationEmails() { return formNotificationEmails; }
  public void setFormNotificationEmails(String v) { formNotificationEmails = v; }
  public boolean isContentMenuEnabled() { return contentMenuEnabled; }
  public void setContentMenuEnabled(boolean v) { contentMenuEnabled = v; }
  public String getContentMenuLabel() { return contentMenuLabel; }
  public void setContentMenuLabel(String v) { contentMenuLabel = v; }
  public int getContentMenuOrder() { return contentMenuOrder; }
  public void setContentMenuOrder(int v) { contentMenuOrder = v; }
  public String getMediaKind() { return mediaKind; }
  public void setMediaKind(String v) { mediaKind = v; }
  public String getStoragePathPattern() { return storagePathPattern; }
  public void setStoragePathPattern(String v) { storagePathPattern = v; }
  public String getMediaAccept() { return mediaAccept; }
  public void setMediaAccept(String v) { mediaAccept = v; }
  public Long getMediaMaxFileLength() { return mediaMaxFileLength; }
  public void setMediaMaxFileLength(Long v) { mediaMaxFileLength = v; }
  public boolean isSystem() { return isSystem; }
  public void setSystem(boolean v) { isSystem = v; }
  public String getSchemaOrgType() { return schemaOrgType; }
  public void setSchemaOrgType(String v) { schemaOrgType = v; }
  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
