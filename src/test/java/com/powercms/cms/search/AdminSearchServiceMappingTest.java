package com.powercms.cms.search;

import com.powercms.cms.content.ContentIndexSchema;
import com.powercms.cms.content.ContentSearchTextAssembler;
import com.powercms.cms.content.FieldIndexSpec;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-SEARCH-003 回归：索引文档缺 categoryId（或字段为 "null"）时
 * 搜索映射不得抛 IllegalArgumentException 导致 500。
 *
 * <p>AC-CM-009 追加：字段级索引键（{@code f_<code>}）进入文档，且受目标索引闸门约束。
 */
class AdminSearchServiceMappingTest {

    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ALIAS = "powercms-admin-test-" + SITE_ID.toString().replace("-", "");
    private static final UUID TYPE_ID = UUID.fromString("00000000-0000-0000-0000-00000000cafe");
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static AdminSearchService newService() {
        return new AdminSearchService(null, null, null, new InMemorySearchIndexStore(), "test");
    }

    @Test
    void missingCategoryIdMapsToNullInsteadOfThrowing() {
        AdminSearchService service = newService();
        // 模拟既有索引中无 categoryId 的文档（站点内容未挂分类时的真实形态）
        service.rebuild(ALIAS, List.of(
            new SearchIndexStore.SearchDocument(SITE_ID.toString(), Map.of(
                "siteId", SITE_ID.toString(),
                "title", "London in Style",
                "status", "PUBLISHED",
                "body", "adventure body"))));

        assertThatCode(() -> service.search(SITE_ID, "adventure", Set.of())).doesNotThrowAnyException();
        List<AdminSearchService.SearchResult> results = service.search(SITE_ID, "adventure", Set.of());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).categoryId()).isNull();
        assertThat(results.get(0).title()).isEqualTo("London in Style");
    }

    @Test
    void legacyNullStringCategoryIdMapsToNull() {
        AdminSearchService service = newService();
        service.rebuild(ALIAS, List.of(
            new SearchIndexStore.SearchDocument(SITE_ID.toString(), Map.of(
                "siteId", SITE_ID.toString(),
                "title", "Blue Ice Adventure",
                "status", "DRAFT",
                "body", "body",
                "categoryId", "null"))));

        List<AdminSearchService.SearchResult> results = service.search(SITE_ID, "blue", Set.of());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).categoryId()).isNull();
    }

    @Test
    void presentCategoryIdIsPreserved() {
        AdminSearchService service = newService();
        UUID categoryId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        service.rebuild(ALIAS, List.of(
            new SearchIndexStore.SearchDocument(SITE_ID.toString(), Map.of(
                "siteId", SITE_ID.toString(),
                "title", "Amazon Escape",
                "status", "PUBLISHED",
                "body", "body",
                "categoryId", categoryId.toString()))));

        List<AdminSearchService.SearchResult> results = service.search(SITE_ID, "amazon", Set.of());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).categoryId()).isEqualTo(categoryId);
    }

    // ---- AC-CM-009：字段级索引键进入文档（sortable/filterable/highlight 真正生效）----

    /**
     * 管理索引文档必须带上 {@code f_<code>} 字段级键（类型保真）——此前字段值只被拍平进
     * {@code body}，索引里根本没有可被 sort/term 指名寻址的键，三个属性因此完全无效。
     */
    @Test
    void adminDocumentCarriesFieldLevelKeys() {
        AdminSearchService service = serviceWithSchema(
            List.of(roleField("code_s", "STRING").sortable().filterable(),
                roleField("count_i", "INTEGER").sortable()),
            "{\"code_s\":\"A-1\",\"count_i\":7}");

        SearchIndexStore.SearchDocument doc =
            service.buildDocument(contentItem(), ContentSearchTextAssembler.Target.ADMIN);

        assertThat(doc.fields()).containsEntry("f_code_s", "A-1").containsEntry("f_count_i", 7L);
    }

    /** 访客索引文档只带 {@code publicIndex} 字段的键，未开访客索引的字段不得以 {@code f_*} 旁路外泄。 */
    @Test
    void visitorDocumentOmitsFieldLevelKeysOutsidePublicIndex() {
        AdminSearchService service = serviceWithSchema(
            List.of(roleField("internal_s", "STRING").sortable().filterable().publicIndex(false)),
            "{\"internal_s\":\"TOP-SECRET\"}");

        SearchIndexStore.SearchDocument doc =
            service.buildDocument(contentItem(), ContentSearchTextAssembler.Target.PUBLIC);

        assertThat(doc.fields()).doesNotContainKey("f_internal_s");
    }

    /** 未接线字段级 schema（未注入依赖/单测桩）时文档形态与落地前一致。 */
    @Test
    void documentWithoutSchemaHasNoFieldLevelKeys() {
        AdminSearchService service = serviceWithSchema(List.of(), "{\"code_s\":\"A-1\"}");

        SearchIndexStore.SearchDocument doc =
            service.buildDocument(contentItem(), ContentSearchTextAssembler.Target.ADMIN);

        assertThat(doc.fields().keySet()).noneMatch(key -> key.startsWith("f_"));
    }

    /** 批量重建暴露的 schema 必须按目标索引区分（两侧 mapping 不可互换）。 */
    @Test
    void siteFieldSchemaIsTargetAware() {
        AdminSearchService service = serviceWithSchema(
            List.of(roleField("admin_s", "STRING").filterable().publicIndex(false)), "{}");

        assertThat(service.siteFieldSchema(SITE_ID, ContentSearchTextAssembler.Target.ADMIN).keys())
            .containsExactly("f_admin_s");
        assertThat(service.siteFieldSchema(SITE_ID, ContentSearchTextAssembler.Target.PUBLIC).keys())
            .isEmpty();
    }

    /**
     * 文档必须写入 {@code contentId}（游标分页的 tiebreaker），且与文档 {@code _id} 一致。
     *
     * <p>回归护栏：mapping 侧已声明 {@code contentId} 为 keyword，但若文档不写值，
     * 排序键恒为空 —— {@code search_after} 会退化成「同值并列」，翻页可能丢文档或死循环。
     * 两个索引目标都必须带（mapping 是共用的，文档不能只在一侧写）。
     */
    @Test
    void documentCarriesContentIdTiebreaker() {
        AdminSearchService service = serviceWithSchema(List.of(), "{}");

        for (ContentSearchTextAssembler.Target target : ContentSearchTextAssembler.Target.values()) {
            SearchIndexStore.SearchDocument doc = service.buildDocument(contentItem(), target);
            assertThat(doc.id()).isEqualTo(ITEM_ID.toString());
            assertThat(doc.fields()).containsEntry("contentId", ITEM_ID.toString());
        }
    }

    /**
     * 无向量能力时 mapping **仍须带着字段级键**（dimensions=0），不得退化为 null。
     *
     * <p>回归护栏：文档写入只看 schema，{@code f_*} 值照写；若 mapping 侧退化为 null，
     * 这些键就只能由 OpenSearch 动态推断（text + kw 子字段），数值/日期排序静默变成字典序。
     */
    @Test
    void mappingWithoutEmbeddingProviderStillDeclaresFieldLevelKeys() {
        AdminSearchService service = serviceWithSchema(
            List.of(roleField("count_i", "INTEGER").sortable()), "{}");

        SearchIndexStore.IndexMapping mapping =
            service.currentMapping(SITE_ID, ContentSearchTextAssembler.Target.ADMIN);

        assertThat(mapping).isNotNull();
        assertThat(mapping.fieldSpecs()).extracting(FieldIndexSpec::key).containsExactly("f_count_i");
        assertThat(mapping.fieldSpecs()).extracting(FieldIndexSpec::type).containsExactly("long");
    }

    /** schema 派生抛错时不得中断索引流程（降级为无字段级键）。 */
    @Test
    void schemaFailureDegradesInsteadOfThrowing() {
        FieldDefinitionRepository failing = mock(FieldDefinitionRepository.class);
        when(failing.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any()))
            .thenThrow(new IllegalStateException("db down"));
        AdminSearchService service = new AdminSearchService(null, null, null,
            new InMemorySearchIndexStore(), "test");
        service.setContentIndexSchema(new ContentIndexSchema(failing, typeRepo()));

        assertThatCode(() -> service.siteFieldSchema(SITE_ID, ContentSearchTextAssembler.Target.ADMIN))
            .doesNotThrowAnyException();
        assertThat(service.siteFieldSchema(SITE_ID, ContentSearchTextAssembler.Target.ADMIN).specs()).isEmpty();
    }

    // ---- helpers ----

    private static AdminSearchService serviceWithSchema(List<FieldDefinitionEntity> defs,
                                                        String fieldValuesJson) {
        ContentVersionEntity version = new ContentVersionEntity();
        version.setFieldValues(fieldValuesJson);
        ContentVersionRepository versionRepo = mock(ContentVersionRepository.class);
        when(versionRepo.findByContentItemIdAndVersion(ITEM_ID, 1)).thenReturn(Optional.of(version));

        FieldDefinitionRepository fieldRepo = mock(FieldDefinitionRepository.class);
        when(fieldRepo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any())).thenReturn(defs);
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(any())).thenReturn(defs);

        AdminSearchService service = new AdminSearchService(null, versionRepo, null,
            new InMemorySearchIndexStore(), "test");
        service.setContentIndexSchema(new ContentIndexSchema(fieldRepo, typeRepo()));
        return service;
    }

    private static ContentTypeRepository typeRepo() {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(TYPE_ID);
        type.setSiteId(SITE_ID);
        type.setCode("article");
        ContentTypeRepository typeRepo = mock(ContentTypeRepository.class);
        when(typeRepo.findBySiteIdOrderByCodeAsc(SITE_ID)).thenReturn(List.of(type));
        return typeRepo;
    }

    private static ContentItemEntity contentItem() {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(ITEM_ID);
        item.setSiteId(SITE_ID);
        item.setContentTypeId(TYPE_ID);
        item.setTitle("标题");
        item.setStatus("DRAFT");
        item.setCurrentVersion(1);
        return item;
    }

    private static Def roleField(String code, String fieldType) {
        return new Def(code, fieldType);
    }

    /** 字段定义的可读构造器（属性角色链）；沿用生产实体的 setter，避免测试出现与实体漂移的副本。 */
    private static final class Def extends FieldDefinitionEntity {
        private Def(String code, String fieldType) {
            setId(UUID.randomUUID());
            setContentTypeId(TYPE_ID);
            setCode(code);
            setName(code);
            setFieldType(fieldType);
            setAdminIndex(true);
            setPublicIndex(true);
            setSortOrder(0);
        }

        private Def sortable() { setSortable(true); return this; }

        private Def filterable() { setFilterable(true); return this; }

        private Def highlight() { setHighlight(true); return this; }

        private Def publicIndex(boolean value) { setPublicIndex(value); return this; }
    }
}
