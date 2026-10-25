package com.powercms.cms.delivery.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.powercms.cms.model.ContentTypeService;
import com.powercms.cms.model.dto.ContentTypeResponse;
import com.powercms.cms.model.dto.ContentTypeResponse.FieldInfo;
import com.powercms.cms.model.dto.ContentTypeResponse.LayoutInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已发布内容模型的表单布局。前台按返回的分区、页签和字段渲染，不在页面里写死表单项。
 */
@RestController
@RequestMapping("/api/v1/delivery/sites/{siteId}/content-forms")
class ContentFormDeliveryController {

  private final ContentTypeService types;

  ContentFormDeliveryController(ContentTypeService types) {
    this.types = types;
  }

  public record Option(String value, String label) {}

  public record FormField(
      String code,
      String name,
      String fieldType,
      boolean required,
      String helpText,
      String defaultValue,
      boolean readOnly,
      List<Option> options) {}

  public record FormNode(String elementType, String title, FormField field, List<FormNode> children) {}

  public record ContentForm(String code, String name, List<FormNode> nodes) {}

  @GetMapping("/{code}")
  ContentForm form(@PathVariable UUID siteId, @PathVariable String code) {
    ContentTypeResponse type = types.publishedByCode(siteId, code);
    List<FormNode> nodes = new ArrayList<>();
    for (LayoutInfo node : type.layout() == null ? List.<LayoutInfo>of() : type.layout()) {
      nodes.add(toNode(node));
    }
    return new ContentForm(type.code(), type.name(), List.copyOf(nodes));
  }

  private static FormNode toNode(LayoutInfo node) {
    List<FormNode> children = new ArrayList<>();
    if (node.children() != null) {
      for (LayoutInfo child : node.children()) children.add(toNode(child));
    }
    return new FormNode(node.elementType(), titleOf(node), toField(node.field()), List.copyOf(children));
  }

  private static String titleOf(LayoutInfo node) {
    if (node.field() != null && node.field().name() != null && !node.field().name().isBlank()) {
      return node.field().name();
    }
    JsonNode config = node.config();
    if (config != null && config.hasNonNull("title")) return config.get("title").asText("");
    return "";
  }

  private static FormField toField(FieldInfo field) {
    if (field == null || field.code() == null || field.code().isBlank()) return null;
    return new FormField(
        field.code(),
        field.name() == null ? field.code() : field.name(),
        field.fieldType() == null ? "STRING" : field.fieldType(),
        field.required(),
        field.helpText() == null ? "" : field.helpText(),
        field.defaultValue() == null ? "" : field.defaultValue(),
        field.readOnly(),
        optionsOf(field.fieldConfig()));
  }

  private static List<Option> optionsOf(JsonNode fieldConfig) {
    if (fieldConfig == null || !fieldConfig.has("options") || !fieldConfig.get("options").isArray()) {
      return List.of();
    }
    List<Option> options = new ArrayList<>();
    for (JsonNode item : fieldConfig.get("options")) {
      if (item.isTextual()) {
        options.add(new Option(item.asText(), item.asText()));
      } else if (item.isObject()) {
        String value = item.hasNonNull("value") ? item.get("value").asText() : item.path("label").asText("");
        String label = item.hasNonNull("label") ? item.get("label").asText() : value;
        if (!value.isBlank()) options.add(new Option(value, label.isBlank() ? value : label));
      }
    }
    return List.copyOf(options);
  }
}
