package com.powercms.cms.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Stable site capability keys used by {@code SiteAuthorization.check} (FR-IAM-004).
 */
public final class SiteCapabilities {

    public static final String READ = "read";
    public static final String WRITE = "write";
    public static final String REVIEW = "review";
    public static final String DEPLOY = "deploy";
    public static final String MODEL = "model";
    public static final String TEMPLATE = "template";
    public static final String MIGRATE = "migrate";
    public static final String ADMIN = "admin";
    /** 查看本站点统计报表（FR-STAT-090）。 */
    public static final String STATS = "stats";
    /** 跨用户聚合与实时观看明细等高敏感统计视图（FR-STAT-091/094）。 */
    public static final String STATS_ADMIN = "stats-admin";
    /** 系统日志查看（当前站点可见域内的运行/访问日志，FR-LOG-PM-001）。 */
    public static final String LOGS = "logs";
    /** 系统日志高权限：导出、平台级日志与保留策略（FR-LOG-PM-001，仅 system-admin 默认持有）。 */
    public static final String LOGS_ADMIN = "logs-admin";

    public static final Set<String> ALL = Set.of(
        READ, WRITE, REVIEW, DEPLOY, MODEL, TEMPLATE, MIGRATE, ADMIN, STATS, STATS_ADMIN,
        LOGS, LOGS_ADMIN);

    private SiteCapabilities() {}

    public static String normalize(String permission) {
        if (permission == null) return "";
        String value = permission.trim().toLowerCase(Locale.ROOT);
        if ("site-admin".equals(value)) return ADMIN;
        return value;
    }

    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) return List.of();
        LinkedHashSet<String> caps = new LinkedHashSet<>();
        for (String part : trimmed.split(",")) {
            String cap = normalize(part.replace("\"", "").replace("'", "").trim());
            if (ALL.contains(cap)) caps.add(cap);
        }
        return List.copyOf(caps);
    }

    public static String toJson(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return "[]";
        List<String> normalized = new ArrayList<>();
        for (String cap : capabilities) {
            String value = normalize(cap);
            if (ALL.contains(value) && !normalized.contains(value)) normalized.add(value);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(normalized.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    public static Set<String> copyOf(Set<String> caps) {
        return caps == null || caps.isEmpty() ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(caps));
    }
}
