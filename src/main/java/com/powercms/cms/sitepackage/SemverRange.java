package com.powercms.cms.sitepackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 极简 semver 版本范围匹配（FR-PACKAGE-002「版本不兼容时阻断」的判定核心）。
 *
 * <p>支持语义：空格或逗号分隔为**与**（AND），{@code ||} 为**或**（OR）；
 * 比较符 {@code >= <= > < =} 与 {@code ^ ~}，以及通配 {@code *}、{@code 1.x}、{@code 1.2.x}。
 * 为保持可预测性，预发布后缀（如 {@code -SNAPSHOT}、{@code -rc.1}）在比较时被忽略——
 * 站点包只关心主版本线是否兼容。</p>
 */
final class SemverRange {

    /** 单个比较器：operator 为空表示等值比较。 */
    private record Comparator(String operator, int[] version) {}

    private final List<List<Comparator>> alternatives;
    private final String raw;

    private SemverRange(List<List<Comparator>> alternatives, String raw) {
        this.alternatives = alternatives;
        this.raw = raw;
    }

    /** 原始表达式（用于错误文案）。 */
    String raw() { return raw; }

    /**
     * 解析版本范围表达式；表达式非法时返回 {@code null}（调用方按「非法清单字段」处理）。
     */
    static SemverRange parse(String expr) {
        if (expr == null || expr.isBlank()) return null;
        String normalized = expr.trim();
        List<List<Comparator>> alternatives = new ArrayList<>();
        for (String branch : normalized.split("\\|\\|")) {
            List<Comparator> comparators = new ArrayList<>();
            for (String token : branch.trim().split("[\\s,]+")) {
                if (token.isBlank()) continue;
                List<Comparator> parsed = parseComparator(token.trim());
                if (parsed == null) return null;
                comparators.addAll(parsed);
            }
            if (comparators.isEmpty()) return null;
            alternatives.add(comparators);
        }
        return alternatives.isEmpty() ? null : new SemverRange(alternatives, normalized);
    }

    /** 指定版本是否落在范围内。 */
    boolean admits(String version) {
        int[] candidate = parseVersion(version);
        if (candidate == null) return false;
        for (List<Comparator> branch : alternatives) {
            boolean all = true;
            for (Comparator comparator : branch) {
                if (!matches(candidate, comparator)) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean matches(int[] candidate, Comparator comparator) {
        int cmp = compare(candidate, comparator.version());
        return switch (comparator.operator()) {
            case ">=" -> cmp >= 0;
            case ">" -> cmp > 0;
            case "<=" -> cmp <= 0;
            case "<" -> cmp < 0;
            case "=" -> cmp == 0;
            // ^1.2.3 → [>=1.2.3, <2.0.0)；^0.2.3 → [>=0.2.3, <0.3.0)
            case "^" -> cmp >= 0 && compare(candidate, caretUpperBound(comparator.version())) < 0;
            // ~1.2.3 → [>=1.2.3, <1.3.0)
            case "~" -> cmp >= 0 && compare(candidate, tildeUpperBound(comparator.version())) < 0;
            default -> false;
        };
    }

    private static int[] caretUpperBound(int[] v) {
        int major = v[0];
        if (major > 0) return new int[]{major + 1, 0, 0};
        if (v[1] > 0) return new int[]{0, v[1] + 1, 0};
        return new int[]{0, 0, v[2] + 1};
    }

    private static int[] tildeUpperBound(int[] v) {
        return new int[]{v[0], v[1] + 1, 0};
    }

    /**
     * 解析单个比较器；通配段（{@code 1.x}）展开为上下界两个比较器。
     * 非法表达式返回 {@code null}。
     */
    private static List<Comparator> parseComparator(String token) {
        String operator = "";
        String rest = token;
        if (token.startsWith(">=") || token.startsWith("<=")) {
            operator = token.substring(0, 2);
            rest = token.substring(2);
        } else if (!token.isEmpty() && "><=^~".indexOf(token.charAt(0)) >= 0) {
            operator = token.substring(0, 1);
            rest = token.substring(1);
        }
        if (rest.isBlank()) return null;
        if ("*".equals(rest) || "x".equalsIgnoreCase(rest) || "latest".equalsIgnoreCase(rest)) {
            return List.of(new Comparator(">=", new int[]{0, 0, 0}));
        }
        String[] parts = rest.split("\\.");
        int[] version = new int[]{0, 0, 0};
        for (int i = 0; i < parts.length && i < 3; i++) {
            String part = parts[i];
            if ("x".equalsIgnoreCase(part) || "*".equals(part)) {
                if (!operator.isEmpty() && !"=".equals(operator)) {
                    // >=1.x / <2.x 一类：按已知前缀下界比较即可
                    return List.of(new Comparator(operator, version));
                }
                // 1.x / 1.2.x：等价于 [>=下界, <上界)，上界为通配段的前一段进位
                if (i == 0) {
                    return List.of(new Comparator(">=", version));
                }
                int[] upper = i == 1
                    ? new int[]{version[0] + 1, 0, 0}
                    : new int[]{version[0], version[1] + 1, 0};
                return List.of(new Comparator(">=", version), new Comparator("<", upper));
            }
            if (!part.chars().allMatch(Character::isDigit)) return null;
            version[i] = Integer.parseInt(part);
        }
        return List.of(new Comparator(operator.isEmpty() ? "=" : operator, version));
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int diff = Integer.compare(a[i], b[i]);
            if (diff != 0) return diff;
        }
        return 0;
    }

    /** 解析版本号（忽略预发布与构建元数据），非法返回 {@code null}。 */
    static int[] parseVersion(String version) {
        if (version == null || version.isBlank()) return null;
        String core = version.trim().toLowerCase(Locale.ROOT);
        int cut = core.length();
        for (char c : new char[]{'-', '+'}) {
            int idx = core.indexOf(c);
            if (idx >= 0 && idx < cut) cut = idx;
        }
        core = core.substring(0, cut);
        String[] parts = core.split("\\.");
        int[] out = new int[]{0, 0, 0};
        for (int i = 0; i < parts.length && i < 3; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            if (!part.chars().allMatch(Character::isDigit)) return null;
            out[i] = Integer.parseInt(part);
        }
        return out;
    }
}
