package com.powercms.cms.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public record CreateContentTypeRequest(
    @NotBlank @Size(max = 100) String code,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 1000) String description,
    String templateType,
    @Size(max = 500) String displayTemplate,
    @Size(max = 100) String icon,
    @Size(max = 500) String thumbnail,
    @Valid List<LayoutElementDto> layout,
    @Valid List<FieldDefDto> fields,
    String objectType,
    @Size(max = 500) String controllerPath,
    @Size(max = 1000) String formController,
    String mergeStrategy,
    UUID parentTypeId,
    boolean quickCreate,
    @Size(max = 500) String destinationPattern,
    @Size(max = 500) String previewImage,
    JsonNode config,
    Boolean flattenComponents,
    String subtype,
    String formNotificationEmails,
    @Size(max = 20) String mediaKind,
    @Size(max = 50) String schemaOrgType) {
  public CreateContentTypeRequest(
      String code, String name, String description, String templateType, String displayTemplate,
      String icon, String thumbnail, List<LayoutElementDto> layout, List<FieldDefDto> fields) {
    this(code, name, description, templateType, displayTemplate, icon, thumbnail, layout, fields,
        null, null, null, null, null, false, null, null, null, null, null, null, null, null);
  }

  public CreateContentTypeRequest(
      String code, String name, String description, List<FieldDefDto> fields) {
    this(code, name, description, "COMPONENT", null, null, null, null, fields);
  }

  public record LayoutElementDto(UUID elementId, @NotBlank String elementType, @Min(0) int sortOrder,
      @Valid FieldDefDto field, JsonNode config, @Valid List<LayoutElementDto> children) {}

  public record FieldDefDto(UUID id, @NotBlank @Size(max = 100) String code,
      @NotBlank @Size(max = 200) String name, @NotBlank String fieldType, boolean required,
      int sortOrder, @Size(max = 100) String groupName, @Size(max = 1000) String helpText,
      @Size(max = 500) String defaultValue, boolean readOnly, String validation,
      JsonNode fieldConfig, String conditionExpr, boolean i18n, boolean adminIndex,
      boolean publicIndex, boolean sensitive, boolean sortable, boolean filterable,
      boolean highlight, boolean uniqueField) {
    public FieldDefDto(String code, String name, String fieldType, boolean required, int sortOrder,
        String groupName, String defaultValue, String validation, boolean i18n, boolean adminIndex,
        boolean publicIndex, boolean sortable, boolean filterable, boolean highlight) {
      this(null, code, name, fieldType, required, sortOrder, groupName, null, defaultValue, false,
          validation, null, null, i18n, adminIndex, publicIndex, false, sortable, filterable,
          highlight, false);
    }

    /**
     * 从持久化实体构建<b>无损</b>字段定义（字段级属性回灌的统一承载）。
     *
     * <p>这是「读模型 {@code ContentTypeResponse.FieldInfo} ↔ 写模型 {@code FieldDefDto}」的统一入口：
     * {@code validation} 取存储原始字符串（不经 JSON 解析再序列化，避免非 JSON 校验规则或
     * JSON 字符串字面量在往返中被改写），{@code fieldConfig} 解析为 JsonNode 以便与原值做
     * 结构化比对。所有 21 个属性一一对应，供增量补丁以「现有值 + 本次改动」合成完整请求体。
     *
     * <p>注意：{@code FieldInfo.isSystem}/{@code systemFieldType} 不在此承载——它们由
     * {@code ContentTypeService.seedSystemFields} 按 code 权威重铸，不参与写入；补丁的
     * 系统字段保护改由 code → {@code FieldInfo.isSystem} 判定。
     */
    public static FieldDefDto fromEntity(FieldDefinitionEntity f, ObjectMapper om) {
      return new FieldDefDto(
          f.getId(), f.getCode(), f.getName(), f.getFieldType(), f.isRequired(), f.getSortOrder(),
          f.getGroupName(), f.getHelpText(), f.getDefaultValue(), f.isReadOnly(), f.getValidation(),
          parseJson(f.getFieldConfig(), om), f.getConditionExpr(), f.isI18n(), f.isAdminIndex(),
          f.isPublicIndex(), false, f.isSortable(), f.isFilterable(), f.isHighlight(),
          f.isUniqueField());
    }

    private static JsonNode parseJson(String raw, ObjectMapper om) {
      if (raw == null || raw.isBlank()) return null;
      try {
        return om.readTree(raw);
      } catch (Exception e) {
        return om.getNodeFactory().textNode(raw);
      }
    }
  }
}
