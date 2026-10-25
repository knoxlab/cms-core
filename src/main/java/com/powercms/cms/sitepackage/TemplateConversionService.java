package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.cms.delivery.FreeMarkerSandbox;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设计器 JSON ↔ FreeMarker 双向转换（FR-TPLAPI-004 / FR-AGT-TPL-010）。
 *
 * <p>设计器 JSON 采用平台既有「布局」结构（与 {@code generate_layout} 同源）：
 * <pre>
 * { "type":"layout", "layout_mode":"two-column",
 *   "rows":[ { "row_id":"main", "grid_template":"1fr 320px",
 *              "slots":[ { "slot_id":"content", "default_component":"content-block", "label":"主内容区" } ] } ] }
 * </pre>
 *
 * <p>转换规则：
 * <ul>
 *   <li><b>JSON→FreeMarker</b>：按行/槽生成可渲染的 {@code <div class="pc-row">} / {@code <div class="pc-slot">}
 *       区块与 `${renderComponent("...")}` 组件调用；行/槽以 {@code data-row}/{@code data-grid}/{@code data-slot}
 *       /{@code data-label} 标注，供反向转换**无损还原**；</li>
 *   <li><b>FreeMarker→JSON</b>：优先依据上述 {@code data-*} 标记还原；含自定义指令（{@code <#...>}/{@code <@...>}）
 *       或非 {@code renderComponent} 表达式时，**不静默丢弃**——逐项写入 {@code conflicts} 与 JSON 的
 *       {@code unmappable} 占位清单，由人工处置；</li>
 *   <li>安全与语法：转换前经 {@link TemplateSecurityValidator} 黑名单，生成/还原的 FreeMarker 经
 *       {@link FreeMarkerSandbox#parse} 静态校验（不渲染、不落库）。</li>
 * </ul>
 */
@Service
public class TemplateConversionService {

    public static final String DIRECTION_JSON_TO_FTL = "json_to_ftl";
    public static final String DIRECTION_FTL_TO_JSON = "ftl_to_json";

    private static final String DEFAULT_COMPONENT_PREFIX = "/site/components/";

    private static final Pattern LAYOUT_MODE = Pattern.compile("data-layout-mode=\"([^\"]*)\"");
    private static final Pattern ROW =
        Pattern.compile("data-row=\"([^\"]*)\"(?:[^>]*?data-grid=\"([^\"]*)\")?");
    private static final Pattern SLOT = Pattern.compile("data-slot=\"([^\"]*)\"");
    private static final Pattern LABEL = Pattern.compile("data-label=\"([^\"]*)\"");
    private static final Pattern RENDER_COMPONENT =
        Pattern.compile("renderComponent\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern DIRECTIVE = Pattern.compile("<([#@])([A-Za-z_][\\w.]*)");
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^{}]*)}");
    private static final Pattern SAFE_COMPONENT =
        Pattern.compile("[A-Za-z0-9_./\\-]+");

    private final FreeMarkerSandbox sandbox;
    private final TemplateValidationService validation;
    private final ObjectMapper objectMapper;

    public TemplateConversionService(FreeMarkerSandbox sandbox, TemplateValidationService validation,
                                     ObjectMapper objectMapper) {
        this.sandbox = sandbox;
        this.validation = validation;
        this.objectMapper = objectMapper;
    }

    /** 转换结果：产物 + 方向 + 冲突清单（不静默丢弃）+ 语法校验。 */
    public record ConversionResult(String output, String direction, boolean valid,
                                   List<String> conflicts, List<String> securityIssues,
                                   Integer line, Integer column) {
    }

    /** 规范化方向；未知值回退 {@code json_to_ftl}（由调用方决定是否先前置拒绝）。 */
    public static String normalizeDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return DIRECTION_JSON_TO_FTL;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return DIRECTION_FTL_TO_JSON.equals(value) ? DIRECTION_FTL_TO_JSON : DIRECTION_JSON_TO_FTL;
    }

    public boolean isKnownDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return DIRECTION_JSON_TO_FTL.equals(value) || DIRECTION_FTL_TO_JSON.equals(value);
    }

    /** 设计器 JSON → FreeMarker。 */
    public ConversionResult jsonToFreeMarker(String json, String templateName) {
        List<String> conflicts = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            conflicts.add("设计器 JSON 解析失败: " + (e.getMessage() == null ? "非法 JSON" : e.getMessage()));
            return new ConversionResult("", DIRECTION_JSON_TO_FTL, false, conflicts, List.of(), null, null);
        }
        if (root == null || !root.isObject()) {
            conflicts.add("设计器 JSON 必须为对象");
            return new ConversionResult("", DIRECTION_JSON_TO_FTL, false, conflicts, List.of(), null, null);
        }
        if (root.path("type").asText("").isBlank()) {
            conflicts.add("设计器 JSON 缺少 type 字段");
            return new ConversionResult("", DIRECTION_JSON_TO_FTL, false, conflicts, List.of(), null, null);
        }
        String layoutMode = root.path("layout_mode").asText("single-column");
        JsonNode rows = root.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            conflicts.add("设计器 JSON 未包含 rows 行定义，无法生成结构化 FreeMarker");
            return new ConversionResult("", DIRECTION_JSON_TO_FTL, false, conflicts, List.of(), null, null);
        }

        StringBuilder out = new StringBuilder();
        out.append("<#-- 由 convert_template 从设计器 JSON 生成；data-* 标注用于反向无损还原 -->\n");
        out.append("<div class=\"pc-layout\" data-layout-mode=\"").append(sanitizeAttribute(layoutMode)).append("\">\n");
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                conflicts.add("忽略非对象的行定义");
                continue;
            }
            String rowId = row.path("row_id").asText("row" + (out.length()));
            String grid = row.path("grid_template").asText("1fr");
            out.append("  <div class=\"pc-row\" data-row=\"").append(sanitizeAttribute(rowId))
                .append("\" data-grid=\"").append(sanitizeAttribute(grid))
                .append("\" style=\"display:grid;grid-template-columns:").append(sanitizeAttribute(grid)).append("\">\n");
            JsonNode slots = row.get("slots");
            if (slots != null && slots.isArray()) {
                for (JsonNode slot : slots) {
                    if (!slot.isObject()) {
                        conflicts.add("行 " + rowId + " 含非对象的槽定义，已忽略");
                        continue;
                    }
                    String slotId = slot.path("slot_id").asText("slot");
                    String label = slot.path("label").asText("");
                    String component = slot.path("default_component").asText("");
                    out.append("    <div class=\"pc-slot\" data-slot=\"").append(sanitizeAttribute(slotId)).append("\"");
                    if (!label.isBlank()) {
                        out.append(" data-label=\"").append(sanitizeAttribute(label)).append("\"");
                    }
                    out.append(">\n");
                    if (component.isBlank()) {
                        conflicts.add("槽 " + slotId + " 未绑定 default_component，已输出占位注释");
                        out.append("      <#-- 槽 ").append(sanitizeComment(slotId)).append(" 未绑定组件 -->\n");
                    } else if (!SAFE_COMPONENT.matcher(component).matches()) {
                        conflicts.add("槽 " + slotId + " 的组件标识含非法字符，已输出占位注释: " + component);
                        out.append("      <#-- 槽 ").append(sanitizeComment(slotId))
                            .append(" 组件标识非法: ").append(sanitizeComment(component)).append(" -->\n");
                    } else {
                        out.append("      ${renderComponent(\"").append(sanitizeComponentPath(component)).append("\")}\n");
                    }
                    out.append("    </div>\n");
                }
            }
            out.append("  </div>\n");
        }
        out.append("</div>\n");

        String output = out.toString();
        TemplateValidationService.ValidationResult check =
            validation.validate(output, TemplateValidationService.MODE_FREEMARKER,
                templateName == null || templateName.isBlank() ? "converted.ftl" : templateName);
        return new ConversionResult(output, DIRECTION_JSON_TO_FTL, check.valid(), conflicts,
            check.securityIssues(), check.line(), check.column());
    }

    /** FreeMarker → 设计器 JSON。 */
    public ConversionResult freeMarkerToJson(String source, String templateName) {
        List<String> securityIssues = TemplateSecurityValidator.validate(source);
        if (!securityIssues.isEmpty()) {
            return new ConversionResult("", DIRECTION_FTL_TO_JSON, false, List.of(), securityIssues, null, null);
        }
        var parsed = sandbox.parse(
            templateName == null || templateName.isBlank() ? "converted.ftl" : templateName, source);
        if (!parsed.valid()) {
            return new ConversionResult("", DIRECTION_FTL_TO_JSON, false,
                List.of("FreeMarker 语法解析失败，无法还原设计器结构"
                    + (parsed.message() == null ? "" : ": " + parsed.message())),
                List.of(), parsed.line(), parsed.column());
        }

        List<String> conflicts = new ArrayList<>();
        List<String> unmappable = new ArrayList<>();

        // 1) data-* 结构化标记还原
        Set<String> layoutModes = new LinkedHashSet<>();
        Matcher modeMatcher = LAYOUT_MODE.matcher(source);
        while (modeMatcher.find()) {
            layoutModes.add(modeMatcher.group(1));
        }
        String layoutMode = layoutModes.isEmpty() ? "single-column" : layoutModes.iterator().next();
        if (layoutModes.size() > 1) {
            conflicts.add("检测到多个 data-layout-mode，取首个: " + layoutMode);
        }

        ArrayNode rows = objectMapper.createArrayNode();
        ObjectNode currentRow = null;
        ObjectNode currentSlot = null;
        boolean[] sawStructure = {false};
        for (String line : source.split("\\R")) {
            Matcher rowMatcher = ROW.matcher(line);
            if (rowMatcher.find()) {
                sawStructure[0] = true;
                currentRow = objectMapper.createObjectNode();
                currentRow.put("row_id", rowMatcher.group(1));
                currentRow.put("grid_template",
                    rowMatcher.group(2) == null || rowMatcher.group(2).isBlank() ? "1fr" : rowMatcher.group(2));
                currentRow.set("slots", objectMapper.createArrayNode());
                rows.add(currentRow);
                currentSlot = null;
            }
            Matcher slotMatcher = SLOT.matcher(line);
            if (slotMatcher.find()) {
                sawStructure[0] = true;
                if (currentRow == null) {
                    conflicts.add("发现 data-slot 但没有所属 data-row，已置入兜底行");
                    currentRow = objectMapper.createObjectNode();
                    currentRow.put("row_id", "row");
                    currentRow.put("grid_template", "1fr");
                    currentRow.set("slots", objectMapper.createArrayNode());
                    rows.add(currentRow);
                }
                currentSlot = objectMapper.createObjectNode();
                currentSlot.put("slot_id", slotMatcher.group(1));
                Matcher labelMatcher = LABEL.matcher(line);
                if (labelMatcher.find()) {
                    currentSlot.put("label", labelMatcher.group(1));
                }
                ((ArrayNode) currentRow.get("slots")).add(currentSlot);
            }
            Matcher compMatcher = RENDER_COMPONENT.matcher(line);
            while (compMatcher.find()) {
                String path = compMatcher.group(1);
                String component = shortComponent(path);
                if (currentSlot == null) {
                    if (currentRow == null) {
                        currentRow = objectMapper.createObjectNode();
                        currentRow.put("row_id", "row");
                        currentRow.put("grid_template", "1fr");
                        currentRow.set("slots", objectMapper.createArrayNode());
                        rows.add(currentRow);
                    }
                    currentSlot = objectMapper.createObjectNode();
                    currentSlot.put("slot_id", component);
                    ((ArrayNode) currentRow.get("slots")).add(currentSlot);
                }
                currentSlot.put("default_component", component);
            }
        }

        // 2) 无法映射的指令与表达式：逐项登记，不静默丢弃
        Matcher directiveMatcher = DIRECTIVE.matcher(source);
        Set<String> reported = new LinkedHashSet<>();
        while (directiveMatcher.find()) {
            String name = directiveMatcher.group(2);
            if ("--".equals(name)) {
                continue;
            }
            String token = "<" + directiveMatcher.group(1) + name + ">";
            if (reported.add(token)) {
                unmappable.add(token);
                conflicts.add("FreeMarker 指令 " + token + " 无法映射为设计器元素，已登记为占位");
            }
        }
        Matcher interpolationMatcher = INTERPOLATION.matcher(source);
        Set<String> exprReported = new LinkedHashSet<>();
        while (interpolationMatcher.find()) {
            String expr = interpolationMatcher.group(1).trim();
            if (RENDER_COMPONENT.matcher(expr).find()) {
                continue;
            }
            if (exprReported.add(expr)) {
                unmappable.add("${" + expr + "}");
                conflicts.add("表达式 ${" + expr + "} 无法映射为设计器元素，已登记为占位");
            }
        }
        if (!sawStructure[0]) {
            conflicts.add("未识别到 data-row/data-slot 结构标记，已按组件位置生成兜底行");
        }

        ObjectNode layout = objectMapper.createObjectNode();
        layout.put("type", "layout");
        layout.put("layout_mode", layoutMode);
        layout.set("rows", rows);
        ArrayNode unmappableNode = layout.putArray("unmappable");
        for (String item : unmappable) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("token", item);
            node.put("note", "无法映射的 FreeMarker 指令/表达式，需人工处置");
            unmappableNode.add(node);
        }

        String output = layout.toPrettyString();
        return new ConversionResult(output, DIRECTION_FTL_TO_JSON, rows.size() > 0, conflicts, List.of(), null, null);
    }

    private static String sanitizeAttribute(String value) {
        if (value == null) return "";
        return value.replace("\"", "").replace("<", "").replace(">", "");
    }

    private static String sanitizeComment(String value) {
        if (value == null) return "";
        return value.replace("-->", "").replace("\n", " ");
    }

    private static String sanitizeComponentPath(String component) {
        String trimmed = component.trim();
        if (trimmed.startsWith("/")) {
            return trimmed;
        }
        return DEFAULT_COMPONENT_PREFIX + trimmed;
    }

    private static String shortComponent(String path) {
        String trimmed = path == null ? "" : path.trim();
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}
