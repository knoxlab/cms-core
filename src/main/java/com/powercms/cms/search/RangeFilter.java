package com.powercms.cms.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 范围过滤（{@code FR-AI-KB-045}）的构造与解析。
 *
 * <p><b>为什么需要它</b>：{@code FR-AI-KB-045} 要求支持「页码范围 / 时间范围 / 文档有效期」，
 * 并在验收标准里明确「过滤器必须走索引侧（{@code term}/{@code terms}/{@code range}/{@code prefix}），
 * **不得先取全量再应用层过滤**」。而检索层此前只实现了 {@code term} / {@code terms} / {@code prefix} ——
 * **`range` 完全没有支持**，因此这三类过滤无处可落。
 *
 * <p><b>为什么"先取全量再应用层过滤"必须禁止</b>：那不是性能问题而是**正确性问题** ——
 * 先取 top-N 再过滤，会把"命中的前 N 条"当作全集，于是**被过滤掉的条目会挤掉本该返回的结果**
 * （例如 10 条命中里有 9 条页码不符，应用层过滤后只剩 1 条，而索引侧过滤本可以返回后面
 * 10 条全都符合的条目）。所以范围过滤只能在查询体里表达。
 *
 * <h3>编码形式</h3>
 * <p>沿用本仓库既有的"内部标记"约定（见 {@link SearchIndexStore#PREFIX_FILTER_MARK}）：
 * 过滤值以 {@code _range:} 开头，后接逗号分隔的边界，例如：
 * <pre>
 *   _range:gte:1,lte:5
 *   _range:gt:100
 *   _range:gte:2026-01-01,lte:2026-12-31
 * </pre>
 * 边界操作符取 OpenSearch 的同名语义（{@code gt}/{@code gte}/{@code lt}/{@code lte}）。
 *
 * <p><b>为什么用字符串编码而不是直接传 Map</b>：过滤条件是
 * {@code Map<String, Object>} 且要经过 {@code SearchQuery} 的不可变拷贝、
 * 前端 JSON 与站点配置多处传递。传嵌套 Map 会让"是不是范围过滤"变成
 * 需要逐层类型判断的事，编码成一个字符串则**判别是单点的**（看前缀），
 * 且与既有的 {@code _prefix:} 完全同形 —— 同一套约定比两套更容易被正确遵守。
 */
public final class RangeFilter {

    private RangeFilter() {}

    /** 范围过滤的标记前缀（与 {@code _prefix:} 同一约定）。 */
    public static final String MARK = "_range:";

    /** 支持的边界操作符（与 OpenSearch range 查询同名）。 */
    private static final List<String> OPS = List.of("gte", "gt", "lte", "lt");

    /**
     * 构造范围过滤值。
     *
     * @param bounds 边界操作符 → 边界值（如 {@code gte → 1}、{@code lte → 5}）；
     *               必须至少一个边界，否则"范围过滤"没有意义（等价于不过滤，
     *               却会让人以为过滤生效了 —— 因此这里直接拒绝）
     */
    public static String of(Map<String, Object> bounds) {
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException("范围过滤必须至少给出一个边界");
        }
        List<String> parts = new ArrayList<>();
        // 按固定顺序输出，保证**同一组边界产生同一字符串** ——
        // 否则缓存键/日志比对会因为 Map 迭代顺序不同而出现"同样的过滤看起来不同"
        for (String op : OPS) {
            Object v = bounds.get(op);
            if (v == null) continue;
            parts.add(op + ":" + v);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("范围过滤的边界操作符非法，只接受 " + OPS);
        }
        return MARK + String.join(",", parts);
    }

    /** {@code gte} ~ {@code lte} 的便捷构造（最常见：页码区间、时间区间）。 */
    public static String between(Object lowerInclusive, Object upperInclusive) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("gte", lowerInclusive);
        b.put("lte", upperInclusive);
        return of(b);
    }

    /** 是否为范围过滤值。 */
    public static boolean isRange(Object value) {
        return value instanceof String s && s.startsWith(MARK);
    }

    /**
     * 解析范围过滤值为 OpenSearch {@code range} 子句的 body。
     *
     * @return 边界操作符 → 边界值；非法输入返回空 Map（调用方据此**不生成子句**
     *         而不是生成一个空 range —— 空 range 在 OpenSearch 里是"匹配所有"，
     *         那会让"过滤条件写错了"表现为"过滤没生效"，比报错更难发现）
     */
    public static Map<String, Object> parse(Object value) {
        if (!isRange(value)) return Map.of();
        String body = ((String) value).substring(MARK.length());
        Map<String, Object> out = new LinkedHashMap<>();
        for (String part : body.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int idx = p.indexOf(':');
            if (idx <= 0) continue;
            String op = p.substring(0, idx).trim();
            String raw = p.substring(idx + 1).trim();
            if (!OPS.contains(op) || raw.isEmpty()) continue;
            out.put(op, coerce(raw));
        }
        return out;
    }

    /**
     * 边界值类型归一：数字串转数字，其余保持字符串。
     *
     * <p>必须归一，否则 {@code gte:"1"}（字符串）在 OpenSearch 的**数值字段**上
     * 会因类型不符而**匹配不到任何文档** —— 表现为"过滤后一条都没有"，
     * 看起来像数据问题。前端 JSON 传过来的数字常常被序列化成字符串。
     */
    private static Object coerce(String raw) {
        try {
            if (raw.contains(".")) return Double.valueOf(raw);
            return Long.valueOf(raw);
        } catch (NumberFormatException ignored) {
            // 日期/时间等非数字边界保持字符串（OpenSearch 按字段类型解析）
            return raw;
        }
    }
}
