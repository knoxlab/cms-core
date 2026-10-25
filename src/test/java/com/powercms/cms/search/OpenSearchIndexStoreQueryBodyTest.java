package com.powercms.cms.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯 BM25 检索查询体（社区版不含向量/融合检索）。
 *
 * <p>用静态入口直接校验生成的 JSON，与 {@link OpenSearchIndexStoreMappingTest} 同一口径：
 * 断言真实生成逻辑，避免测试里手抄一份查询体而与实现漂移。
 */
class OpenSearchIndexStoreQueryBodyTest {

    private static final ObjectMapper MAPPERS = new ObjectMapper();

    private static JsonNode body() throws Exception {
        return MAPPERS.valueToTree(OpenSearchIndexStore.queryBody(
            List.of(Map.of("term", Map.of("status", "PUBLISHED"))),
            "配电柜", List.of("title", "body"), 20, "standard"));
    }

    /** 关键词查询走 {@code multi_match}（多字段 + analyzer 与 mapping 同口径）。 */
    @Test
    void keywordQueryUsesMultiMatch() throws Exception {
        JsonNode multiMatch = body().path("bool").path("must").get(1).path("multi_match");

        assertThat(multiMatch.path("query").asText()).isEqualTo("配电柜");
        assertThat(multiMatch.path("fields").get(0).asText()).isEqualTo("title");
        assertThat(multiMatch.path("fields").get(1).asText()).isEqualTo("body");
        assertThat(multiMatch.path("analyzer").asText()).isEqualTo("standard");
    }

    /** 过滤条件恒在 {@code bool.must}（FR-AI-RAG-003：站点/状态/ACL 不因检索而旁路）。 */
    @Test
    void filtersStayInMustBranch() throws Exception {
        JsonNode bool = body().path("bool");

        assertThat(bool.path("must").get(0).path("term").path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(bool.has("should")).isFalse();
        assertThat(bool.has("minimum_should_match")).isFalse();
    }

    /** 查询体不得含任何向量/k-NN 构造（社区版纯关键词检索）。 */
    @Test
    void queryHasNoVectorClause() throws Exception {
        String json = MAPPERS.writeValueAsString(body());

        assertThat(json).doesNotContain("knn");
        assertThat(json).doesNotContain("embedding");
        assertThat(json).doesNotContain("boost");
    }
}
