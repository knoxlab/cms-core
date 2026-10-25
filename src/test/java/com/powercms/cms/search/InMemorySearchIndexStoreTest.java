package com.powercms.cms.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** G9-⑩：影子索引增量原语（resetIndex / writeBatch / switchAlias / indexExists）的 memory 后端行为。 */
class InMemorySearchIndexStoreTest {

    private final InMemorySearchIndexStore store = new InMemorySearchIndexStore();

    private static SearchIndexStore.SearchDocument doc(String id, String text) {
        return new SearchIndexStore.SearchDocument(id, Map.of("title", text, "body", text));
    }

    @Test
    void resetWriteBatchThenSwitchAliasExposesDocumentsUnderAlias() {
        store.resetIndex("idx-c1", null);
        assertThat(store.indexExists("idx-c1")).isTrue();
        store.writeBatch("idx-c1", List.of(doc("a", "alpha"), doc("b", "beta")));
        store.writeBatch("idx-c1", List.of()); // 空批次为无操作

        assertThat(store.search("idx-c1", "", Map.of(), 10)).hasSize(2);

        store.switchAlias("alias", "idx-c1");
        assertThat(store.indexExists("idx-c1")).isFalse();
        assertThat(store.search("alias", "alpha", Map.of(), 10))
            .extracting(SearchIndexStore.SearchHit::id).containsExactly("a");
    }

    @Test
    void switchAliasIsIdempotentAndReplacesOldIndexContent() {
        store.resetIndex("alias", null);
        store.writeBatch("alias", List.of(doc("old", "legacy")));
        store.resetIndex("cand-2", null);
        store.writeBatch("cand-2", List.of(doc("fresh", "new content")));

        store.switchAlias("alias", "cand-2");
        store.switchAlias("alias", "cand-2"); // 幂等：重复调用不得清空 alias

        assertThat(store.search("alias", "", Map.of(), 10))
            .extracting(SearchIndexStore.SearchHit::id).containsExactly("fresh");
    }

    @Test
    void switchAliasWithoutCandidateThrowsWhenAliasIsEmpty() {
        assertThatThrownBy(() -> store.switchAlias("alias", "missing"))
            .isInstanceOf(SearchUnavailableException.class);
    }

    @Test
    void rebuildDelegatesToIncrementalPrimitives() {
        store.rebuild("alias", "cand-3", List.of(doc("a", "alpha")), null);

        assertThat(store.indexExists("cand-3")).isFalse(); // 已切换为 alias，候选物理索引不再单独存在
        assertThat(store.search("alias", "alpha", Map.of(), 10))
            .extracting(SearchIndexStore.SearchHit::id).containsExactly("a");
    }

    @Test
    void indexExistsIsFalseForUnknownIndex() {
        assertThat(store.indexExists("nope")).isFalse();
    }

    // ---- AC-CM-009：属性驱动的检索域与高亮域 ----

    /**
     * {@code highlight=true} 的字段级键必须**同时**进入检索域与高亮域。
     *
     * <p>回归护栏：OpenSearch 只为「查询实际命中」的字段产出片段。若只声明 {@code highlight.fields}
     * 而不把 {@code f_<code>} 加进查询字段，该属性永远拿不到片段 —— 代码上「接上了」、
     * 用户侧毫无变化，与本次要修的原始缺陷同一形态。内存后端也须同口径，
     * 否则单测绿灯而真实栈无片段。
     */
    @Test
    void highlightFieldIsBothSearchedAndHighlighted() {
        store.resetIndex("idx", null);
        store.writeBatch("idx", List.of(new SearchIndexStore.SearchDocument("a", Map.of(
            "title", "探针条目", "body", "聚合正文", "f_sku_s", "SKU-A1"))));

        // 判别串只存在于字段级键：能命中即可证明它已进入检索域
        SearchQuery query = new SearchQuery("SKU-A1", Map.of(), List.of(), 10, List.of(),
            List.of("title", "f_sku_s"));
        SearchPage page = store.searchPage("idx", query);

        assertThat(page.hits()).extracting(SearchIndexStore.SearchHit::id).containsExactly("a");
        @SuppressWarnings("unchecked")
        Map<String, Object> highlight = (Map<String, Object>) page.hits().get(0).fields().get("highlight");
        assertThat(highlight).containsKey("f_sku_s");
        assertThat(highlight.get("f_sku_s").toString()).contains("<mark>SKU-A1</mark>");
        // body 不在高亮域（属性驱动，不再连带高亮聚合正文）
        assertThat(highlight).doesNotContainKey("body");
    }

    /** 未进入检索域的字段级键不得被命中（属性未开 → 不参与匹配）。 */
    @Test
    void fieldKeyOutsideSearchDomainIsNotMatched() {
        store.resetIndex("idx", null);
        store.writeBatch("idx", List.of(new SearchIndexStore.SearchDocument("a", Map.of(
            "title", "探针条目", "body", "聚合正文", "f_other_s", "ONLY-IN-HERE"))));

        SearchQuery query = new SearchQuery("ONLY-IN-HERE", Map.of(), List.of(), 10, List.of(),
            List.of("title", "f_sku_s"));

        assertThat(store.searchPage("idx", query).hits()).isEmpty();
    }

    // ---- DEF-KB-01：切别名前的候选索引计数闸门 ----

    /**
     * DEF-KB-01：{@code count} 必须与 {@code search} 的过滤语义同口径。
     *
     * <p>这里刻意构造「候选索引里混入 1 条草稿」的真实形态：总量 3、PUBLISHED 2。切别名前的闸门
     * 正是靠这两个计数是否相等来判断「候选索引是否只含已发布内容」，两者口径一旦分叉，闸门形同虚设。
     */
    @Test
    void countAppliesSameFilterSemanticsAsSearch() {
        store.resetIndex("cand", null);
        store.writeBatch("cand", List.of(
            new SearchIndexStore.SearchDocument("p1", Map.of("siteId", "s", "status", "PUBLISHED")),
            new SearchIndexStore.SearchDocument("p2", Map.of("siteId", "s", "status", "PUBLISHED")),
            new SearchIndexStore.SearchDocument("d1", Map.of("siteId", "s", "status", "DRAFT"))));

        assertThat(store.count("cand", Map.of())).isEqualTo(3);
        assertThat(store.count("cand", Map.of("status", "PUBLISHED"))).isEqualTo(2);
        assertThat(store.count("cand", Map.of("siteId", "s", "status", "PUBLISHED"))).isEqualTo(2);
    }

    /** 缺 {@code status} 字段的文档一律按「非发布」计（方向安全：宁可拒绝发布，不可泄露草稿）。 */
    @Test
    void countTreatsMissingStatusAsNotPublished() {
        store.resetIndex("cand", null);
        store.writeBatch("cand", List.of(
            new SearchIndexStore.SearchDocument("p1", Map.of("status", "PUBLISHED")),
            new SearchIndexStore.SearchDocument("x1", Map.of("title", "no status field"))));

        assertThat(store.count("cand", Map.of())).isEqualTo(2);
        assertThat(store.count("cand", Map.of("status", "PUBLISHED"))).isEqualTo(1);
    }

    /** 未知索引按 0 条计（空候选索引＝无内容可泄露，闸门放行而不是因索引缺失报错）。 */
    @Test
    void countUnknownIndexIsZero() {
        assertThat(store.count("nope", Map.of())).isZero();
        assertThat(store.count("nope", null)).isZero();
    }
}
