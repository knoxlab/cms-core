package com.powercms.cms.search;

import java.util.List;
import java.util.Map;

/** Storage abstraction shared by admin and public search indexes. */
public interface SearchIndexStore {

    /**
     * FR-AI-RAG-011 前缀过滤标记：过滤值以该标记开头时按「前缀匹配」语义执行
     * （OpenSearch → prefix 查询；InMemory → startsWith）。供多源路由的
     * sourceDomain 过滤使用（如 "_prefix:PIM:" 命中 "PIM:erp"）。
     */
    String PREFIX_FILTER_MARK = "_prefix:";

    /**
     * 无 mapping 的重建（向后兼容入口）：委托到 {@link #rebuild(String, String, List, IndexMapping)}。
     */
    default void rebuild(String alias, String candidateIndex, List<SearchDocument> documents) {
        rebuild(alias, candidateIndex, documents, null);
    }

    /**
     * 携带 mapping 信息的重建（FR-AI-RAG-001 / M0）。{@code mapping} 为 null 时退化为默认 BM25 mapping。
     * 用于 RAG 重建时写入含向量字段的 k-NN mapping。
     *
     * <p>G9-⑩：缺省实现由三个增量原语组合而成（重置候选索引 → 分批写入 → 切换 alias），
     * 便于上层按批次写入并持久化游标（断点续跑）。
     */
    default void rebuild(String alias, String candidateIndex, List<SearchDocument> documents, IndexMapping mapping) {
        resetIndex(candidateIndex, mapping);
        writeBatch(candidateIndex, documents);
        switchAlias(alias, candidateIndex);
    }

    /**
     * 影子索引增量原语（G9-⑩ / FR-AI-RAG-001 / NFR-AI-PERF-003）：**重置并创建**候选索引
     * （删除同名物理索引后按 mapping 重建）。仅在**全新重建**时调用；断点续跑**不得**调用，
     * 否则会丢弃已写入的批次。
     */
    default void resetIndex(String index, IndexMapping mapping) {
        throw new UnsupportedOperationException("resetIndex not supported by this store");
    }

    /**
     * 影子索引增量原语（G9-⑩）：**批量写入**候选索引且不切换 alias，供分批重建/续跑逐页写入。
     * 实现须保证「写后可见/可查询」的口径与 {@link #rebuild} 一致；空列表为无操作。
     */
    default void writeBatch(String index, List<SearchDocument> documents) {
        throw new UnsupportedOperationException("writeBatch not supported by this store");
    }

    /**
     * 影子索引增量原语（G9-⑩）：候选索引**原子切换**为 alias 并清理被取代的旧物理索引。
     * 必须**幂等**：alias 已指向候选索引时不得再删除该索引（重复调用不得造成数据丢失）。
     */
    default void switchAlias(String alias, String candidateIndex) {
        throw new UnsupportedOperationException("switchAlias not supported by this store");
    }

    /**
     * 影子索引增量原语（G9-⑩）：判断物理索引是否存在。断点续跑前据此校验候选索引是否仍完整
     * （进程重启后 memory 后端返回 false → 上层退回全新重建，避免只写尾批次造成索引残缺）。
     */
    default boolean indexExists(String index) {
        return false;
    }

    void upsert(String indexOrAlias, SearchDocument document);
    List<SearchHit> search(String indexOrAlias, String query, Map<String, Object> filters, int limit);

    /**
     * 命中条数（DEF-KB-01）：**纯计数**，过滤语义与 {@link #search} 完全一致
     * （term / terms / {@link #PREFIX_FILTER_MARK} 前缀）。
     *
     * <p>用途是「切别名前的合法性校验」这类只需要一纸计数的场景（如候选访客索引里有多少条非发布文档）。
     * 缺省实现退化为读取整页文档再取 size（内存后端够用），OpenSearch 后端覆写为 {@code _count}，
     * 避免为一纸计数把文档体全部反序列化。
     */
    default long count(String indexOrAlias, Map<String, Object> filters) {
        return search(indexOrAlias, null, filters, Integer.MAX_VALUE).size();
    }

    /**
     * 带**排序 + 游标分页 + 属性驱动高亮**的检索（FR-SEARCH-004 / AC-CM-009）。
     *
     * <p>缺省实现忽略排序与游标、退化为 {@link #search}（{@code nextCursor} 恒为 null）：
     * 这样「排序/分页能力不可用」对调用方是**显式可见**的（拿不到游标），而不是静默返回错序结果。
     */
    default SearchPage searchPage(String indexOrAlias, SearchQuery query) {
        List<SearchHit> hits = search(indexOrAlias, query.query(), query.filters(), query.size());
        return new SearchPage(hits, null);
    }

    /**
     * 搜索建议（FR-SEARCH-004「搜索建议」）：按**前缀**返回该字段上出现过的取值（按出现频次降序）。
     *
     * @param field 索引键（必须是有 keyword 语义的键，即 resolver 校验通过的可排序/可过滤字段）
     * @return 去重后的建议词；缺省实现返回空列表（不支持建议的后端）
     */
    default List<String> suggest(String indexOrAlias, String field, String prefix, int limit) {
        return List.of();
    }

    /**
     * 删除单条索引文档（用于内容下线/删除清理索引）。
     */
    default void delete(String indexOrAlias, String documentId) {
        throw new UnsupportedOperationException("delete not supported by this store");
    }

    /**
     * 重建时使用的 mapping 描述（AC-CM-009 / FR-MODEL-006）。{@code null} 表示保持向后兼容的
     * 纯 BM25 mapping。
     *
     * <p>{@code fieldSpecs}：按内容模型 {@code sortable}/{@code filterable}/{@code highlight}
     * 属性派生的**字段级**索引键（{@code f_<code>}）。
     * 空列表等价于旧行为（只有 title/body 可检索，无字段级排序/过滤/高亮）—— 既有 2 参/3 参
     * 调用点因此自动兼容。
     */
    record IndexMapping(List<com.powercms.cms.content.FieldIndexSpec> fieldSpecs) {

        /** 无字段级键的旧形态（与 AC-CM-009 落地前行为等价）。 */
        IndexMapping() {
            this(List.of());
        }

        @Override
        public List<com.powercms.cms.content.FieldIndexSpec> fieldSpecs() {
            return fieldSpecs == null ? List.of() : fieldSpecs;
        }
    }

    /** 索引文档扩展字段。 */
    record SearchDocument(String id, Map<String, Object> fields) {}

    /**
     * 索引命中。
     * <p>{@code sortValues} 为本条命中在请求 {@code sort} 数组上的取值（FR-SEARCH-004 游标分页：
     * 作为下一页 {@code search_after} 的输入）。无排序请求时为空列表。
     */
    record SearchHit(String id, Map<String, Object> fields, Double score,
                     List<Object> sortValues) {
        public SearchHit(String id, Map<String, Object> fields) { this(id, fields, null, null); }
        public SearchHit(String id, Map<String, Object> fields, Double score) { this(id, fields, score, null); }

        @Override
        public List<Object> sortValues() {
            return sortValues == null ? List.of() : sortValues;
        }
    }
}
