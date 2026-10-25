package com.powercms.cms.search;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次检索请求的**已校验**查询描述（FR-SEARCH-004：全文 + 过滤 + 排序 + 游标分页 + 高亮）。
 *
 * <p>由 {@link SearchQueryResolver} 从 HTTP 请求体解析并按内容模型属性校验后产出；
 * store 层只负责执行，不再做业务判断。
 *
 * <p><b>字段名口径</b>：{@code sort}/{@code filters}/{@code highlightFields} 里的键都是
 * **索引键**（系统键如 {@code status}/{@code updatedAt}，或字段级键 {@code f_<code>} /
 * {@code f_<code>.kw}），不是内容字段的 {@code code}。code → 索引键的翻译与白名单校验
 * 都在 resolver 内完成，避免「把 code 当索引键」的隐式约定散落各处。
 */
public record SearchQuery(
    String query,
    Map<String, Object> filters,
    List<SortSpec> sort,
    int size,
    List<Object> searchAfter,
    List<String> highlightFields) {

    /** 排序项：{@code field} 为索引键，{@code order} 为 {@code asc}/{@code desc}。 */
    public record SortSpec(String field, String order) {

        public boolean descending() {
            return "desc".equalsIgnoreCase(order);
        }

        /** OpenSearch sort 子句（缺省 asc）。 */
        public Map<String, Object> clause() {
            return Map.of(field, Map.of("order", descending() ? "desc" : "asc"));
        }
    }

    /** 结果列表自身使用的稳定 tiebreaker：无排序时也要有，否则 {@code search_after} 会漏/重。 */
    public static final String TIEBREAKER_FIELD = "contentId";

    /**
     * 默认高亮字段：仅文档标题。
     *
     * <p>刻意**不含** {@code body}：{@code body} 是全部 {@code adminIndex} 字段拍平后的聚合文本，
     * 对它高亮等于「不管字段有没有开 {@code highlight}，其文本都会被高亮」—— 那正是
     * AC-CM-009 落地前与属性语义**相反**的旧行为。字段级高亮改由
     * {@link SearchQueryResolver} 按 {@code highlight=true} 动态生成。
     *
     * <p>RAG 引用片段的职责由 {@code snapshot}（body 前 200 字）继续承载，不依赖 highlight。
     */
    public static final List<String> DEFAULT_HIGHLIGHT_FIELDS = List.of("title");

    /** 生效的高亮字段（空列表 → 默认 {@code title}）。 */
    public List<String> effectiveHighlightFields() {
        return highlightFields.isEmpty() ? DEFAULT_HIGHLIGHT_FIELDS : highlightFields;
    }

    /**
     * 「仅按元数据（标题）检索」的内部标记（{@code FR-AI-KB-047} L3 档）。
     *
     * <p><b>为什么需要这个标记</b>：检索域由 {@link #searchFields()} **派生**，
     * 恒定包含 {@code title} 与 {@code body}，无法通过构造参数收窄。
     * 而 L3「元数据关键词检索」的语义恰恰是**只按文档名/标题匹配**。
     * 早先的实现试图靠"构造一个只带 title 的 SearchQuery"来收窄，
     * 但 {@code highlightFields} 只影响**高亮**、不影响检索域 ——
     * 于是那一档实际仍在搜正文，**"降级为元数据检索"是假的**，
     * 而单测用 mock store 也发现不了（mock 不解析查询语义）。
     *
     * <p>放在 {@code filters} 里借用既有的「内部标记」通道：
     * {@code _embeddingVersion} 已是同样的先例，由 store 在构建过滤子句前剥离。
     */
    public static final String TITLE_ONLY_MARK = "_titleOnly";

    /** 是否为「仅标题」检索（由 store 读取并剥离该标记）。 */
    public boolean titleOnly() {
        Object v = filters.get(TITLE_ONLY_MARK);
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }

    /**
     * 关键词检索域（FR-SEARCH-004 / AC-CM-009）：标题 + 正文 + <b>高亮字段的字段级键</b>。
     *
     * <p><b>为什么高亮字段必须同时进入检索域</b>：高亮是「命中的可视化」，OpenSearch 只为
     * <b>查询实际命中</b>的字段产出片段。只把 {@code f_<code>} 放进 {@code highlight.fields}
     * 而不放进查询字段，{@code highlight=true} 的字段**永远拿不到片段** —— 属性在代码上「已接
     * 上」、用户侧仍看不到任何效果，与本次要修的原始缺陷同一形态（静默无效）。
     *
     * <p>扩大检索域不会泄漏：{@code f_<code>} 键只在字段**已进入该目标索引**时生成
     * （{@link com.powercms.cms.content.ContentIndexSchema#resolve} 的目标闸门），
     * 故 {@code adminIndex}/{@code publicIndex=false} 的文本根本不存在这些键。
     */
    public List<String> searchFields() {
        List<String> out = new ArrayList<>(List.of("title", "body"));
        effectiveHighlightFields().stream()
            .filter(field -> !"title".equals(field))
            .forEach(out::add);
        return List.copyOf(new LinkedHashSet<>(out));
    }

    public SearchQuery {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        sort = sort == null ? List.of() : List.copyOf(sort);
        searchAfter = searchAfter == null ? List.of() : List.copyOf(searchAfter);
        highlightFields = highlightFields == null ? List.of() : List.copyOf(highlightFields);
    }

    /** 无排序/无游标的简单查询（MCP 工具与既有调用点的兼容形态）。 */
    public static SearchQuery of(String query, Map<String, Object> filters, int size) {
        return new SearchQuery(query, filters, List.of(), size, List.of(), List.of());
    }

    public boolean hasCursor() {
        return !searchAfter.isEmpty();
    }

    /**
     * 补上稳定 tiebreaker 后的 sort 子句（OpenSearch 语义：{@code search_after} 必须与 sort 一一对应）。
     *
     * <p>没有 tiebreaker 时，同分/同值文档的相对顺序在不同分片/段之间不稳定，翻页会重复或漏条。
     */
    public List<Map<String, Object>> sortClauses() {
        List<Map<String, Object>> clauses = new java.util.ArrayList<>();
        if (sort.isEmpty()) {
            if (query != null && !query.isBlank()) clauses.add(Map.of("_score", Map.of("order", "desc")));
        } else {
            sort.forEach(spec -> clauses.add(spec.clause()));
        }
        if (sort.stream().noneMatch(spec -> TIEBREAKER_FIELD.equals(spec.field()))) {
            clauses.add(Map.of(TIEBREAKER_FIELD, Map.of("order", "asc")));
        }
        return clauses;
    }

    /** 拷入可变 map 以便 store 层注入内部字段（如 {@code _embeddingVersion}）。 */
    public Map<String, Object> mutableFilters() {
        return new LinkedHashMap<>(filters);
    }
}
