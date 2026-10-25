package com.powercms.cms.content;

import com.powercms.cms.model.entity.FieldDefinitionEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验收 R6：向量化/检索文本装配规则。
 *
 * <ul>
 *   <li>拼接口径「标题+摘要+正文+标签」（summary 约定 code / TAG 字段 / 其余文本字段）；</li>
 *   <li>敏感字段过滤：{@code publicIndex=false}（sensitive 语义）字段内容绝不进入**访客索引**文本；</li>
 *   <li>DEF-SEARCH-02：字段级过滤**按目标索引区分**——管理索引 { @code adminIndex} / 访客索引
 *       {@code publicIndex}（§5.3 / AC-CM-009）；</li>
 *   <li>HTML/Markdown 剥离与空白压缩；i18n 对象 / 数组值取文本叶子；</li>
 *   <li>无字段定义或 JSON 解析失败时降级返回原始文本（向后兼容）。</li>
 * </ul>
 */
class ContentSearchTextAssemblerTest {

    private static final UUID TYPE_ID = UUID.randomUUID();

    @Test
    void excludesNonPublicFieldsAndOrdersSummaryBodyTags() {
        String json = """
            {"summary":"本栏目导语文本","body":"<p>正文第一段<b>加粗要点</b>。</p>",
             "internalNote":"TOP-SECRET-不应索引","tags":["人工智能","seo","  "]}
            """;
        ContentSearchTextAssembler.IndexText text = ContentSearchTextAssembler.extract("标题A", json, defs(
            field("summary", "STRING", true),
            field("body", "RICH_TEXT", true),
            field("internalNote", "LONG_TEXT", false),
            field("tags", "TAG", true)));

        assertThat(text.title()).isEqualTo("标题A");
        // publicIndex=false 的敏感字段内容被剔除
        assertThat(text.content()).doesNotContain("internalNote").doesNotContain("TOP-SECRET");
        assertThat(text.content()).doesNotContain("不应索引");
        // 标签（TAG 字段）进入文本，空白项被滤除
        assertThat(text.content()).contains("人工智能").contains("seo").doesNotContain("  ");
        // HTML 被剥离、标签文本拼接；顺序 = 摘要→正文→标签
        assertThat(text.content()).startsWith("本栏目导语文本");
        assertThat(text.content()).contains("正文第一段 加粗要点");
        assertThat(text.content()).endsWith("人工智能 seo");
        // 向量化输入 = 标题 + 内容
        assertThat(text.embeddingText()).startsWith("标题A");
        assertThat(text.embeddingText()).contains(text.content());
    }

    @Test
    void excludesSensitiveFieldEvenWhenPublicFieldSimilarTextExists() {
        String json = """
            {"publicBody":"公开发布内容","secret":"机密档案A1B2","tags":[]}
            """;
        ContentSearchTextAssembler.IndexText text = ContentSearchTextAssembler.extract(null, json, defs(
            field("publicBody", "LONG_TEXT", true),
            field("secret", "LONG_TEXT", false),
            field("tags", "TAG", true)));

        assertThat(text.content()).contains("公开发布内容").doesNotContain("A1B2");
    }

    @Test
    void stripsHtmlAndCollapsesWhitespace() {
        String json = "{\"body\":\"<p>段落一</p>\\n<p>段落二 <em>斜体</em> 留白</p>\",\"tags\":[]}";
        ContentSearchTextAssembler.IndexText text = ContentSearchTextAssembler.extract(null, json, defs(
            field("body", "RICH_TEXT", true),
            field("tags", "TAG", true)));

        assertThat(text.content()).contains("段落一").contains("段落二 斜体 留白");
        assertThat(text.content()).doesNotContain("<p>").doesNotContain("<em>");
    }

    @Test
    void nonTextFieldTypesAreExcludedFromBody() {
        String json = "{\"cover\":{\"mediaId\":\"m-1\",\"title\":\"封面图\"},\"body\":\"真实正文\"," +
            "\"tags\":[],\"secret\":\"hush\"}";
        ContentSearchTextAssembler.IndexText text = ContentSearchTextAssembler.extract(null, json, defs(
            field("cover", "MEDIA_REF", true),
            field("body", "LONG_TEXT", true),
            field("secret", "JSON", true),
            field("tags", "TAG", true)));

        assertThat(text.content()).isEqualTo("真实正文");
    }

    @Test
    void i18nObjectAndArrayValuesYieldTextLeaves() {
        String json = "{\"title\":{\"zh\":\"中文标题\",\"en\":\"English Title\"}," +
            "\"body\":[\"片段甲\",\"片段乙\"],\"tags\":[]}";
        ContentSearchTextAssembler.IndexText text = ContentSearchTextAssembler.extract(null, json, defs(
            field("title", "STRING", true),
            field("body", "LONG_TEXT", true),
            field("tags", "TAG", true)));

        assertThat(text.content()).contains("中文标题").contains("English Title");
        assertThat(text.content()).contains("片段甲").contains("片段乙");
    }

    @Test
    void emptyDefsFallsBackToRawValues() {
        ContentSearchTextAssembler.IndexText text = ContentSearchTextAssembler.extract("t", "{\"a\":1}", List.of());
        assertThat(text.content()).isEqualTo("{\"a\":1}");
        assertThat(text.title()).isEqualTo("t");
    }

    @Test
    void invalidJsonFallsBackToRawValues() {
        ContentSearchTextAssembler.IndexText text = ContentSearchTextAssembler.extract("t", "{not-json",
            defs(field("body", "LONG_TEXT", true)));
        assertThat(text.content()).isEqualTo("{not-json");
    }

    @Test
    void blankVersionProducesEmptyContent() {
        ContentSearchTextAssembler.IndexText text =
            ContentSearchTextAssembler.extract("t", "   ", defs(field("b", "LONG_TEXT", true)));
        assertThat(text.content()).isEmpty();
        assertThat(text.embeddingText()).isEqualTo("t");
    }

    @Test
    void assembleReadsFieldDefinitionsByContentType() {
        ContentSearchTextAssembler assembler = new ContentSearchTextAssembler(null);
        ContentSearchTextAssembler.IndexText text = assembler.assemble(TYPE_ID, "t", "{\"x\":1}");
        // fieldRepo 为 null（测试）→ 无字段定义 → 降级原始文本
        assertThat(text.content()).isEqualTo("{\"x\":1}");
    }

    // ---- 正文（proseText）口径：schema.org articleBody 等「正文」语义，绝不 dump 结构化字段原文 ----

    @Test
    void proseTextKeepsOnlyProseFieldsAndDropsStructuredFieldValues() {
        String json = """
            {"headline_s":"公司标题","body_html":"<p>我们是一家公司。</p>",
             "contact":{"phone":"13800000000"},"milestones":[{"year":2015}]}
            """;
        String text = ContentSearchTextAssembler.extractProse(json, defs(
            field("headline_s", "STRING", true),
            field("body_html", "RICH_TEXT", true),
            field("contact", "KEY_VALUE", true),
            field("milestones", "REPEAT_GROUP", true)));

        assertThat(text).isEqualTo("我们是一家公司。");
        assertThat(text).doesNotContain("13800000000").doesNotContain("2015")
            .doesNotContain("公司标题").doesNotContain("headline_s");
    }

    @Test
    void proseTextOmitsArticleBodyWhenJsonObjectHasNoProseField() {
        String json = "{\"headline_s\":\"公司标题\",\"contact\":{\"phone\":\"13800000000\"}}";
        String text = ContentSearchTextAssembler.extractProse(json, defs(
            field("headline_s", "STRING", true),
            field("contact", "KEY_VALUE", true)));

        assertThat(text).isEmpty();
    }

    @Test
    void proseTextNeverFallsBackToRawJsonObjectWithoutFieldDefinitions() {
        // 无字段定义时，assemble 的历史口径是「降级原始 fieldValues」；
        // 但正文口径必须 fail-safe：JSON 对象无定义 → 空串（否则会把全量字段 JSON 泄露进 articleBody）
        assertThat(ContentSearchTextAssembler.extractProse("{\"a\":\"b\"}", List.of())).isEmpty();
    }

    @Test
    void proseTextExcludesNonPublicProseField() {
        String json = "{\"secretBody\":\"<p>机密正文</p>\"}";
        assertThat(ContentSearchTextAssembler.extractProse(json,
            defs(field("secretBody", "RICH_TEXT", false)))).isEmpty();
    }

    @Test
    void proseTextKeepsLegacyPlainTextBody() {
        // 历史口径：fieldValues 是纯文本（非 JSON 对象）→ 仍视为正文
        assertThat(ContentSearchTextAssembler.extractProse("正文内容",
            defs(field("body_html", "RICH_TEXT", true)))).isEqualTo("正文内容");
    }

    // ---- DEF-SEARCH-02：字段级过滤按目标索引区分（管理索引 adminIndex / 访客索引 publicIndex）----

    /**
     * DEF-SEARCH-02：管理索引按 {@code adminIndex} 取字段——`publicIndex=false` 但 `adminIndex=true`
     * 的业务字段（此前因共用 publicIndex 而**正文恒为空**）必须进入管理索引正文。
     */
    @Test
    void adminTargetIncludesAdminIndexedFieldsEvenWhenNotPublicIndexed() {
        String json = "{\"body_t\":\"正文关键词弹性伸缩\",\"url_s\":\"/x\",\"secret\":\"TOP-SECRET\"}";
        List<FieldDefinitionEntity> defs = defs(
            field("body_t", "LONG_TEXT", true, false),
            field("url_s", "STRING", true, false),
            field("secret", "LONG_TEXT", false, false));

        ContentSearchTextAssembler.IndexText admin = ContentSearchTextAssembler.extract(
            "标题", json, defs, ContentSearchTextAssembler.Target.ADMIN);
        ContentSearchTextAssembler.IndexText publicText = ContentSearchTextAssembler.extract(
            "标题", json, defs, ContentSearchTextAssembler.Target.PUBLIC);

        // 管理索引：adminIndex=true 的字段进入正文/向量文本，adminIndex=false 的仍被剔除
        assertThat(admin.content()).contains("正文关键词弹性伸缩").contains("/x");
        assertThat(admin.content()).doesNotContain("TOP-SECRET");
        assertThat(admin.embeddingText()).contains("正文关键词弹性伸缩");
        // 访客索引：两侧都未开 → 正文为空（原缺陷正是把这一口径误用到了管理索引）
        assertThat(publicText.content()).isEmpty();
    }

    /** 反向：只开访客索引（`publicIndex=true, adminIndex=false`）的字段只进访客索引，不进管理索引。 */
    @Test
    void adminTargetExcludesPublicOnlyFields() {
        String json = "{\"publicBody\":\"访客可见正文\",\"adminBody\":\"后台可见正文\"}";
        List<FieldDefinitionEntity> defs = defs(
            field("publicBody", "LONG_TEXT", false, true),
            field("adminBody", "LONG_TEXT", true, false));

        ContentSearchTextAssembler.IndexText admin = ContentSearchTextAssembler.extract(
            "t", json, defs, ContentSearchTextAssembler.Target.ADMIN);
        ContentSearchTextAssembler.IndexText publicText = ContentSearchTextAssembler.extract(
            "t", json, defs, ContentSearchTextAssembler.Target.PUBLIC);

        assertThat(admin.content()).isEqualTo("后台可见正文");
        assertThat(publicText.content()).isEqualTo("访客可见正文");
    }

    /** 未显式指定目标时按**访客口径**处理：默认不得放宽为管理口径（避免敏感内容意外入库）。 */
    @Test
    void unspecifiedTargetDefaultsToPublicScope() {
        String json = "{\"adminOnly\":\"后台可见\"}";
        List<FieldDefinitionEntity> defs = defs(field("adminOnly", "LONG_TEXT", true, false));

        assertThat(ContentSearchTextAssembler.extract("t", json, defs).content()).isEmpty();
        assertThat(ContentSearchTextAssembler.extract("t", json, defs, null).content()).isEmpty();
    }

    /** TAG 字段同样按目标索引区分：管理索引收 adminIndex 标签，访客索引收 publicIndex 标签。 */
    @Test
    void tagFieldsFollowTargetIndexScope() {
        String json = "{\"adminTags\":[\"内部标签\"],\"publicTags\":[\"公开标签\"]}";
        List<FieldDefinitionEntity> defs = defs(
            field("adminTags", "TAG", true, false),
            field("publicTags", "TAG", false, true));

        assertThat(ContentSearchTextAssembler.extract("t", json, defs,
            ContentSearchTextAssembler.Target.ADMIN).content()).isEqualTo("内部标签");
        assertThat(ContentSearchTextAssembler.extract("t", json, defs,
            ContentSearchTextAssembler.Target.PUBLIC).content()).isEqualTo("公开标签");
    }

    private static List<FieldDefinitionEntity> defs(FieldDefinitionEntity... fields) {
        return List.of(fields);
    }

    private static FieldDefinitionEntity field(String code, String type, boolean publicIndex) {
        return field(code, type, publicIndex, publicIndex);
    }

    private static FieldDefinitionEntity field(String code, String type, boolean adminIndex,
                                               boolean publicIndex) {
        FieldDefinitionEntity f = new FieldDefinitionEntity();
        f.setId(UUID.randomUUID());
        f.setContentTypeId(TYPE_ID);
        f.setCode(code);
        f.setName(code);
        f.setFieldType(type);
        f.setAdminIndex(adminIndex);
        f.setPublicIndex(publicIndex);
        f.setSortOrder(0);
        return f;
    }
}
