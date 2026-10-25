package com.powercms.cms.search;

import com.powercms.cms.content.ContentIndexSchema;
import com.powercms.cms.content.ContentSearchTextAssembler;
import com.powercms.cms.content.FieldIndexSpec;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 检索请求解析 + **属性白名单校验**（FR-SEARCH-004 / AC-CM-009）。
 *
 * <p>把 HTTP 请求体里的「字段 code + 排序/过滤意图」翻译成索引键，并按内容模型属性核验：
 * <ul>
 *   <li>排序：字段必须 {@code sortable=true}，否则
 *       {@link ErrorCodes#SEARCH_SORT_UNSUPPORTED}（→ 400）；</li>
 *   <li>过滤：字段必须 {@code filterable=true}，否则
 *       {@link ErrorCodes#SEARCH_FILTER_UNSUPPORTED}（→ 400）；</li>
 *   <li>高亮：由 {@code highlight=true} 的字段**自动生成**，调用方无需（也不能）指定。</li>
 * </ul>
 *
 * <p><b>为什么必须 400 而不是静默忽略</b>：静默忽略与本次要修的原始缺陷同构 —— 用户以为属性
 * 生效、实际没有，只是把「模型侧静默无效」挪成了「查询侧静默无效」。显式拒绝才能让调用方
 * （含 AI Agent，见 {@code HybridSearchTool}）在第一步就发现「这个字段没开可排序」。
 *
 * <p>系统键白名单（与内容模型无关、始终可用）：排序 {@code updatedAt}/{@code currentVersion}；
 * 过滤 {@code status}/{@code categoryId}/{@code contentTypeId}/{@code sourceDomain}。
 */
@Component
public class SearchQueryResolver {

    private static final Logger log = LoggerFactory.getLogger(SearchQueryResolver.class);

    /** 默认页大小与上限：避免一次请求把索引全量拉回。 */
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** 始终可排序的系统键（`contentId` 作为 tiebreaker 由 SearchQuery 自动追加，不接受指定）。 */
    static final Set<String> SORTABLE_SYSTEM_KEYS = Set.of("updatedAt", "currentVersion");

    /** 始终可过滤的系统键。 */
    static final Set<String> FILTERABLE_SYSTEM_KEYS =
        Set.of("status", "categoryId", "contentTypeId", "sourceDomain");

    private final ContentIndexSchema contentIndexSchema;

    /**
     * 生产构造：用 {@code ObjectProvider} 取可选依赖。
     *
     * <p>字段级检索能力在「未接线内容模型 schema」时必须**降级**（系统键仍可用、字段级键按不支持
     * 处理并 400），而不是让整个检索链路启动失败 —— 因此这里必须是可选注入。
     */
    @Autowired
    public SearchQueryResolver(org.springframework.beans.factory.ObjectProvider<ContentIndexSchema> provider) {
        this(provider.getIfAvailable());
    }

    /** 直接注入（单测与显式装配场景）。 */
    public SearchQueryResolver(ContentIndexSchema contentIndexSchema) {
        this.contentIndexSchema = contentIndexSchema;
    }

    /**
     * 解析并校验一次检索请求。
     *
     * @param siteId   目标站点
     * @param target   目标索引（管理/访客）——决定按 {@code adminIndex} 还是 {@code publicIndex} 取字段
     * @param query    关键词（可空 = 纯过滤/排序浏览）
     * @param statuses 状态过滤（既有参数，等价于 {@code filters.status}）
     * @param filters  字段级过滤：**字段 code** → 取值列表
     * @param sort     排序项：**字段 code** → {@code asc}/{@code desc}
     * @param limit    页大小
     * @param cursor   {@code nextCursor} 原样回传
     */
    public SearchQuery resolve(UUID siteId, ContentSearchTextAssembler.Target target, String query,
                               List<String> statuses, Map<String, List<String>> filters,
                               List<SortField> sort, Integer limit, String cursor) {
        ContentIndexSchema.SiteSchema schema = siteSchema(siteId, target);
        Map<String, Object> resolvedFilters = new LinkedHashMap<>();

        if (statuses != null && !statuses.isEmpty()) {
            resolvedFilters.put("status", new ArrayList<>(statuses));
        }
        if (filters != null) {
            filters.forEach((code, values) -> {
                if (code == null || code.isBlank() || values == null || values.isEmpty()) return;
                resolvedFilters.put(resolveFilterKey(code, schema), new ArrayList<>(values));
            });
        }

        List<SearchQuery.SortSpec> resolvedSort = new ArrayList<>();
        if (sort != null) {
            sort.forEach(item -> {
                if (item == null || item.field() == null || item.field().isBlank()) return;
                resolvedSort.add(new SearchQuery.SortSpec(
                    resolveSortKey(item.field(), schema), normalizeOrder(item.order())));
            });
        }
        // 同一字段重复指定排序：保留首个（避免 sort/search_after 数组语义歧义）
        List<SearchQuery.SortSpec> deduped = dedupe(resolvedSort);

        int size = limit == null ? DEFAULT_SIZE : Math.max(1, Math.min(limit, MAX_SIZE));
        return new SearchQuery(query, resolvedFilters, deduped, size,
            SearchCursor.decode(cursor), highlightFields(schema));
    }

    /** 高亮字段：`title` + 每个 {@code highlight=true} 字段的字段级键。 */
    List<String> highlightFields(ContentIndexSchema.SiteSchema schema) {
        List<String> fields = new ArrayList<>(SearchQuery.DEFAULT_HIGHLIGHT_FIELDS);
        if (schema != null) {
            schema.specs().stream()
                .filter(FieldIndexSpec::highlight)
                .map(FieldIndexSpec::key)
                .forEach(fields::add);
        }
        return List.copyOf(new LinkedHashSet<>(fields));
    }

    /** 解析过滤键：系统键放行，字段 code 必须存在且 {@code filterable=true}。 */
    String resolveFilterKey(String code, ContentIndexSchema.SiteSchema schema) {
        if (FILTERABLE_SYSTEM_KEYS.contains(code)) return code;
        FieldIndexSpec spec = schema == null ? null : schema.byCode(code).orElse(null);
        if (spec == null) {
            throw new BusinessException(ErrorCodes.SEARCH_FILTER_UNSUPPORTED,
                "字段「" + code + "」不能作为搜索过滤条件（未开启可过滤或不属于本索引）");
        }
        if (!spec.filterable()) {
            throw new BusinessException(ErrorCodes.SEARCH_FILTER_UNSUPPORTED,
                "字段「" + code + "」未开启「可过滤」，不能作为搜索过滤条件");
        }
        return spec.filterKey();
    }

    /** 解析排序键：系统键放行，字段 code 必须存在且 {@code sortable=true}。 */
    String resolveSortKey(String code, ContentIndexSchema.SiteSchema schema) {
        if (SORTABLE_SYSTEM_KEYS.contains(code)) return code;
        FieldIndexSpec spec = schema == null ? null : schema.byCode(code).orElse(null);
        if (spec == null) {
            throw new BusinessException(ErrorCodes.SEARCH_SORT_UNSUPPORTED,
                "字段「" + code + "」不能作为搜索排序条件（未开启可排序或不属于本索引）");
        }
        if (!spec.sortable()) {
            throw new BusinessException(ErrorCodes.SEARCH_SORT_UNSUPPORTED,
                "字段「" + code + "」未开启「可排序」，不能作为搜索排序条件");
        }
        return spec.sortKey();
    }

    /**
     * 解析搜索建议的字段键（FR-SEARCH-004）。
     *
     * <p>建议基于 **keyword 语义**的键做前缀聚合：只接受可排序或可过滤的字段 ——
     * {@code text} 字段（如 {@code title}/{@code body}）未开 fielddata，聚合会被 OpenSearch 拒绝。
     */
    public String resolveSuggestKey(String code, UUID siteId, ContentSearchTextAssembler.Target target) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCodes.SEARCH_FILTER_UNSUPPORTED, "搜索建议必须指定字段");
        }
        FieldIndexSpec spec = siteSchema(siteId, target).byCode(code).orElse(null);
        if (spec == null || !(spec.filterable() || spec.sortable())) {
            throw new BusinessException(ErrorCodes.SEARCH_FILTER_UNSUPPORTED,
                "字段「" + code + "」不能用于搜索建议（需开启可排序或可过滤）");
        }
        return spec.filterKey();
    }

    /** 供调用方构造可读的「哪些字段可排序/可过滤」提示（前端置灰依据）。 */
    public Map<String, List<String>> capabilities(UUID siteId, ContentSearchTextAssembler.Target target) {
        ContentIndexSchema.SiteSchema schema = siteSchema(siteId, target);
        List<String> sortable = new ArrayList<>(SORTABLE_SYSTEM_KEYS);
        List<String> filterable = new ArrayList<>(FILTERABLE_SYSTEM_KEYS);
        List<String> highlight = new ArrayList<>(SearchQuery.DEFAULT_HIGHLIGHT_FIELDS);
        schema.specs().forEach(spec -> {
            if (spec.sortable()) sortable.add(spec.code());
            if (spec.filterable()) filterable.add(spec.code());
            if (spec.highlight()) highlight.add(spec.code());
        });
        return Map.of("sortable", List.copyOf(sortable),
            "filterable", List.copyOf(filterable),
            "highlight", List.copyOf(highlight));
    }

    /** 排序字段的请求形态（`{field, order}`）。 */
    public record SortField(String field, String order) {}

    private static String normalizeOrder(String order) {
        return "asc".equalsIgnoreCase(order) ? "asc" : "desc";
    }

    private static List<SearchQuery.SortSpec> dedupe(List<SearchQuery.SortSpec> sort) {
        Map<String, SearchQuery.SortSpec> byField = new LinkedHashMap<>();
        sort.forEach(spec -> byField.putIfAbsent(spec.field(), spec));
        return List.copyOf(byField.values());
    }

    /** schema 派生失败（未接线/仓储异常）时降级为空 schema：系统键仍可用，字段级键一律 400。 */
    private ContentIndexSchema.SiteSchema siteSchema(UUID siteId, ContentSearchTextAssembler.Target target) {
        if (contentIndexSchema == null || siteId == null) return ContentIndexSchema.SiteSchema.empty();
        try {
            return contentIndexSchema.siteSchema(siteId, target);
        } catch (RuntimeException ex) {
            log.warn("字段级检索 schema 派生失败 siteId={}，字段级排序/过滤将按不支持处理：{}",
                siteId, ex.getMessage());
            return ContentIndexSchema.SiteSchema.empty();
        }
    }
}
