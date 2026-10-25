package com.powercms.cms.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L3 元数据档（{@code FR-AI-KB-047}）必须**真的**只按标题匹配。
 *
 * <p>本类用**真实**的 {@link InMemorySearchIndexStore}（实现与 OpenSearch 同语义的检索域）
 * 而不是 mock，因为要暴露的缺陷恰恰是"检索域没有真的收窄"——
 * 而 mock store 不解析查询语义，因此**永远发现不了**它。
 *
 * <p><b>被暴露的真实缺陷</b>：早先的 L3 实现用"构造一个只带 {@code title} 的
 * {@code highlightFields} 的 SearchQuery"来收窄检索域。但检索域由
 * {@code SearchQuery#searchFields()} **派生**，恒定包含 {@code title} 与 {@code body}；
 * {@code highlightFields} 只影响**高亮**。于是那一档实际仍在搜正文 ——
 * **"已降级为按文档名/标题匹配"是假的**，而提示语还明确写着"无法检索正文"。
 * <p>这正是本仓库反复出现的同一类缺陷：**过滤/限定条件写了但没生效，且完全静默**。
 */
class MetadataFallbackTitleScopeTest {

    private static final String INDEX = "idx";

    private InMemorySearchIndexStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySearchIndexStore();
    }

    private void put(String id, String title, String body) {
        store.upsert(INDEX, new SearchIndexStore.SearchDocument(id,
            Map.of("title", title, "body", body)));
    }

    /** 与 L3 档完全一致的查询构造（内部标记 + 原过滤条件）。 */
    private static SearchQuery titleOnlyQuery(String q, Map<String, Object> extraFilters) {
        Map<String, Object> filters = new LinkedHashMap<>(extraFilters);
        filters.put(SearchQuery.TITLE_ONLY_MARK, Boolean.TRUE);
        return new SearchQuery(q, filters, null, 10, null, null);
    }

    private static SearchQuery fullQuery(String q, Map<String, Object> extraFilters) {
        return new SearchQuery(q, new LinkedHashMap<>(extraFilters), null, 10, null, null);
    }

    @Test
    @DisplayName("关键词只出现在正文 → 元数据档必须**找不到**（证明检索域真的收窄了）")
    void bodyOnlyMatchIsNotFoundInMetadataLevel() {
        put("d1", "设备手册", "拧紧力矩为 45 N·m。");

        // 全字段检索能找到（正文命中）
        assertThat(store.search(INDEX, "拧紧力矩", new HashMap<>(), 10))
            .as("前提：全字段检索本来能找到这条，否则本用例证明不了收窄")
            .hasSize(1);

        // 元数据档必须找不到 —— 若这里仍能找到，说明"只搜标题"没有生效，
        // 而提示语却告诉用户"无法检索正文"，两者矛盾
        assertThat(store.search(INDEX, "拧紧力矩", Map.of(SearchQuery.TITLE_ONLY_MARK, true), 10))
            .as("元数据档只搜标题：正文命中的内容在这一档必须找不到，"
                + "否则「已降级为按文档名/标题匹配」是假的")
            .isEmpty();
    }

    @Test
    @DisplayName("关键词出现在标题 → 元数据档能命中（这一档的价值所在）")
    void titleMatchIsFoundInMetadataLevel() {
        put("d1", "安装扭矩手册", "与本查询无关的正文。");

        assertThat(store.search(INDEX, "安装扭矩", Map.of(SearchQuery.TITLE_ONLY_MARK, true), 10))
            .as("索引挂了也要能按文件名找到文档，否则这一档没有存在意义")
            .hasSize(1);
    }

    /**
     * 内部标记**不得**变成真实的字段过滤条件。
     *
     * <p>若 store 忘了剥离它，会去找一个不存在的字段 {@code _titleOnly} →
     * 所有命中被滤掉 → 该档**恒返回空集**（症状是"降级检索什么都找不到"，
     * 看起来像数据问题而不是实现问题）。
     */
    @Test
    @DisplayName("内部标记不得被当成字段过滤条件（否则该档恒为空）")
    void markerIsNotTreatedAsAFieldFilter() {
        put("d1", "安装扭矩手册", "正文。");

        assertThat(store.search(INDEX, "安装扭矩", Map.of(SearchQuery.TITLE_ONLY_MARK, true), 10))
            .as("标记若未剥离，会去找不存在的字段 _titleOnly 并滤掉全部命中")
            .isNotEmpty();
    }

    @Test
    @DisplayName("收窄检索域不影响其他过滤条件（站点过滤仍然生效）")
    void narrowingKeepsOtherFiltersEffective() {
        // 两条标题都命中同一关键词，只有 siteId 不同
        store.upsert(INDEX, new SearchIndexStore.SearchDocument("d1",
            Map.of("title", "安装扭矩手册", "body", "正文。", "siteId", "mine")));
        store.upsert(INDEX, new SearchIndexStore.SearchDocument("d2",
            Map.of("title", "安装扭矩手册（另一站点）", "body", "正文。", "siteId", "other")));

        var page = store.search(INDEX, "安装扭矩",
            titleOnlyQuery("安装扭矩", Map.of("siteId", "mine")).filters(), 10);

        assertThat(page)
            .as("收窄到标题域后站点过滤必须仍然生效（否则会跨站点泄漏）")
            .hasSize(1);
        assertThat(page.get(0).id()).isEqualTo("d1");
    }

    @Test
    @DisplayName("titleOnly() 识别布尔与字符串两种形态（配置可能来自 JSON）")
    void titleOnlyMarkerIsRecognized() {
        assertThat(titleOnlyQuery("q", Map.of()).titleOnly()).isTrue();
        assertThat(fullQuery("q", Map.of()).titleOnly()).isFalse();
        assertThat(new SearchQuery("q", Map.of(SearchQuery.TITLE_ONLY_MARK, "true"), null, 5, null, null)
            .titleOnly()).isTrue();
        assertThat(new SearchQuery("q", Map.of(SearchQuery.TITLE_ONLY_MARK, "false"), null, 5, null, null)
            .titleOnly()).isFalse();
        assertThat(fullQuery("q", Map.of("siteId", "s")).titleOnly()).isFalse();
    }
}
