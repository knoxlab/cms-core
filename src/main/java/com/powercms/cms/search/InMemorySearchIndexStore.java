package com.powercms.cms.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "powercms.search.backend", havingValue = "memory", matchIfMissing = true)
class InMemorySearchIndexStore implements SearchIndexStore {
    private final Map<String, Map<String, SearchDocument>> indexes = new ConcurrentHashMap<>();

    @Override
    public void resetIndex(String index, IndexMapping mapping) {
        // G9-⑩：候选索引为独立 bucket；重置即丢弃同名（残留）候选，语义与 OpenSearch 删后建一致。
        // 保持插入序（LinkedHashMap）：与既有 rebuild 的可见顺序（首 N 条）保持一致。
        indexes.put(index, newBucket());
    }

    @Override
    public void writeBatch(String index, List<SearchDocument> documents) {
        if (documents.isEmpty()) return;
        Map<String, SearchDocument> bucket = indexes.computeIfAbsent(index, ignored -> newBucket());
        documents.forEach(document -> bucket.put(document.id(), document));
    }

    @Override
    public void switchAlias(String alias, String candidateIndex) {
        Map<String, SearchDocument> candidate = indexes.get(candidateIndex);
        if (candidate == null) {
            // 幂等：alias 已持有文档（此前已完成切换）时重复调用为无操作，绝不清空 alias。
            if (indexes.containsKey(alias)) return;
            throw new SearchUnavailableException("候选索引不存在，无法切换 alias: " + candidateIndex);
        }
        if (candidateIndex.equals(alias)) return;
        indexes.put(alias, candidate);
        indexes.remove(candidateIndex);
    }

    @Override
    public boolean indexExists(String index) {
        return indexes.containsKey(index);
    }

    @Override
    public void upsert(String indexOrAlias, SearchDocument document) {
        indexes.computeIfAbsent(indexOrAlias, ignored -> newBucket()).put(document.id(), document);
    }

    /**
     * 范围匹配（{@code FR-AI-KB-045}），与 OpenSearch {@code range} 同语义。
     *
     * <p><b>字段缺失即不命中</b>（与 {@code term}/{@code range} 在 OpenSearch 上的行为一致）：
     * 若把"缺失"当作"满足"，范围过滤在缺字段的文档上会静默失效 ——
     * 表现为"过滤了但结果里混着不该有的条目"，而这比"少返回几条"危险得多。
     *
     * <p>数值与字符串都支持：页码/时间戳是数字，日期可能是 ISO 字符串。
     * 两侧都是数字时按数值比较（避免 {@code "10" < "9"} 这类字典序错误），
     * 否则按字符串比较。
     */
    static boolean matchesRange(Object actual, Map<String, Object> bounds) {
        if (actual == null || bounds == null || bounds.isEmpty()) return false;
        for (var b : bounds.entrySet()) {
            int cmp = compare(actual, b.getValue());
            if (cmp == Integer.MIN_VALUE) return false; // 不可比较 → 不命中（fail-closed）
            switch (b.getKey()) {
                case "gte" -> { if (cmp < 0) return false; }
                case "gt" -> { if (cmp <= 0) return false; }
                case "lte" -> { if (cmp > 0) return false; }
                case "lt" -> { if (cmp >= 0) return false; }
                default -> { return false; } // 未知操作符 → 不命中（不静默放过）
            }
        }
        return true;
    }

    /** 比较两侧；不可比较时返回 {@link Integer#MIN_VALUE} 作为"不可比较"哨兵。 */
    private static int compare(Object a, Object b) {
        Double na = asNumber(a);
        Double nb = asNumber(b);
        if (na != null && nb != null) return Double.compare(na, nb);
        if (a instanceof Number || b instanceof Number) return Integer.MIN_VALUE;
        return a.toString().compareTo(String.valueOf(b));
    }

    private static Double asNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.valueOf(s.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    @Override
    public List<SearchHit> search(String indexOrAlias, String query, Map<String, Object> filters, int limit) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        // FR-AI-KB-047 L3：内部标记不参与字段比较。
        // 若把它当成普通过滤条件，会去找一个不存在的字段 `_titleOnly` → 所有命中被滤掉
        // → 该档恒返回空集（症状是"降级检索什么都找不到"，看起来像数据问题）。
        Map<String, Object> effective = new java.util.LinkedHashMap<>(filters);
        boolean titleOnly = Boolean.TRUE.equals(effective.remove(SearchQuery.TITLE_ONLY_MARK));
        return snapshot(indexOrAlias).stream()
            .filter(document -> effective.entrySet().stream().allMatch(filter -> {
                Object actual = document.fields().get(filter.getKey());
                Object expected = filter.getValue();
                // FR-AI-KB-045：范围过滤（与 OpenSearch range 同语义）。
                // **放在前缀判断之前**：两者都是"字符串编码的过滤"，先判前缀会把
                // "_range:gte:1" 当成前缀值，于是永远匹配不到。
                if (RangeFilter.isRange(expected)) {
                    return matchesRange(actual, RangeFilter.parse(expected));
                }
                // FR-AI-RAG-011：PREFIX_FILTER_MARK 开头 → 前缀匹配（与 OpenSearch prefix 同语义）
                if (expected instanceof String s && s.startsWith(SearchIndexStore.PREFIX_FILTER_MARK)) {
                    String prefix = s.substring(SearchIndexStore.PREFIX_FILTER_MARK.length());
                    return actual != null && actual.toString().startsWith(prefix);
                }
                if (expected instanceof Collection<?> values) {
                    if (values.isEmpty()) return true;
                    // FR-AI-RAG-003/011：terms 语义——多值文档字段与过滤集合有交集即命中
                    // （_visibleGroups 等索引标签为集合，此处与 OpenSearch terms 查询对齐）
                    if (actual instanceof Collection<?> actualValues) {
                        return actualValues.stream().anyMatch(values::contains);
                    }
                    return values.contains(actual);
                }
                return Objects.equals(expected, actual);
            }))
            // 检索域默认 title+body；「仅标题」档只搜 title（与 OpenSearch 的 title-only 同语义）
            .filter(document -> searchable(document.fields().get("title"), needle)
                || (!titleOnly && searchable(document.fields().get("body"), needle)))
            .limit(limit)
            .map(document -> new SearchHit(document.id(), document.fields(), null))
            .toList();
    }

    @Override
    public void delete(String indexOrAlias, String documentId) {
        Map<String, SearchDocument> bucket = indexes.get(indexOrAlias);
        if (bucket != null) bucket.remove(documentId);
    }

    /**
     * DEF-KB-01：纯计数（过滤语义与 {@link #search} 完全一致，共用 {@link #matchesFilters}）。
     * 不经过关键词匹配，故「标题与正文都为空」的文档同样计入——切别名前的合法性校验必须**看到全部条目**。
     */
    @Override
    public long count(String indexOrAlias, Map<String, Object> filters) {
        Map<String, Object> effective = filters == null ? Map.of() : filters;
        return snapshot(indexOrAlias).stream()
            .filter(document -> matchesFilters(document, effective))
            .count();
    }

    /**
     * FR-SEARCH-004 / AC-CM-009：内存后端实现排序 + 游标分页 + 属性驱动高亮，
     * 与 OpenSearch 后端保持**同一语义**（否则单测通过的排序在真实栈上行为不同）。
     *
     * <p>高亮：仅对请求指定的字段（{@code highlightFields}，由内容模型 {@code highlight} 属性驱动）
     * 产出片段，且该字段值必须命中关键词 —— 与「属性未开则不高亮」对齐。
     */
    @Override
    public SearchPage searchPage(String indexOrAlias, SearchQuery query) {
        String needle = query.query() == null ? "" : query.query().toLowerCase(Locale.ROOT);
        // 检索域与高亮域都收口在 SearchQuery 内（title + body + 高亮字段的字段级键），
        // 避免内存后端与 OpenSearch 后端在「哪些字段参与匹配」上分叉。
        List<String> searchFields = query.searchFields();
        List<String> highlightFields = query.effectiveHighlightFields();
        List<SearchDocument> matched = snapshot(indexOrAlias).stream()
            .filter(document -> matchesFilters(document, query.filters()))
            .filter(document -> needle.isBlank() || matchesKeyword(document, searchFields, needle))
            .toList();
        if (!needle.isBlank()) {
            matched = matched.stream()
                .map(document -> withHighlight(document, highlightFields, needle))
                .toList();
        }

        List<SearchQuery.SortSpec> sort = effectiveSort(query);
        List<SearchDocument> ordered = new ArrayList<>(matched);
        if (!sort.isEmpty()) ordered.sort(comparator(sort));

        int size = Math.max(1, query.size());
        List<SearchDocument> page = ordered;
        if (query.hasCursor()) {
            page = ordered.stream().filter(document -> afterCursor(document, sort, query.searchAfter())).toList();
        }
        List<SearchDocument> window = page.size() > size ? page.subList(0, size) : page;
        List<SearchHit> hits = new ArrayList<>();
        for (SearchDocument document : window) {
            hits.add(new SearchHit(document.id(), document.fields(), null,
                sortValuesOf(document, sort)));
        }
        String nextCursor = page.size() <= size ? null
            : SearchCursor.encode(hits.get(hits.size() - 1).sortValues());
        return new SearchPage(hits, nextCursor);
    }

    /** 搜索建议（FR-SEARCH-004）：按字段前缀收集取值，按出现频次降序（与 terms 聚合口径一致）。 */
    @Override
    public List<String> suggest(String indexOrAlias, String field, String prefix, int limit) {        if (field == null || field.isBlank()) return List.of();
        String head = prefix == null ? "" : prefix;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SearchDocument document : snapshot(indexOrAlias)) {
            for (Object value : valuesOf(document.fields().get(field))) {
                String text = String.valueOf(value);
                if (head.isEmpty() || text.startsWith(head)) {
                    counts.merge(text, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(Math.max(1, limit))
            .map(Map.Entry::getKey)
            .toList();
    }

    /** 无显式排序时按「关键词相关度」在内存后端不可复现，退化为稳定 tiebreaker（contentId）序。 */
    private static List<SearchQuery.SortSpec> effectiveSort(SearchQuery query) {
        if (!query.sort().isEmpty()) return query.sort();
        return List.of(new SearchQuery.SortSpec(SearchQuery.TIEBREAKER_FIELD, "asc"));
    }

    private Comparator<SearchDocument> comparator(List<SearchQuery.SortSpec> sort) {
        Comparator<SearchDocument> comparator = null;
        for (SearchQuery.SortSpec spec : sort) {
            Comparator<SearchDocument> next = (a, b) -> compareValues(
                fieldOf(a, spec.field()), fieldOf(b, spec.field()), spec.descending());
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator == null ? (Comparator<SearchDocument>) (a, b) -> 0 : comparator;
    }

    /**
     * 取值比较（与 OpenSearch 缺省 {@code missing} 语义对齐）：asc → null 靠后，desc → null 靠前；
     * 两侧都是数字时按数值比较（否则数值型字段会被当字符串比，出现 "10" &lt; "9" 的错序）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b, boolean descending) {
        if (a == null && b == null) return 0;
        if (a == null) return descending ? -1 : 1;
        if (b == null) return descending ? 1 : -1;
        int result;
        if (a instanceof Number na && b instanceof Number nb) {
            result = Double.compare(na.doubleValue(), nb.doubleValue());
        } else if (a instanceof Comparable ca && a.getClass().equals(b.getClass())) {
            result = ca.compareTo(b);
        } else {
            result = String.valueOf(a).compareTo(String.valueOf(b));
        }
        return descending ? -result : result;
    }

    /** {@code _score} 在内存后端无相关度模型，一律视为相等（顺序由 tiebreaker 决定，保证稳定）。 */
    private static Object fieldOf(SearchDocument document, String field) {
        if ("_score".equals(field)) return null;
        return document.fields().get(field);
    }

    private static List<Object> sortValuesOf(SearchDocument document, List<SearchQuery.SortSpec> sort) {
        List<Object> values = new ArrayList<>();
        sort.forEach(spec -> values.add(fieldOf(document, spec.field())));
        return values;
    }

    /** 游标比较：按 sort 子句逐项比较，严格大于游标值的文档才属于后续页。 */
    private static boolean afterCursor(SearchDocument document, List<SearchQuery.SortSpec> sort,
                                       List<Object> cursor) {
        for (int i = 0; i < sort.size() && i < cursor.size(); i++) {
            int cmp = compareValues(fieldOf(document, sort.get(i).field()), cursor.get(i),
                sort.get(i).descending());
            if (cmp != 0) return cmp > 0;
        }
        return false;
    }

    private static boolean matchesFilters(SearchDocument document, Map<String, Object> filters) {
        return filters.entrySet().stream().allMatch(filter -> {
            Object actual = document.fields().get(filter.getKey());
            Object expected = filter.getValue();
            // FR-AI-RAG-011：PREFIX_FILTER_MARK 开头 → 前缀匹配（与 OpenSearch prefix 同语义）
            if (expected instanceof String s && s.startsWith(SearchIndexStore.PREFIX_FILTER_MARK)) {
                String prefix = s.substring(SearchIndexStore.PREFIX_FILTER_MARK.length());
                return actual != null && actual.toString().startsWith(prefix);
            }
            if (expected instanceof Collection<?> values) {
                if (values.isEmpty()) return true;
                // FR-AI-RAG-003/011：terms 语义——多值文档字段与过滤集合有交集即命中
                if (actual instanceof Collection<?> actualValues) {
                    return actualValues.stream().anyMatch(values::contains);
                }
                return values.contains(actual);
            }
            return Objects.equals(expected, actual);
        });
    }

    /**
     * 属性驱动高亮：只对请求里的字段生成片段（无命中则不产出该字段）。
     *
     * <p>返回**新文档**而不是原地改 {@code fields}：索引文档的 fields 可能是不可变 Map
     * （测试桩、{@code Map.of} 构造），原地写入会抛 {@code UnsupportedOperationException}。
     */
    private static SearchDocument withHighlight(SearchDocument document, List<String> fields, String needle) {
        Map<String, Object> highlight = new LinkedHashMap<>();
        for (String field : fields) {
            for (Object value : valuesOf(document.fields().get(field))) {
                String text = String.valueOf(value);
                if (!text.toLowerCase(Locale.ROOT).contains(needle)) continue;
                // 片段用 <mark> 包裹：与 OpenSearch 的 <em> 同为「片段数组」形态，
                // 前端统一按片段渲染高亮，不因后端不同而分叉。
                highlight.put(field, List.of(markMatches(text, needle)));
                break;
            }
        }
        if (highlight.isEmpty()) return document;
        Map<String, Object> copy = new LinkedHashMap<>(document.fields());
        copy.put("highlight", highlight);
        return new SearchDocument(document.id(), copy);
    }

    /** 命中片段：原文中所有命中处包 {@code <mark>}（大小写不敏感，保留原文大小写）。 */
    private static String markMatches(String text, String needle) {
        if (needle == null || needle.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        String lower = text.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = lower.indexOf(needle, from);
            if (at < 0) { out.append(text, from, text.length()); break; }
            out.append(text, from, at).append("<mark>").append(text, at, at + needle.length()).append("</mark>");
            from = at + needle.length();
        }
        return out.toString();
    }

    private static List<Object> valuesOf(Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            collection.forEach(item -> { if (item != null) out.add(item); });
            return out;
        }
        return List.of(value);
    }

    private boolean searchable(Object value, String needle) {
        return value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** 检索域内任一字段命中即算命中（与 OpenSearch 的 multi_match 多字段语义对齐）。 */
    private boolean matchesKeyword(SearchDocument document, List<String> searchFields, String needle) {
        return searchFields.stream().anyMatch(field -> valuesOf(document.fields().get(field)).stream()
            .anyMatch(value -> searchable(value, needle)));
    }

    /** 插入序 bucket（LinkedHashMap）：resetIndex/writeBatch/upsert 共用，保持与既有 rebuild 相同的可见顺序。 */
    private static Map<String, SearchDocument> newBucket() {
        return Collections.synchronizedMap(new LinkedHashMap<>());
    }

    /** 迭代快照：bucket 为 synchronizedMap，迭代须在锁内完成，避免写入并发导致 CME。 */
    private List<SearchDocument> snapshot(String indexOrAlias) {
        Map<String, SearchDocument> bucket = indexes.get(indexOrAlias);
        if (bucket == null) return List.of();
        synchronized (bucket) {
            return new ArrayList<>(bucket.values());
        }
    }
}
