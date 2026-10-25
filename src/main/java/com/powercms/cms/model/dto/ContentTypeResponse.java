package com.powercms.cms.model.dto;

import com.fasterxml.jackson.databind.*;
import com.powercms.cms.model.ContentModelValidator;
import com.powercms.cms.model.entity.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public record ContentTypeResponse(
    UUID id,
    UUID siteId,
    String code,
    String name,
    String description,
    String templateType,
    String displayTemplate,
    String icon,
    String thumbnail,
    String objectType,
    boolean isSystem,
    String controllerPath,
    String formController,
    String mergeStrategy,
    UUID parentTypeId,
    boolean quickCreate,
    String destinationPattern,
    String previewImage,
    JsonNode config,
    boolean flattenComponents,
    String subtype,
    String formNotificationEmails,
    boolean contentMenuEnabled,
    String contentMenuLabel,
    int contentMenuOrder,
    String mediaKind,
    String storagePathPattern,
    String mediaAccept,
    Long mediaMaxFileLength,
    String schemaOrgType,
    int version,
    String status,
    Integer publishedVersion,
    Instant publishedAt,
    String publishedBy,
    Instant createdAt,
    List<LayoutInfo> layout,
    List<FieldInfo> fields) {
  public record FieldInfo(
      UUID id,
      String code,
      String name,
      String fieldType,
      boolean required,
      int sortOrder,
      String groupName,
      String helpText,
      String defaultValue,
      boolean readOnly,
      JsonNode validation,
      JsonNode fieldConfig,
      String conditionExpr,
      boolean i18n,
      boolean adminIndex,
      boolean publicIndex,
      boolean sortable,
      boolean filterable,
      boolean highlight,
      boolean isSystem,
      String systemFieldType,
      boolean uniqueField,
      JsonNode fieldVariables) {}

  public record LayoutInfo(
      String elementType,
      UUID elementId,
      int sortOrder,
      JsonNode config,
      FieldInfo field,
      List<LayoutInfo> children) {}

  public static ContentTypeResponse from(
      ContentTypeEntity ct,
      List<FieldDefinitionEntity> fs,
      List<FormLayoutEntity> ls,
      ObjectMapper om) {
    Map<UUID, FieldDefinitionEntity> byId =
        fs.stream().collect(Collectors.toMap(FieldDefinitionEntity::getId, Function.identity()));
    Map<UUID, List<FormLayoutEntity>> children =
        ls.stream()
            .collect(
                Collectors.groupingBy(
                    x -> x.getParentId() == null ? new UUID(0, 0) : x.getParentId()));
    Set<UUID> elementIds =
        fs.stream().map(FieldDefinitionEntity::getId).collect(Collectors.toSet());
    if (ls.stream().anyMatch(x -> !elementIds.contains(x.getElementId())))
      throw new IllegalStateException("布局引用了不存在的元素");
    List<LayoutInfo> layout =
        build(children.getOrDefault(new UUID(0, 0), List.of()), children, byId, om);
    List<FieldInfo> fields =
        fs.stream()
            .filter(f -> !ContentModelValidator.STRUCTURAL.contains(f.getFieldType()))
            .map(f -> field(f, om))
            .toList();
    return new ContentTypeResponse(
        ct.getId(),
        ct.getSiteId(),
        ct.getCode(),
        ct.getName(),
        ct.getDescription(),
        ct.getTemplateType(),
        ct.getDisplayTemplate(),
        ct.getIcon(),
        ct.getThumbnail(),
        ct.getObjectType(),
        ct.isSystem(),
        ct.getControllerPath(),
        ct.getFormController(),
        ct.getMergeStrategy(),
        ct.getParentTypeId(),
        ct.isQuickCreate(),
        ct.getDestinationPattern(),
        ct.getPreviewImage(),
        json(ct.getTypeConfig(), om),
        ct.isFlattenComponents(),
        ct.getSubtype(),
        ct.getFormNotificationEmails(),
        ct.isContentMenuEnabled(),
        ct.getContentMenuLabel(),
        ct.getContentMenuOrder(),
        ct.getMediaKind(),
        ct.getStoragePathPattern(),
        ct.getMediaAccept(),
        ct.getMediaMaxFileLength(),
        ct.getSchemaOrgType(),
        ct.getVersion(),
        ct.getStatus(),
        ct.getPublishedVersion(),
        ct.getPublishedAt(),
        ct.getPublishedBy(),
        ct.getCreatedAt(),
        layout,
        fields);
  }

  private static List<LayoutInfo> build(
      List<FormLayoutEntity> roots,
      Map<UUID, List<FormLayoutEntity>> children,
      Map<UUID, FieldDefinitionEntity> byId,
      ObjectMapper om) {
    return roots.stream()
        .sorted(
            Comparator.comparingInt(FormLayoutEntity::getSortOrder)
                .thenComparing(FormLayoutEntity::getElementId))
        .map(
            l -> {
              FieldDefinitionEntity f = byId.get(l.getElementId());
              boolean structural = !"FIELD".equals(l.getElementType());              return new LayoutInfo(
                  l.getElementType(),
                  l.getElementId(),
                  l.getSortOrder(),
                  structural ? json(f == null ? null : f.getFieldConfig(), om) : null,
                  structural ? null : field(f, om),
                  build(children.getOrDefault(l.getElementId(), List.of()), children, byId, om));
            })
        .toList();
  }

  private static FieldInfo field(FieldDefinitionEntity f, ObjectMapper om) {
    if (f == null) return null;
    JsonNode fieldConfig = json(f.getFieldConfig(), om);
    JsonNode fieldVariables =
        fieldConfig != null && fieldConfig.isObject() ? fieldConfig.get("fieldVariables") : null;
    return new FieldInfo(
        f.getId(),
        f.getCode(),
        f.getName(),
        f.getFieldType(),
        f.isRequired(),
        f.getSortOrder(),
        f.getGroupName(),
        f.getHelpText(),
        f.getDefaultValue(),
        f.isReadOnly(),
        json(f.getValidation(), om),
        fieldConfig,
        f.getConditionExpr(),
        f.isI18n(),
        f.isAdminIndex(),
        f.isPublicIndex(),
        f.isSortable(),
        f.isFilterable(),
        f.isHighlight(),
        f.isSystem(),
        f.getSystemFieldType(),
        f.isUniqueField(),
        fieldVariables);
  }

  private static JsonNode json(String s, ObjectMapper om) {
    if (s == null || s.isBlank()) return null;
    try {
      return om.readTree(s);
    } catch (Exception e) {
      return om.getNodeFactory().textNode(s);
    }
  }
}
