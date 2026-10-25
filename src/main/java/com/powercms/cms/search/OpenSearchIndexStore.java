package com.powercms.cms.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
@ConditionalOnProperty(name = "powercms.search.backend", havingValue = "opensearch")
class OpenSearchIndexStore implements SearchIndexStore {
    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexStore.class);

    /** mapping 生成的编码器（静态入口 {@link #mappingJson} 无实例可依，单测直接校验同一份逻辑）。 */
    private static final ObjectMapper MAPPING_MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration requestTimeout;
    /** 中文检索 analyzer（R4 预留）：默认 standard；部署 ik/ICU 后配 ik_max_word/icu_analyzer 并全量重建。 */
    private final String analyzer;

    OpenSearchIndexStore(ObjectMapper mapper,
                         @Value("${powercms.search.opensearch-uri:http://localhost:9200}") URI endpoint,
                         @Value("${powercms.search.connect-timeout:2s}") Duration connectTimeout,
                         @Value("${powercms.search.request-timeout:5s}") Duration requestTimeout,
                         @Value("${powercms.search.analyzer:standard}") String analyzer) {
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.analyzer = (analyzer == null || analyzer.isBlank()) ? "standard" : analyzer;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public void resetIndex(String index, IndexMapping mapping) {
        // G9-⑩：删除同名（残留）物理索引后按 mapping 重建——断点续跑复用候选索引名时不会误建空索引。
        send("DELETE", "/" + index, "", 200, 404);
        send("PUT", "/" + index, mappingJson(mapping), 200, 201);
    }

    /**
     * 分批写入候选索引（G9-⑩）：按 500 条一批 bulk，避免单次 payload 超过 OpenSearch 限制（默认 100MB）；
     * 写后 refresh 保证「写后即可切换/可查」。**校验 bulk errors**：任一条失败即抛错，避免残缺索引被切换。
     */
    @Override
    public void writeBatch(String index, List<SearchDocument> documents) {
        if (documents.isEmpty()) return;
        for (int from = 0; from < documents.size(); from += 500) {
            int to = Math.min(from + 500, documents.size());
            String body = send("POST", "/_bulk?refresh=false", bulk(index, documents.subList(from, to)), 200);
            requireBulkOk(body);
        }
        send("POST", "/" + index + "/_refresh", "", 200);
    }

    /**
     * 候选索引原子切换为 alias 并清理被取代的旧物理索引（G9-⑩）。
     * **幂等**：alias 已只指向候选索引时直接返回（不得再执行 DELETE，否则会删除在线索引）。
     */
    @Override
    public void switchAlias(String alias, String candidateIndex) {
        List<String> oldIndexes = aliasIndexes(alias);
        if (oldIndexes.size() == 1 && candidateIndex.equals(oldIndexes.get(0))) return;
        List<Map<String, Object>> actions = new ArrayList<>();
        oldIndexes.forEach(index -> actions.add(Map.of("remove", Map.of("index", index, "alias", alias))));
        actions.add(Map.of("add", Map.of("index", candidateIndex, "alias", alias)));
        try {
            send("POST", "/_aliases", mapper.writeValueAsString(Map.of("actions", actions)), 200);
        } catch (JsonProcessingException e) {
            throw new SearchUnavailableException("Unable to encode OpenSearch alias request", e);
        }
        oldIndexes.forEach(index -> {
            try { send("DELETE", "/" + index, "", 200); }
            catch (SearchUnavailableException ex) { log.warn("Could not delete superseded search index {}", index, ex); }
        });
    }

    @Override
    public boolean indexExists(String index) {
        try {
            HttpResponse<String> response = client.send(request("HEAD", "/" + index, null),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return true;
            if (response.statusCode() == 404) return false;
            throw new SearchUnavailableException("OpenSearch returned " + response.statusCode() + " on HEAD /" + index);
        } catch (IOException e) {
            throw new SearchUnavailableException("OpenSearch request failed: HEAD /" + index, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchUnavailableException("OpenSearch request interrupted: HEAD /" + index, e);
        }
    }

    @Override
    public void upsert(String indexOrAlias, SearchDocument document) {
        ensureWritableIndex(indexOrAlias);
        try { send("PUT", "/" + indexOrAlias + "/_doc/" + document.id() + "?refresh=true",
            mapper.writeValueAsString(serializeDocument(document)), 200, 201); }
        catch (JsonProcessingException e) { throw new SearchUnavailableException("Unable to encode search document", e); }
    }

    @Override
    public List<SearchHit> search(String indexOrAlias, String query, Map<String, Object> filters, int limit) {
        return doSearch(indexOrAlias, SearchQuery.of(query, filters, limit)).hits();
    }

    /**
     * DEF-KB-01：纯计数（过滤语义与 {@link #search} 一致，走 {@code _count}）。
     *
     * <p>用 {@code _count} 而不是 {@code _search} + {@code size=0}：切别名前的校验只关心条数，
     * 不需要命名空间里的文档体，也不需要 sort/tiebreaker（计数与排序无关）。
     */
    @Override
    public long count(String indexOrAlias, Map<String, Object> filters) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> filterClauses = filterClauses(filters);
        body.put("query", filterClauses.isEmpty()
            ? Map.of("match_all", Map.of())
            : Map.of("bool", Map.of("must", filterClauses)));
        try {
            JsonNode response = mapper.readTree(send("POST", "/" + indexOrAlias + "/_count", body, 200));
            return response.path("count").asLong(0);
        } catch (JsonProcessingException e) {
            throw new SearchUnavailableException("Invalid OpenSearch count response", e);
        }
    }

    /** FR-SEARCH-004 / AC-CM-009：按 sort 子句排序 + search_after 游标 + 属性驱动高亮。 */
    @Override
    public SearchPage searchPage(String indexOrAlias, SearchQuery query) {
        return doSearch(indexOrAlias, query);
    }

    @Override
    public void delete(String indexOrAlias, String documentId) {
        ensureWritableIndex(indexOrAlias);
        send("DELETE", "/" + indexOrAlias + "/_doc/" + documentId + "?refresh=true", "", 200, 404);
    }

    /**
     * 搜索建议（FR-SEARCH-004）：前缀查询 + terms 聚合。
     *
     * <p>聚合只允许落在 **keyword 语义**的键上（字段级排序/过滤键，或系统 keyword 键）：
     * {@code text} 字段未开 fielddata，直接聚合会返回 OpenSearch 错误，因此白名单由
     * {@link SearchQueryResolver} 在入口处收口，本方法假定 {@code field} 已合法。
     */
    @Override
    public List<String> suggest(String indexOrAlias, String field, String prefix, int limit) {
        if (field == null || field.isBlank()) return List.of();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", 0);
        body.put("query", Map.of("prefix", Map.of(field, prefix == null ? "" : prefix)));
        body.put("aggs", Map.of("suggestions", Map.of("terms", Map.of(
            "field", field, "size", Math.max(1, limit), "order", Map.of("_count", "desc")))));
        try {
            JsonNode response = mapper.readTree(send("POST", "/" + indexOrAlias + "/_search", body, 200));
            List<String> out = new ArrayList<>();
            response.path("aggregations").path("suggestions").path("buckets")
                .forEach(bucket -> out.add(bucket.path("key").asText()));
            return out;
        } catch (JsonProcessingException e) {
            throw new SearchUnavailableException("Invalid OpenSearch suggest response", e);
        }
    }

    /**
     * 过滤条件 → OpenSearch 子句（term / terms / prefix / range）。
     *
     * <p>检索与计数**共用同一实现**：两处若各写一份，计数就会与检索在集合型过滤
     * （{@code terms} / {@code _visibleGroups}）或前缀/范围过滤上悄悄分叉，而「计数」恰恰是
     * 切别名这类不可回退决策的判据（DEF-KB-01）。
     */
    private static List<Map<String, Object>> filterClauses(Map<String, Object> filters) {
        List<Map<String, Object>> clauses = new ArrayList<>();
        if (filters == null) return clauses;
        filters.forEach((field, value) -> {
            if (field == null) return; // 内部保留键（如 _embeddingVersion）不应参与过滤
            // FR-AI-KB-045：范围过滤（页码范围 / 时间范围 / 有效期）。
            // **放在前缀判断之前**是刻意的：两者都是"字符串编码的过滤"，先判前缀会让
            // "_range:" 被当成前缀值拼成 `prefix: {field: "_range:gte:1"}` ——
            // 那是一个永远匹配不到的前缀，于是"过滤后一条都没有"，看起来像数据问题。
            if (RangeFilter.isRange(value)) {
                Map<String, Object> body = RangeFilter.parse(value);
                // 边界全部非法时**不生成子句**而不是生成空 range：
                // 空 range 在 OpenSearch 里等价于"匹配所有"，会让"过滤条件写错了"
                // 表现为"过滤没生效"——比报错更难发现。
                if (!body.isEmpty()) clauses.add(Map.of("range", Map.of(field, body)));
            } else if (value instanceof String s && s.startsWith(SearchIndexStore.PREFIX_FILTER_MARK)) {
                clauses.add(Map.of("prefix", Map.of(field, s.substring(SearchIndexStore.PREFIX_FILTER_MARK.length()))));
            } else if (value instanceof Collection<?> values && !values.isEmpty()) {
                clauses.add(Map.of("terms", Map.of(field, values)));
            } else if (!(value instanceof Collection<?>)) {
                clauses.add(Map.of("term", Map.of(field, value)));
            }
        });
        return clauses;
    }

    /**
     * 检索查询体（FR-AI-RAG-002 / AC-CM-009）。
     *
     * <p><b>融合口径</b>：有 queryVector 时 BM25 与 k-NN 融合为 {@code bool.should}，<b>两条腿各自带权重</b>
     * （{@code multi_match.boost = bm25Weight}、{@code knn.<field>.boost = knnWeight}）；过滤条件恒放
     * {@code bool.must}，保证站点/状态/ACL({@code _visibleGroups}) 始终生效（FR-AI-RAG-003）。
     * 无向量时退化为纯 BM25（关键词与过滤合并进 {@code bool.must}）。
     *
     * <p><b>权重语义须知</b>：两条腿的分数量纲不同——BM25 是未归一化的相关性分（可 &gt; 1），k-NN 是
     * {@code cosinesimil} 下的相似度（落在 {@code (0,1]}）。因此站点策略里的权重比**不**等于排序名次比，
     * 它只是在同一打分空间内缩放两条腿；`knnWeight` 调到 &gt; 1 才能让向量腿在典型 BM25 命中前抬头。
     *
     * <p>独立成包内方法以便直接断言生成的 JSON（同 {@code mappingJson} 的口径：校验真实生成逻辑，
     * 避免测试里手抄一份查询体而与实现漂移）。
     */
    static Map<String, Object> queryBody(List<Map<String, Object>> filterClauses, String keywordQuery,
                                        List<String> searchFields, int limit, String analyzer) {
        // 纯 BM25：关键词 multi_match + 过滤条件合并进 bool.must
        List<Map<String, Object>> must = new ArrayList<>(filterClauses == null ? List.of() : filterClauses);
        if (keywordQuery != null && !keywordQuery.isBlank()) {
            must.add(Map.of("multi_match", Map.of("query", keywordQuery, "fields", searchFields,
                "analyzer", analyzer)));
        }
        return Map.of("bool", Map.of("must", must));
    }

    private SearchPage doSearch(String indexOrAlias, SearchQuery query) {
        // 复制为可变 map 以支持删除内部字段（filters 可能是不可变 Map.of 视图）
        Map<String, Object> effectiveFilters = query.mutableFilters();
        // FR-AI-KB-047 L3：剥离「仅标题」内部标记。
        // **必须在 filterClauses(...) 之前剥离**：否则它会变成一个真实的 term 过滤条件
        // （索引里没有名为 _titleOnly 的字段 → 所有命中被滤掉 → 该档恒返回空集，
        // 而表面症状是"降级检索什么都找不到"，看起来像数据问题而不是实现问题）。
        boolean titleOnly = Boolean.TRUE.equals(effectiveFilters.remove(SearchQuery.TITLE_ONLY_MARK));
        // 非关键词的过滤条件（siteId/status/categoryId/_visibleGroups 等 → term/terms）。
        // FR-AI-RAG-011：以 PREFIX_FILTER_MARK 开头的过滤值 → prefix 查询（sourceDomain 前缀路由）。
        // AC-CM-009：字段级过滤键（f_<code> / f_<code>.kw）由 SearchQueryResolver 校验后进入同一机制。
        List<Map<String, Object>> filterClauses = filterClauses(effectiveFilters);
        String keywordQuery = query.query();
        int limit = query.size();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("size", limit);
            // 排序 + 稳定 tiebreaker（FR-SEARCH-004）；search_after 必须与 sort 数组一一对应
            body.put("sort", query.sortClauses());
            if (query.hasCursor()) body.put("search_after", query.searchAfter());
            // FR-AI-RAG-004 + AC-CM-009：高亮字段由 highlight 属性驱动（不再把整段 body 当高亮域，
            // 否则 highlight=false 的字段会随 body 被连带高亮 —— 那是与属性语义相反的旧行为）。
            // 检索域同步扩到高亮字段的字段级键：OpenSearch 只为**命中过的字段**产出片段，
            // 只声明 highlight.fields 而不搜这些键 = 该属性永不生效（见 SearchQuery#searchFields）。
            List<String> searchFields = query.searchFields();
            List<String> highlightFields = query.effectiveHighlightFields();
            if (keywordQuery != null && !keywordQuery.isBlank() && !highlightFields.isEmpty()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                highlightFields.forEach(field -> fields.put(field, "title".equals(field)
                    ? Map.of("number_of_fragments", 0)
                    : Map.of("fragment_size", 200, "number_of_fragments", 1)));
                body.put("highlight", Map.of("fields", fields));
            }

            body.put("query", queryBody(filterClauses, keywordQuery, titleOnly ? List.of("title") : searchFields,
                limit, analyzer));
            JsonNode response = mapper.readTree(send("POST", "/" + indexOrAlias + "/_search", body, 200));
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode hit : response.path("hits").path("hits")) {
                Map<String, Object> source = mapper.convertValue(hit.path("_source"), Map.class);
                Double score = hit.path("_score").isMissingNode() || hit.path("_score").isNull()
                    ? null : hit.path("_score").asDouble();
                // FR-SEARCH-004：抽取 OpenSearch 返回的 highlight（按属性驱动的字段列表）。
                // 必须是**片段数组**（JSON 数组），不能 toString() 成 "[a,b]" 这样的单串 ——
                // 前端要把每个片段分别渲染成 <mark>，字符串化会让「高亮」降级成一段原始 JSON。
                if (hit.has("highlight")) {
                    Map<String, Object> highlight = new LinkedHashMap<>();
                    hit.path("highlight").fields().forEachRemaining(entry -> {
                        List<String> fragments = new ArrayList<>();
                        JsonNode node = entry.getValue();
                        if (node.isArray()) node.forEach(fragment -> fragments.add(fragment.asText()));
                        else if (!node.isNull()) fragments.add(node.asText());
                        if (!fragments.isEmpty()) highlight.put(entry.getKey(), List.copyOf(fragments));
                    });
                    if (!highlight.isEmpty()) source.put("highlight", highlight);
                }
                // FR-AI-RAG-004：snapshot 引用文本（截取 body 200 字符）
                Object bodyText = source.get("body");
                if (bodyText != null) {
                    String s = bodyText.toString();
                    source.put("snapshot", s.length() > 200 ? s.substring(0, 200) + "…" : s);
                }
                hits.add(new SearchHit(hit.path("_id").asText(), source, score,
                    sortValuesOf(hit)));
            }
            // 游标仅在「本页已满」时下发：未满页即已到末尾，再给游标只会多一次空往返
            String nextCursor = hits.size() < limit ? null
                : SearchCursor.encode(hits.get(hits.size() - 1).sortValues());
            return new SearchPage(hits, nextCursor);
        } catch (JsonProcessingException e) {
            throw new SearchUnavailableException("Invalid OpenSearch response", e);
        }
    }

    /** 读取 OpenSearch 回传的 {@code sort} 取值数组（游标分页的输入）。 */
    private List<Object> sortValuesOf(JsonNode hit) {
        JsonNode sort = hit.path("sort");
        if (sort.isMissingNode() || sort.isNull() || !sort.isArray()) return List.of();
        List<Object> values = new ArrayList<>();
        sort.forEach(node -> values.add(mapper.convertValue(node, Object.class)));
        return values;
    }

    private Map<String, Object> serializeDocument(SearchDocument document) {
        return new LinkedHashMap<>(document.fields());
    }

    private String mappingJson(IndexMapping mapping) {
        return mappingJson(mapping, analyzer);
    }

    /**
     * 索引 mapping 生成（静态、无实例依赖，供单元测试直接校验；与实例方法保持一致）。
     *
     * <p>{@code analyzer} 作用于 title/body 及字段级 text 键（R4：默认 standard，中文生产可配
     * ik_max_word 等，变更后必须触发全量重建 {@code rebuild} 使新 mapping 生效）。
     *
     * <p><b>AC-CM-009 / FR-MODEL-006</b>：{@code mapping.fieldSpecs()} 里的每个字段按其在内容模型
     * 中开启的属性生成独立键 —— {@code keyword}/{@code long}/{@code double}/{@code boolean}/
     * {@code date} 直接对应 OpenSearch 类型；{@code text} 追加 {@code fields.kw}（keyword 子字段）
     * 以同时满足「高亮需分词」与「排序/过滤需精确值」，见
     * {@link com.powercms.cms.content.FieldIndexSpec#hasKeywordSubfield()}。
     *
     * @param mapping 为 null 时返回旧版纯 BM25 mapping（纯关键词降级路径，不含字段级键）；
     *                {@code embeddingDimensions() <= 0} 表示「有字段级键、无向量字段」——
     *                字段级键照常声明，只是不建 {@code embedding} 与 {@code index.knn}
     */
    static String mappingJson(IndexMapping mapping, String analyzer) {
        String a = (analyzer == null || analyzer.isBlank()) ? "standard" : analyzer;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", analyzedTextField(a));
        properties.put("body", analyzedTextField(a));
        properties.put("status", keywordField());
        properties.put("siteId", keywordField());
        properties.put("categoryId", keywordField());
        properties.put("contentTypeId", keywordField());
        // FR-SEARCH-004：系统排序/过滤键必须**显式声明**，否则查询层一用就 400
        // （OpenSearch 对未映射字段排序直接报 "No mapping found for [x] in order to sort on"）。
        // contentId 是游标分页的稳定 tiebreaker（SearchQuery.TIEBREAKER_FIELD）——
        // 每次检索（哪怕不含任何排序）都会追加它，漏了它等于把整条检索链路打断。
        // updatedAt / currentVersion 是 SearchQueryResolver 声明的「始终可排序」系统键，
        // 声明在这里才与能力清单一致（此前能力清单承诺可排序、映射里却没有该键）。
        properties.put("contentId", keywordField());
        properties.put("updatedAt", dateField());
        properties.put("currentVersion", longField());
        // FR-SEARCH-003：访客索引文档必须带 releaseId / contentVersion（口径追溯），
        // 显式声明类型避免动态推断：UUID 串被 standard 分词器按 '-' 切开后，
        // 任何 releaseId 等值过滤/聚合都会静默失配（与 siteId 同理必须 keyword）。
        properties.put("releaseId", keywordField());
        properties.put("contentVersion", longField());

        // AC-CM-009：字段级键的声明**先于**「有无向量能力」的分叉 —— 两条降级路径
        // （mapping == null 的纯 BM25 路径 / dimensions=0 的无向量但仍有字段级键路径）都可能带着
        // specs。早期实现把 specs 放在唯一 vector 分支里，使 dimensions=0 的部署静默丢掉全部
        // f_* 声明，只能由 OpenSearch 动态推断类型（数值键被推成 text，排序变字典序）。
        if (mapping != null) {
            mapping.fieldSpecs().forEach(spec -> properties.put(spec.key(), fieldMapping(spec, a)));
        }

        // FR-AI-SRC-003：来源域标注为 keyword（多源检索的路由过滤键）。
        properties.put("sourceDomain", keywordField());

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", 1);
        return encodeMapping(settings, properties);
    }

    /** 字段级键的 mapping 片段（AC-CM-009）。 */
    private static Map<String, Object> fieldMapping(
        com.powercms.cms.content.FieldIndexSpec spec, String analyzer) {
        if ("text".equals(spec.type())) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("analyzer", analyzer);
            if (spec.hasKeywordSubfield()) {
                Map<String, Object> kw = new LinkedHashMap<>();
                kw.put("type", "keyword");
                // 超长值不进入 keyword 子字段（排序/过滤取不到），换取索引体积可控
                kw.put("ignore_above", com.powercms.cms.content.FieldIndexSpec.KEYWORD_IGNORE_ABOVE);
                text.put("fields", Map.of(com.powercms.cms.content.FieldIndexSpec.KEYWORD_SUBFIELD, kw));
            }
            return text;
        }
        return Map.of("type", spec.type());
    }

    private static Map<String, Object> analyzedTextField(String analyzer) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "text");
        field.put("analyzer", analyzer);
        return field;
    }

    private static Map<String, Object> keywordField() {
        return Map.of("type", "keyword");
    }

    /** 系统排序键 {@code updatedAt}：OpenSearch 需显式声明为 date 才能排序。 */
    private static Map<String, Object> dateField() {
        return Map.of("type", "date");
    }

    /** 系统排序键 {@code currentVersion}。 */
    private static Map<String, Object> longField() {
        return Map.of("type", "long");
    }

    private static String encodeMapping(Map<String, Object> settings, Map<String, Object> properties) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settings", settings);
        body.put("mappings", Map.of("properties", properties));
        try {
            return MAPPING_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to encode OpenSearch index mapping", e);
        }
    }

    private void ensureWritableIndex(String alias) {
        if (!aliasIndexes(alias).isEmpty()) return;
        String physical = alias + "-bootstrap-" + System.currentTimeMillis();
        rebuild(alias, physical, List.of(), null);
    }

    private List<String> aliasIndexes(String alias) {
        HttpRequest request = request("GET", "/_alias/" + alias, null);
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return List.of();
            requireStatus(response, 200);
            JsonNode root = mapper.readTree(response.body());
            List<String> names = new ArrayList<>();
            root.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (IOException e) { throw new SearchUnavailableException("OpenSearch request failed", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SearchUnavailableException("OpenSearch request interrupted", e); }
    }

    private String bulk(String index, List<SearchDocument> documents) {
        StringBuilder body = new StringBuilder();
        try {
            for (SearchDocument document : documents) {
                body.append(mapper.writeValueAsString(Map.of("index", Map.of("_index", index, "_id", document.id())))).append('\n');
                body.append(mapper.writeValueAsString(serializeDocument(document))).append('\n');
            }
            return body.toString();
        } catch (JsonProcessingException e) { throw new SearchUnavailableException("Unable to encode bulk request", e); }
    }

    /**
     * bulk 响应错误校验（G9-⑩）：OpenSearch 对单条失败默认返回 200 + {@code errors:true}，
     * 不校验会把「部分写入成功」的影子索引当成完整索引切换上线。此处任一条失败即抛错（含首个失败原因）。
     */
    private void requireBulkOk(String responseBody) {
        try {
            JsonNode response = mapper.readTree(responseBody);
            if (!response.path("errors").asBoolean(false)) return;
            for (JsonNode item : response.path("items")) {
                JsonNode error = item.path("index").path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    throw new SearchUnavailableException("OpenSearch bulk 写入失败 _id="
                        + item.path("index").path("_id").asText() + " reason="
                        + error.path("reason").asText(error.toString()));
                }
            }
            throw new SearchUnavailableException("OpenSearch bulk 写入失败（errors=true，未定位到具体条目）");
        } catch (JsonProcessingException e) {
            throw new SearchUnavailableException("Invalid OpenSearch bulk response", e);
        }
    }

    private String send(String method, String path, String body, int... expected) {
        try {
            HttpResponse<String> response = client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
            requireStatus(response, expected);
            return response.body();
        } catch (IOException e) { throw new SearchUnavailableException("OpenSearch request failed: " + method + " " + path, e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SearchUnavailableException("OpenSearch request interrupted", e); }
    }

    private String send(String method, String path, Map<String, Object> body, int... expected) {
        try {
            String encoded = mapper.writeValueAsString(body);
            return send(method, path, encoded, expected);
        } catch (JsonProcessingException e) {
            throw new SearchUnavailableException("Unable to encode request body", e);
        }
    }

    private HttpRequest request(String method, String path, String body) {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return HttpRequest.newBuilder(endpoint.resolve(path)).timeout(requestTimeout).header("Content-Type", "application/json").method(method, publisher).build();
    }

    private void requireStatus(HttpResponse<String> response, int... expected) {
        if (Arrays.stream(expected).noneMatch(code -> code == response.statusCode()))
            throw new SearchUnavailableException("OpenSearch returned " + response.statusCode() + ": " + response.body());
    }
}
