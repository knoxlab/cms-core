package com.powercms.cms.content.compose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative class-number composer (parity with admin-ui {@code classCompose.ts}).
 * Does not depend on subdivision.json — only role values + compose config.
 */
public final class ClassComposeEngine {

  private static final List<String> DEFAULT_ORDER =
      List.of("base", "imitation", "special", "world", "china", "nation", "general");

  private ClassComposeEngine() {}

  public record RoleValue(String id, String code, String name) {}

  public record ComposeResult(String classNumber, String classCaption, List<String> orderUsed) {}

  public static ComposeResult compose(JsonNode config, Map<String, RoleValue> roles) {
    if (config == null || config.isNull()) {
      return new ComposeResult("", "", List.of());
    }
    RoleValue baseVal = roles.get("base");
    String baseCode = baseVal != null ? baseVal.code() : null;
    Resolved resolved = resolveRoleConfigs(config, baseCode);
    StringBuilder s = new StringBuilder();
    List<String> labels = new ArrayList<>();

    for (String roleKey : resolved.order) {
      JsonNode roleCfg = resolved.roles.get(roleKey);
      if (roleCfg == null || roleCfg.isNull()) continue;
      RoleValue value = roles.get(roleKey);
      Segment seg = segmentFromRole(roleCfg, value);
      if (seg == null) continue;

      boolean wrapOrPrefix = roleCfg.hasNonNull("wrap") || roleCfg.hasNonNull("prefix");
      Boolean spaceBeforeNode =
          roleCfg.has("spaceBefore") && !roleCfg.get("spaceBefore").isNull()
              ? roleCfg.get("spaceBefore").asBoolean()
              : null;

      if (s.length() == 0) {
        s.append(seg.text);
      } else if (wrapOrPrefix) {
        if (Boolean.TRUE.equals(spaceBeforeNode)) s.append(' ');
        s.append(seg.text);
      } else {
        boolean needSpace = spaceBeforeNode != Boolean.FALSE && !endsWithMarker(s);
        if (needSpace) s.append(' ');
        s.append(seg.text);
      }
      if (seg.label != null && !seg.label.isBlank()) labels.add(seg.label);
    }
    return new ComposeResult(s.toString(), String.join(" ", labels), List.copyOf(resolved.order));
  }

  private static boolean endsWithMarker(StringBuilder s) {
    if (s.isEmpty()) return false;
    char c = s.charAt(s.length() - 1);
    return c == '>' || c == ')' || c == ']';
  }

  private static Segment segmentFromRole(JsonNode roleCfg, RoleValue value) {
    if (value == null || value.code() == null || value.code().isBlank()) return null;
    String code = value.code();
    String stripPrefix =
        roleCfg.hasNonNull("stripPrefix") ? roleCfg.get("stripPrefix").asText() : null;
    boolean useFullCode = roleCfg.path("useFullCode").asBoolean(false);
    if (stripPrefix != null && !useFullCode && code.startsWith(stripPrefix)) {
      code = code.substring(stripPrefix.length());
    }
    if (code.isBlank()) return null;

    if (roleCfg.has("wrap") && roleCfg.get("wrap").isArray() && roleCfg.get("wrap").size() >= 2) {
      ArrayNode wrap = (ArrayNode) roleCfg.get("wrap");
      return new Segment(wrap.get(0).asText() + code + wrap.get(1).asText(), value.name());
    }
    if (roleCfg.hasNonNull("prefix")) {
      return new Segment(roleCfg.get("prefix").asText() + code, value.name());
    }
    return new Segment(code, value.name());
  }

  private record Segment(String text, String label) {}

  private record Resolved(List<String> order, Map<String, JsonNode> roles) {}

  private static Resolved resolveRoleConfigs(JsonNode config, String baseCode) {
    JsonNode preset = matchPreset(baseCode, config.get("presets"));
    List<String> order = new ArrayList<>();
    if (preset != null && preset.has("order") && preset.get("order").isArray() && !preset.get("order").isEmpty()) {
      preset.get("order").forEach(n -> order.add(n.asText()));
    } else if (config.has("order") && config.get("order").isArray() && !config.get("order").isEmpty()) {
      config.get("order").forEach(n -> order.add(n.asText()));
    } else {
      order.addAll(DEFAULT_ORDER);
    }

    Map<String, JsonNode> roles = new LinkedHashMap<>();
    JsonNode baseRoles = config.get("roles");
    if (baseRoles != null && baseRoles.isObject()) {
      baseRoles.fields().forEachRemaining(e -> roles.put(e.getKey(), e.getValue().deepCopy()));
    }
    if (preset != null && preset.has("roles") && preset.get("roles").isObject()) {
      preset
          .get("roles")
          .fields()
          .forEachRemaining(
              e -> {
                ObjectNode merged =
                    roles.containsKey(e.getKey()) && roles.get(e.getKey()).isObject()
                        ? ((ObjectNode) roles.get(e.getKey())).deepCopy()
                        : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                if (!merged.has("field")) merged.put("field", e.getKey());
                e.getValue()
                    .fields()
                    .forEachRemaining(
                        p -> {
                          if (p.getValue().isNull()) merged.putNull(p.getKey());
                          else merged.set(p.getKey(), p.getValue().deepCopy());
                        });
                roles.put(e.getKey(), merged);
              });
    }
    return new Resolved(order, roles);
  }

  private static JsonNode matchPreset(String baseCode, JsonNode presets) {
    if (baseCode == null || baseCode.isBlank() || presets == null || !presets.isArray()) return null;
    for (JsonNode p : presets) {
      if (p.has("whenBaseEquals") && p.get("whenBaseEquals").isArray()) {
        for (JsonNode eq : p.get("whenBaseEquals")) {
          if (baseCode.equals(eq.asText())) return p;
        }
      }
      if (p.has("whenBasePrefix") && p.get("whenBasePrefix").isArray()) {
        for (JsonNode pre : p.get("whenBasePrefix")) {
          if (baseCode.startsWith(pre.asText())) return p;
        }
      }
    }
    return null;
  }

  /** Build role bag from a repeat row + compose.roles.*.field using a category resolver. */
  public static Map<String, RoleValue> rolesFromRow(
      JsonNode composeConfig, JsonNode row, java.util.function.Function<String, RoleValue> resolveCategory) {
    Map<String, RoleValue> out = new LinkedHashMap<>();
    JsonNode rolesCfg = composeConfig == null ? null : composeConfig.get("roles");
    if (rolesCfg == null || !rolesCfg.isObject() || row == null || !row.isObject()) return out;
    rolesCfg
        .fields()
        .forEachRemaining(
            e -> {
              String field = e.getValue().path("field").asText(e.getKey());
              JsonNode raw = row.get(field);
              if (raw == null || raw.isNull() || (raw.isTextual() && raw.asText().isBlank())) {
                out.put(e.getKey(), null);
                return;
              }
              if (raw.isTextual() && resolveCategory != null) {
                RoleValue resolved = resolveCategory.apply(raw.asText());
                out.put(e.getKey(), Objects.requireNonNullElseGet(resolved, () -> new RoleValue(raw.asText(), raw.asText(), null)));
              } else if (raw.isObject()) {
                out.put(
                    e.getKey(),
                    new RoleValue(
                        textOrNull(raw, "id"),
                        textOrNull(raw, "code"),
                        textOrNull(raw, "name")));
              } else {
                out.put(e.getKey(), new RoleValue(null, raw.asText(), null));
              }
            });
    return out;
  }

  private static String textOrNull(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asText() : null;
  }
}
