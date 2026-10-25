package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders the structural HTML described by a visual layout. */
public final class VisualLayoutHtmlRenderer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_CLASS = Pattern.compile("[A-Za-z0-9-]+");
    private static final Set<String> SIDEBAR_POSITIONS = Set.of("LEFT", "RIGHT");

    private VisualLayoutHtmlRenderer() {
    }

    /** Compatibility entry point for callers that only need the layout structure. */
    public static String render(String layoutJson) {
        return render(layoutJson, Map.of(), "");
    }

    /** Renders the layout and inserts already-rendered zone HTML without escaping it. */
    public static String render(String layoutJson, Map<String, Object> renderedZones) {
        return render(layoutJson, renderedZones, "");
    }

    /**
     * Renders the layout around the page template body. The page body follows the rows in
     * the main region, preserving the historical "layout + page body" ordering.
     */
    public static String render(String layoutJson, Map<String, Object> renderedZones, String pageBody) {
        JsonNode root = parseLayout(layoutJson);
        if (root == null) return valueOrEmpty(pageBody);

        Map<String, Object> zones = renderedZones == null ? Map.of() : renderedZones;
        String mainHtml = renderRows(root.path("rows"), zones) + valueOrEmpty(pageBody);
        String sidebar = root.path("sidebar").asText("NONE").toUpperCase(java.util.Locale.ROOT);
        if (!SIDEBAR_POSITIONS.contains(sidebar)) return mainHtml;

        int sidebarWidth = validSidebarWidth(root.path("sidebarWidth").asInt(30));
        int mainWidth = 100 - sidebarWidth;
        String aside = "<aside class=\"visual-layout-sidebar\" style=\"width:" + sidebarWidth
            + "%;flex:0 0 " + sidebarWidth + "%\">" + renderZoneKeys(root.get("sidebarZoneKeys"), "sidebar", zones) + "</aside>";
        String main = "<main class=\"visual-layout-main\" style=\"width:" + mainWidth
            + "%;flex:0 0 " + mainWidth + "%\">" + mainHtml + "</main>";
        String content = "LEFT".equals(sidebar) ? aside + main : main + aside;
        return "<div class=\"visual-layout visual-layout--sidebar-" + sidebar.toLowerCase(java.util.Locale.ROOT)
            + "\" style=\"display:flex\">" + content + "</div>";
    }

    private static JsonNode parseLayout(String layoutJson) {
        if (layoutJson == null || layoutJson.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(layoutJson);
            return root != null && root.isObject() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String renderRows(JsonNode rows, Map<String, Object> zones) {
        if (!rows.isArray()) return "";
        StringBuilder output = new StringBuilder();
        for (JsonNode row : rows) {
            if (!row.isObject()) continue;
            output.append("<div class=\"").append(classes(row.path("classes"))).append("\">");
            JsonNode boxes = row.path("boxes");
            if (boxes.isArray()) {
                for (JsonNode box : boxes) {
                    if (!box.isObject()) continue;
                    int width = clampWidth(box.path("width").asInt(12));
                    String percent = columnPercent(width);
                    output.append("<div class=\"").append(classes(box.path("classes")))
                        .append("\" style=\"width:").append(percent).append("%;flex:0 0 ")
                        .append(percent).append("%\">");
                    output.append(renderZoneKeys(box.get("zoneKeys"), textKey(box.get("zoneKey")), zones));
                    output.append("</div>");
                }
            }
            output.append("</div>");
        }
        return output.toString();
    }

    private static String renderZoneKeys(JsonNode zoneKeys, String fallbackKey, Map<String, Object> zones) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (zoneKeys != null && zoneKeys.isArray()) {
            for (JsonNode item : zoneKeys) {
                String key = textKey(item);
                if (key != null) keys.add(key);
            }
        }
        if (keys.isEmpty() && fallbackKey != null) keys.add(fallbackKey);
        StringBuilder output = new StringBuilder();
        for (String key : keys) output.append(zoneHtml(zones.get(key)));
        return output.toString();
    }

    private static String textKey(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        String key = value.asText().trim();
        return key.isEmpty() ? null : key;
    }

    private static String classes(JsonNode value) {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        if (value.isTextual()) addClasses(classes, value.asText().split("\\s+"));
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item.isTextual()) addClasses(classes, new String[]{item.asText()});
            }
        }
        return String.join(" ", classes);
    }

    private static void addClasses(Set<String> classes, String[] candidates) {
        for (String candidate : candidates) {
            if (SAFE_CLASS.matcher(candidate).matches()) classes.add(candidate);
        }
    }

    private static String zoneHtml(Object renderedZone) {
        if (renderedZone instanceof String html) return html;
        if (renderedZone instanceof Map<?, ?> map) {
            Object html = map.get("html");
            return html == null ? "" : String.valueOf(html);
        }
        return "";
    }

    private static int clampWidth(int width) {
        return Math.max(1, Math.min(12, width));
    }

    private static int validSidebarWidth(int width) {
        return width == 20 || width == 30 || width == 40 ? width : 30;
    }

    private static String columnPercent(int width) {
        return BigDecimal.valueOf(width)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
