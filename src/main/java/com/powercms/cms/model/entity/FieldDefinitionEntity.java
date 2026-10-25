package com.powercms.cms.model.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
    name = "field_definition",
    uniqueConstraints = @UniqueConstraint(columnNames = {"content_type_id", "code"}))
public class FieldDefinitionEntity {
  @Id private UUID id;

  @Column(name = "content_type_id", nullable = false)
  private UUID contentTypeId;

  @Column(nullable = false, length = 100)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "field_type", nullable = false, length = 30)
  private String fieldType;

  @Column(nullable = false)
  private boolean required;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "group_name", length = 100)
  private String groupName;

  @Column(name = "default_value", length = 500)
  private String defaultValue;

  @Column(columnDefinition = "text")
  private String validation;

  @Column(nullable = false)
  private boolean i18n;

  @Column(name = "admin_index", nullable = false)
  private boolean adminIndex;

  @Column(name = "public_index", nullable = false)
  private boolean publicIndex;

  @Column(nullable = false)
  private boolean sortable;

  @Column(nullable = false)
  private boolean filterable;

  @Column(nullable = false)
  private boolean highlight;

  @Column(name = "help_text", length = 1000)
  private String helpText;

  @Column(name = "read_only", nullable = false)
  private boolean readOnly;

  @Column(name = "parent_id")
  private UUID parentId;

  @Column(name = "field_config", columnDefinition = "text")
  private String fieldConfig;

  @Column(name = "condition_expr", length = 1000)
  private String conditionExpr;

  @Column(name = "is_system", nullable = false)
  private boolean isSystem;

  @Column(name = "system_field_type", length = 50)
  private String systemFieldType;

  @Column(name = "unique_field", nullable = false)
  private boolean uniqueField;

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

  public String getFieldType() {
    return fieldType;
  }

  public void setFieldType(String v) {
    fieldType = v;
  }

  public boolean isRequired() {
    return required;
  }

  public void setRequired(boolean v) {
    required = v;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int v) {
    sortOrder = v;
  }

  public String getGroupName() {
    return groupName;
  }

  public void setGroupName(String v) {
    groupName = v;
  }

  public String getDefaultValue() {
    return defaultValue;
  }

  public void setDefaultValue(String v) {
    defaultValue = v;
  }

  public String getValidation() {
    return validation;
  }

  public void setValidation(String v) {
    validation = v;
  }

  public boolean isI18n() {
    return i18n;
  }

  public void setI18n(boolean v) {
    i18n = v;
  }

  public boolean isAdminIndex() {
    return adminIndex;
  }

  public void setAdminIndex(boolean v) {
    adminIndex = v;
  }

  public boolean isPublicIndex() {
    return publicIndex;
  }

  public void setPublicIndex(boolean v) {
    publicIndex = v;
  }

  public boolean isSortable() {
    return sortable;
  }

  public void setSortable(boolean v) {
    sortable = v;
  }

  public boolean isFilterable() {
    return filterable;
  }

  public void setFilterable(boolean v) {
    filterable = v;
  }

  public boolean isHighlight() {
    return highlight;
  }

  public void setHighlight(boolean v) {
    highlight = v;
  }

  public String getHelpText() {
    return helpText;
  }

  public void setHelpText(String v) {
    helpText = v;
  }

  public boolean isReadOnly() {
    return readOnly;
  }

  public void setReadOnly(boolean v) {
    readOnly = v;
  }

  public UUID getParentId() {
    return parentId;
  }

  public void setParentId(UUID v) {
    parentId = v;
  }

  public String getFieldConfig() {
    return fieldConfig;
  }

  public void setFieldConfig(String v) {
    fieldConfig = v;
  }

  public String getConditionExpr() {
    return conditionExpr;
  }

  public void setConditionExpr(String v) {
    conditionExpr = v;
  }

  public boolean isSystem() {
    return isSystem;
  }

  public void setSystem(boolean v) {
    isSystem = v;
  }

  public String getSystemFieldType() {
    return systemFieldType;
  }

  public void setSystemFieldType(String v) {
    systemFieldType = v;
  }

  public boolean isUniqueField() {
    return uniqueField;
  }

  public void setUniqueField(boolean v) {
    uniqueField = v;
  }
}
