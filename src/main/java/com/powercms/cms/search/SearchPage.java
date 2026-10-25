package com.powercms.cms.search;

import java.util.List;

/**
 * 一页检索结果（FR-SEARCH-004 游标分页）。
 *
 * @param hits       本页命中
 * @param nextCursor 下一页游标；{@code null} 表示本页即最后一页（未满页或末条命中）
 */
public record SearchPage(List<SearchIndexStore.SearchHit> hits, String nextCursor) {

    public static SearchPage empty() {
        return new SearchPage(List.of(), null);
    }
}
