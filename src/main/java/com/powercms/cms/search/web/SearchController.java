package com.powercms.cms.search.web;

import com.powercms.cms.content.ContentSearchTextAssembler;
import com.powercms.cms.search.AdminSearchService;
import com.powercms.cms.search.PublicSearchService;
import com.powercms.cms.search.SearchIndexMappingStateService;
import com.powercms.cms.search.SearchIndexStore;
import com.powercms.cms.search.SearchPage;
import com.powercms.cms.search.SearchQueryResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/sites/{siteId}/search")
class SearchController {
    private final AdminSearchService searchService;
    private final PublicSearchService publicSearchService;
    private final SearchQueryResolver queryResolver;
    private final SearchIndexMappingStateService mappingStateService;

    SearchController(AdminSearchService searchService, PublicSearchService publicSearchService,
                     SearchQueryResolver queryResolver,
                     SearchIndexMappingStateService mappingStateService) {
        this.searchService = searchService;
        this.publicSearchService = publicSearchService;
        this.queryResolver = queryResolver;
        this.mappingStateService = mappingStateService;
    }

    /**
     * 检索请求（FR-SEARCH-004 / AC-CM-009）。
     *
     * @param sort    排序项（**内容字段 code** → {@code asc}/{@code desc}）
     * @param filters 字段级过滤（**内容字段 code** → 取值列表）
     * @param cursor  上一页响应里的 {@code nextCursor}，原样回传
     */
    record SearchRequest(String query, List<String> statuses, List<SearchQueryResolver.SortField> sort,
                         Map<String, List<String>> filters, String cursor) {}

    record SearchResponse(List<AdminSearchService.SearchResult> results, int total) {}

    record SearchHitView(String contentId, String title, String status, Map<String, Object> highlight) {}

    record SearchPageResponse(List<SearchHitView> hits, String nextCursor) {}

    /** 兼容入口：一次性返回命中（不分页、不排序），保持既有调用方语义。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @PostMapping
    SearchResponse search(@PathVariable UUID siteId, @RequestBody SearchRequest req) {
        Set<String> statuses = req.statuses() != null ? new HashSet<>(req.statuses()) : Set.of();
        var results = searchService.search(siteId, req.query(), statuses);
        return new SearchResponse(results, results.size());
    }

    /**
     * 分页检索（FR-SEARCH-004）：排序 + 字段级过滤 + 游标分页 + 属性驱动高亮。
     *
     * <p>字段级排序/过滤在 {@link SearchQueryResolver} 内按内容模型 {@code sortable}/{@code filterable}
     * 属性做白名单校验；未开启的字段一律 400，不静默忽略。
     */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @PostMapping("/query")
    SearchPageResponse query(@PathVariable UUID siteId, @RequestBody SearchRequest req) {
        var query = queryResolver.resolve(siteId, ContentSearchTextAssembler.Target.ADMIN, req.query(),
            req.statuses(), req.filters(), req.sort(), null, req.cursor());
        SearchPage page = searchService.searchPage(siteId, query);
        return new SearchPageResponse(page.hits().stream().map(SearchController::toView).toList(),
            page.nextCursor());
    }

    private static SearchHitView toView(SearchIndexStore.SearchHit hit) {
        Map<String, Object> highlight = new LinkedHashMap<>();
        if (hit.fields().get("highlight") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> highlight.put(String.valueOf(key), value));
        }
        return new SearchHitView(hit.id(), String.valueOf(hit.fields().get("title")),
            hit.fields().get("status") == null ? null : String.valueOf(hit.fields().get("status")),
            highlight.isEmpty() ? null : highlight);
    }

    /**
     * 全量重建管理索引属**系统级写面**（DEF-SEARCH-04）：与「站点包」「媒体服务器」同口径收敛到
     * system-admin。此前为站点 {@code write}，导致 `siteadmin.s1`（具备 {@code admin}/{@code write}
     * 但非 system-admin）**直连** `/search` 可成功提交重建——菜单已隐藏、接口却放行，与
     * TC-M60-022「直接访问时不渲染可成功提交的写控件；提交被拒绝并给出权限说明」冲突。
     */
    @PreAuthorize("hasRole('system-admin')")
    @PostMapping("/rebuild")
    String rebuild(@PathVariable UUID siteId) {
        searchService.indexAll(siteId);
        return "全量索引重建已触发";
    }

    /**
     * 全量重建公开检索索引属**运维写面**，收敛到交互式会话（站点域 `rebuild` 由 @siteAuthorization 收敛）。
     */
    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping("/public/rebuild")
    String rebuildPublic(@PathVariable UUID siteId) {
        publicSearchService.rebuild(siteId);
        return "全量索引重建已触发";
    }

    /**
     * 映射基线状态（FR-MODEL-006 / TC-M66-023）。
     *
     * <p>回答「模型属性改了，索引要重建吗」：`rebuildRequired=true` 表示内容模型已声明的
     * 字段级键形态与索引当前 mapping 不一致，排序/过滤在该键上会失效；重建后自动对齐。
     */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/mapping-state")
    MappingStateResponse mappingState(@PathVariable UUID siteId) {
        List<MappingStateView> views = mappingStateService.states(siteId).stream()
            .map(state -> new MappingStateView(state.target(), state.appliedFingerprint(),
                state.desiredFingerprint(), state.rebuildRequired(),
                state.appliedAt() == null ? null : state.appliedAt().toString()))
            .toList();
        return new MappingStateResponse(views, views.stream().anyMatch(MappingStateView::rebuildRequired));
    }

    record MappingStateView(String target, String appliedFingerprint, String desiredFingerprint,
                            boolean rebuildRequired, String appliedAt) {}
    record MappingStateResponse(List<MappingStateView> targets, boolean rebuildRequired) {}
}
