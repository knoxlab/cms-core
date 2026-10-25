package com.powercms.cms.content.compose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.cms.content.entity.CategoryEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Applies declarative class-number compose on content save.
 *
 * <p>Dynamic scripts belong on {@code CUSTOM_CODE} fields — REPEAT_GROUP compose is
 * declarative only (no JS/Groovy on the repeat group).
 */
@Service
public class ClassComposeService {

  private static final String REPEAT_PARENT_KEY = "__repeatParent";

  private final FieldDefinitionRepository fieldRepo;
  private final CategoryRepository categoryRepo;
  private final ObjectMapper om;

  ClassComposeService(
      FieldDefinitionRepository fieldRepo, CategoryRepository categoryRepo, ObjectMapper om) {
    this.fieldRepo = fieldRepo;
    this.categoryRepo = categoryRepo;
    this.om = om;
  }

  /** Rewrite fieldValues JSON: fill class_number / class_caption for REPEAT_GROUPs with compose. */
  public String applyOnSave(UUID siteId, UUID contentTypeId, String fieldValues) {
    if (fieldValues == null || fieldValues.isBlank()) return fieldValues;
    JsonNode root;
    try {
      root = om.readTree(fieldValues);
    } catch (Exception e) {
      return fieldValues;
    }
    if (root == null || !root.isObject()) return fieldValues;

    List<FieldDefinitionEntity> fields = fieldRepo.findByContentTypeIdOrderBySortOrder(contentTypeId);
    ObjectNode obj = (ObjectNode) root;
    boolean changed = false;

    for (FieldDefinitionEntity rg : fields) {
      if (!"REPEAT_GROUP".equals(rg.getFieldType())) continue;
      JsonNode cfg = parseConfig(rg.getFieldConfig());
      JsonNode compose = cfg == null ? null : cfg.get("compose");
      if (compose == null || compose.isNull() || !compose.has("roles")) continue;

      JsonNode rowsNode = obj.get(rg.getCode());
      if (rowsNode == null || !rowsNode.isArray()) continue;

      String numberField = resolveOutputField(fields, rg.getCode(), true);
      String captionField = resolveOutputField(fields, rg.getCode(), false);

      ArrayNode rows = (ArrayNode) rowsNode;
      for (int i = 0; i < rows.size(); i++) {
        JsonNode row = rows.get(i);
        if (row == null || !row.isObject()) continue;
        ClassComposeEngine.ComposeResult result = composeRow(siteId, compose, (ObjectNode) row);
        if (result.classNumber() == null || result.classNumber().isBlank()) continue;
        ObjectNode rowObj = (ObjectNode) row;
        if (numberField != null) {
          rowObj.put(numberField, result.classNumber());
          changed = true;
        }
        if (captionField != null && result.classCaption() != null) {
          rowObj.put(captionField, result.classCaption());
          changed = true;
        }
      }
    }
    if (!changed) return fieldValues;
    try {
      return om.writeValueAsString(obj);
    } catch (Exception e) {
      return fieldValues;
    }
  }

  /**
   * Designer / API try-run: compose from explicit roles (or category ids) + compose config.
   *
   * @return map with classNumber, classCaption, orderUsed
   */
  public Map<String, Object> tryCompose(UUID siteId, JsonNode body) {
    JsonNode compose = body.path("compose");
    if (compose.isMissingNode() || compose.isNull()) {
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "compose 配置不能为空");
    }
    Map<String, ClassComposeEngine.RoleValue> roles = new LinkedHashMap<>();
    JsonNode rolesNode = body.get("roles");
    if (rolesNode != null && rolesNode.isObject()) {
      rolesNode
          .fields()
          .forEachRemaining(
              e -> {
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) {
                  roles.put(e.getKey(), null);
                  return;
                }
                if (v.isTextual()) {
                  roles.put(e.getKey(), resolveCategory(siteId, v.asText()));
                  return;
                }
                String id = text(v, "id");
                String code = text(v, "code");
                String name = text(v, "name");
                if ((code == null || code.isBlank()) && id != null) {
                  ClassComposeEngine.RoleValue resolved = resolveCategory(siteId, id);
                  if (resolved != null) {
                    roles.put(e.getKey(), resolved);
                    return;
                  }
                }
                roles.put(e.getKey(), new ClassComposeEngine.RoleValue(id, code, name));
              });
    }
    ClassComposeEngine.ComposeResult result = ClassComposeEngine.compose(compose, roles);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("classNumber", result.classNumber());
    out.put("classCaption", result.classCaption());
    out.put("orderUsed", result.orderUsed());
    out.put("declarativeClassNumber", result.classNumber());
    out.put("scriptExecuted", false);
    out.put("message", "declarative only");
    return out;
  }

  private ClassComposeEngine.ComposeResult composeRow(UUID siteId, JsonNode compose, ObjectNode row) {
    Map<String, ClassComposeEngine.RoleValue> roles =
        ClassComposeEngine.rolesFromRow(compose, row, id -> resolveCategory(siteId, id));
    return ClassComposeEngine.compose(compose, roles);
  }

  private ClassComposeEngine.RoleValue resolveCategory(UUID siteId, String idOrCode) {
    if (idOrCode == null || idOrCode.isBlank()) return null;
    try {
      UUID id = UUID.fromString(idOrCode);
      return categoryRepo
          .findById(id)
          .filter(c -> siteId == null || siteId.equals(c.getSiteId()))
          .map(this::toRole)
          .orElse(null);
    } catch (IllegalArgumentException notUuid) {
      return new ClassComposeEngine.RoleValue(null, idOrCode, null);
    }
  }

  private ClassComposeEngine.RoleValue toRole(CategoryEntity c) {
    return new ClassComposeEngine.RoleValue(c.getId().toString(), c.getPathAlias(), c.getName());
  }

  private String resolveOutputField(
      List<FieldDefinitionEntity> fields, String parentCode, boolean number) {
    for (FieldDefinitionEntity f : fields) {
      JsonNode cfg = parseConfig(f.getFieldConfig());
      if (cfg == null) continue;
      if (!parentCode.equals(cfg.path(REPEAT_PARENT_KEY).asText(null))) continue;
      if (number && cfg.path("composeOutput").asBoolean(false)) return f.getCode();
      if (!number && cfg.path("composeCaption").asBoolean(false)) return f.getCode();
    }
    String fallback = number ? "class_number" : "class_caption";
    for (FieldDefinitionEntity f : fields) {
      JsonNode cfg = parseConfig(f.getFieldConfig());
      if (cfg != null
          && parentCode.equals(cfg.path(REPEAT_PARENT_KEY).asText(null))
          && fallback.equals(f.getCode())) {
        return f.getCode();
      }
    }
    return fallback;
  }

  private JsonNode parseConfig(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return om.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }

  private static String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asText() : null;
  }
}
