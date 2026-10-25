package com.powercms.cms.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.FieldIndexSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 纯 BM25 索引 mapping 输出（社区版不含向量字段）；R4：analyzer 可配置。 */
class OpenSearchIndexStoreMappingTest {

    private static final ObjectMapper MAPPERS = new ObjectMapper();

    @Test
    void mappingContainsRequiredBm25Fields() throws Exception {
        // 直接校验真实生成逻辑（OpenSearchIndexStore.mappingJson 静态入口，避免手抄漂移）
        String json = OpenSearchIndexStore.mappingJson(new SearchIndexStore.IndexMapping(), "standard");
        // 必须是合法 JSON（真实 OpenSearch PUT 会解析；防括号残缺回归）
        assertThat(MAPPERS.readTree(json)).isNotNull();
        assertThat(json).contains("\"title\":{\"type\":\"text\",\"analyzer\":\"standard\"}");
        assertThat(json).contains("\"body\":{\"type\":\"text\",\"analyzer\":\"standard\"}");
        // 社区版为纯关键词（BM25）索引：不得出现任何向量/k-NN 构造
        assertThat(json).doesNotContain("knn_vector");
        assertThat(json).doesNotContain("index.knn");
        assertThat(json).doesNotContain("embedding");
        // FR-AI-SRC-003：sourceDomain 为 keyword（多源路由前缀过滤 + 来源域标注）
        assertThat(json).contains("\"sourceDomain\":{\"type\":\"keyword\"}");
    }

    @Test
    void nullMappingKeepsBackwardsCompatibility() throws Exception {
        // null mapping 时仅含 BM25 字段
        String json = OpenSearchIndexStore.mappingJson(null, "standard");
        assertThat(MAPPERS.readTree(json)).isNotNull();
        assertThat(json).doesNotContain("knn_vector");
        assertThat(json).doesNotContain("embedding");
    }

    /** R4：analyzer 配置写入 title/body 文本字段（为 ik/ICU 等中文分词部署预留，无需改代码即切换）。 */
    @Test
    void customAnalyzerIsAppliedToTextFields() throws Exception {
        String json = OpenSearchIndexStore.mappingJson(new SearchIndexStore.IndexMapping(), "ik_max_word");
        assertThat(MAPPERS.readTree(json)).isNotNull();
        assertThat(json).contains("\"title\":{\"type\":\"text\",\"analyzer\":\"ik_max_word\"}");
        assertThat(json).contains("\"body\":{\"type\":\"text\",\"analyzer\":\"ik_max_word\"}");
    }

    // ---- AC-CM-009 / FR-MODEL-006：sortable/filterable/highlight 落到字段级 mapping 键 ----

    /**
     * 字段级键按内容模型属性生成：{@code keyword}/{@code long}/{@code double}/{@code boolean}/
     * {@code date} 直接对应 OpenSearch 类型；{@code highlight} 走 {@code text}。
     */
    @Test
    void fieldLevelKeysFollowDeclaredRoles() throws Exception {
        SearchIndexStore.IndexMapping mapping = new SearchIndexStore.IndexMapping(List.of(
            new FieldIndexSpec("code_s", "f_code_s", "keyword", true, true, false),
            new FieldIndexSpec("count_i", "f_count_i", "long", true, false, false),
            new FieldIndexSpec("price_d", "f_price_d", "double", true, false, false),
            new FieldIndexSpec("flag_b", "f_flag_b", "boolean", false, true, false),
            new FieldIndexSpec("published_dt", "f_published_dt", "date", false, true, false),
            new FieldIndexSpec("intro_t", "f_intro_t", "text", false, false, true)));

        JsonNode properties = MAPPERS.readTree(OpenSearchIndexStore.mappingJson(mapping, "standard"))
            .path("mappings").path("properties");

        assertThat(properties.path("f_code_s").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("f_count_i").path("type").asText()).isEqualTo("long");
        assertThat(properties.path("f_price_d").path("type").asText()).isEqualTo("double");
        assertThat(properties.path("f_flag_b").path("type").asText()).isEqualTo("boolean");
        assertThat(properties.path("f_published_dt").path("type").asText()).isEqualTo("date");
        // 高亮字段必须是 text 且带 analyzer，否则出不了片段
        assertThat(properties.path("f_intro_t").path("type").asText()).isEqualTo("text");
        assertThat(properties.path("f_intro_t").path("analyzer").asText()).isEqualTo("standard");
    }

    /**
     * 双角色字段（既要排序/过滤又要高亮）：{@code text} + {@code fields.kw}（keyword 子字段）。
     * 排序/过滤走 {@code f_x.kw}，高亮走 {@code f_x}。
     */
    @Test
    void dualRoleFieldAddsKeywordSubfield() throws Exception {
        SearchIndexStore.IndexMapping mapping = new SearchIndexStore.IndexMapping(
            List.of(new FieldIndexSpec("title_s", "f_title_s", "text", true, true, true)));

        JsonNode field = MAPPERS.readTree(OpenSearchIndexStore.mappingJson(mapping, "ik_max_word"))
            .path("mappings").path("properties").path("f_title_s");

        assertThat(field.path("type").asText()).isEqualTo("text");
        assertThat(field.path("analyzer").asText()).isEqualTo("ik_max_word");
        assertThat(field.path("fields").path("kw").path("type").asText()).isEqualTo("keyword");
        // 超长值不进 keyword 子字段：显式声明上限，避免「整段正文被当 keyword 存」的索引膨胀
        assertThat(field.path("fields").path("kw").path("ignore_above").asInt())
            .isEqualTo(FieldIndexSpec.KEYWORD_IGNORE_ABOVE);
    }

    /** 单角色 text（仅高亮）不追加 keyword 子字段。 */
    @Test
    void highlightOnlyFieldHasNoKeywordSubfield() throws Exception {
        SearchIndexStore.IndexMapping mapping = new SearchIndexStore.IndexMapping(
            List.of(new FieldIndexSpec("intro_t", "f_intro_t", "text", false, false, true)));

        JsonNode field = MAPPERS.readTree(OpenSearchIndexStore.mappingJson(mapping, "standard"))
            .path("mappings").path("properties").path("f_intro_t");

        assertThat(field.path("fields").isMissingNode()).isTrue();
    }

    /**
     * 纯 BM25 降级路径（{@code mapping == null}）不含字段级键 —— 该路径下索引里根本没有
     * {@code f_*} 数据，声明这些键只会让 mapping 与实际文档不一致。
     */
    @Test
    void nullMappingKeepsNoFieldLevelKeys() throws Exception {
        String json = OpenSearchIndexStore.mappingJson(null, "standard");
        List<String> names = new java.util.ArrayList<>();
        MAPPERS.readTree(json).path("mappings").path("properties").fieldNames()
            .forEachRemaining(names::add);

        assertThat(names).noneMatch(name -> name.startsWith("f_"));
    }

    /**
     * 空字段级声明（dimensions 概念在社区版不存在）：映射只含通用键 + 系统排序键。
     *
     * <p>系统键（{@code contentId}/{@code updatedAt}/{@code currentVersion}）被刻意归入
     * 「通用键」而非字段级键 —— 它们不来自内容模型，任何站点/任何降级路径都必须存在。
     */
    @Test
    void emptyFieldSpecsMatchLegacyPlusSystemSortKeys() throws Exception {
        List<String> names = new java.util.ArrayList<>();
        MAPPERS.readTree(OpenSearchIndexStore.mappingJson(new SearchIndexStore.IndexMapping(), "standard"))
            .path("mappings").path("properties").fieldNames().forEachRemaining(names::add);

        assertThat(names).containsExactlyInAnyOrder("title", "body", "status", "siteId", "categoryId",
            "contentTypeId", "sourceDomain",
            "contentId", "updatedAt", "currentVersion",
            // 口径统一（FR-SEARCH-003）：访客索引文档标注所属 Release 与成员版本，
            // 必须显式映射为 keyword/long —— 否则按 releaseId 过滤（切别名校验、检索兜底）会失效。
            "releaseId", "contentVersion");
    }

    /**
     * 系统排序键在任何路径（含纯 BM25 降级 {@code mapping == null}）都必须有<b>正确类型</b>的映射。
     *
     * <p>回归护栏：{@code SearchQuery} 每次检索都会追加 tiebreaker {@code contentId} 以保证
     * 分页稳定，{@code SearchQueryResolver} 也始终声明 {@code updatedAt}/{@code currentVersion}
     * 可排序。OpenSearch 对未映射字段排序会直接抛
     * {@code No mapping found for [x] in order to sort on} —— 即整个检索链路 500。
     * 类型也必须对：{@code date}/long 声明成 keyword 会因格式不符而排序报错。
     */
    @Test
    void systemSortKeysAreAlwaysMappedWithCorrectTypes() throws Exception {
        for (String json : List.of(
            OpenSearchIndexStore.mappingJson(null, "standard"),
            OpenSearchIndexStore.mappingJson(new SearchIndexStore.IndexMapping(), "standard"))) {
            JsonNode properties = MAPPERS.readTree(json).path("mappings").path("properties");
            assertThat(properties.path("contentId").path("type").asText()).isEqualTo("keyword");
            assertThat(properties.path("updatedAt").path("type").asText()).isEqualTo("date");
            assertThat(properties.path("currentVersion").path("type").asText()).isEqualTo("long");
            // 口径统一后切别名校验按 releaseId 过滤：类型必须是 keyword（text 无法 term 过滤）
            assertThat(properties.path("releaseId").path("type").asText()).isEqualTo("keyword");
            assertThat(properties.path("contentVersion").path("type").asText()).isEqualTo("long");
        }
    }
}
