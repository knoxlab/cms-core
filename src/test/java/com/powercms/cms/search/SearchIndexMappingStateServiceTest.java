package com.powercms.cms.search;

import com.powercms.cms.content.ContentIndexSchema;
import com.powercms.cms.content.ContentIndexSchemaChangedEvent;
import com.powercms.cms.content.ContentSearchTextAssembler.Target;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.search.entity.SearchIndexMappingStateEntity;
import com.powercms.cms.search.repo.SearchIndexMappingStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-MODEL-006 / AC-CM-009 / TC-M66-023：字段级索引映射的生命周期。
 *
 * <p>核心语义：{@code sortable}/{@code filterable}/{@code highlight} 改变的是 OpenSearch
 * <b>mapping 形态</b>（{@code f_<code>} 键的类型与子字段），而 mapping 近乎不可变 ——
 * 模型属性一改，已建索引就不再与模型一致，此时排序/过滤只会以 400 暴露。本服务负责让这件事
 * <b>可发现</b>：
 * <ul>
 *   <li>模型发布 → 重算指纹比对 → 不一致则告警（WARN）+ 状态表标记需重建（<b>不</b>自动重建）；</li>
 *   <li>重建成功 → 回执基线，状态复位；</li>
 *   <li>冲突 / 不支持角色 → 告警并纳入指纹（「勾了却不生效」的静默失效必须可见）；</li>
 *   <li>指纹只含映射形态 → 重复发布同一模型不得反复告警。</li>
 * </ul>
 *
 * <p><b>注意</b>：本服务监听的是 {@code content} 模块转译后的
 * {@link ContentIndexSchemaChangedEvent}，而不是 {@code model} 的
 * {@code ContentTypePublishedEvent} —— 模块边界见服务类注释；转译链路的接缝由
 * {@code ContentIndexSchemaChangeBridgeTest} 单独覆盖。
 */
class SearchIndexMappingStateServiceTest {

    /** 与建索引侧同口径的分析器（本类用例不覆盖分析器切换）。 */
    private static final String ANALYZER = "standard";

    private static final UUID SITE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TYPE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final Map<String, SearchIndexMappingStateEntity> store = new LinkedHashMap<>();
    private SearchIndexMappingStateRepository repo;
    private SearchIndexMappingStateWriter writer;
    private FieldDefinitionRepository fieldRepo;

    @BeforeEach
    void setUp() {
        repo = mock(SearchIndexMappingStateRepository.class);
        when(repo.save(any(SearchIndexMappingStateEntity.class))).thenAnswer(inv -> {
            SearchIndexMappingStateEntity entity = inv.getArgument(0);
            store.put(entity.getTarget(), entity);
            return entity;
        });
        when(repo.findBySiteIdAndTarget(eq(SITE_ID), anyString())).thenAnswer(inv ->
            Optional.ofNullable(store.get(inv.getArgument(1))));
        when(repo.findBySiteIdOrderByTargetAsc(SITE_ID)).thenAnswer(inv -> new ArrayList<>(store.values()));

        // 独立事务写入桩：转调 repo，使本类的状态断言与生产语义一致
        writer = mock(SearchIndexMappingStateWriter.class);
        org.mockito.Mockito.doAnswer(inv -> {
            repo.save(inv.getArgument(0, SearchIndexMappingStateEntity.class));
            return null;
        }).when(writer).upsert(any(SearchIndexMappingStateEntity.class));

        fieldRepo = mock(FieldDefinitionRepository.class);
    }

    /**
     * 尚无基线：登记 desired，但<b>不告警</b>。
     *
     * <p>管理/访客两个目标各自独立比对 —— 「从未重建过」不等于「映射过期」这一回归，
     * 此时只登记状态，不往失败队列里丢噪音。
     */
    @Test
    void firstPublishRecordsBaselineWithoutAlerting() {
        SearchIndexMappingStateService service = service(sortableField("title", "STRING"));

        service.onContentIndexSchemaChanged(new ContentIndexSchemaChangedEvent(SITE_ID));

        assertThat(service.rebuildRequired(SITE_ID)).isTrue();
        assertThat(targets(service)).containsExactlyInAnyOrder("ADMIN", "PUBLIC");
        assertThat(state(service, "ADMIN").appliedFingerprint()).isNull();
        assertThat(state(service, "ADMIN").desiredFingerprint()).isNotNull();
    }

    /** 基线已对齐且模型未变：重复发布<b>不得</b>产生告警（否则每次发布都会刷失败队列）。 */
    @Test
    void repeatPublishWithUnchangedModelIsQuiet() {
        SearchIndexMappingStateService service = service(sortableField("title", "STRING"));
        markAppliedBoth(service);
        assertThat(service.rebuildRequired(SITE_ID)).isFalse();

        service.onContentIndexSchemaChanged(new ContentIndexSchemaChangedEvent(SITE_ID));

        assertThat(service.rebuildRequired(SITE_ID)).isFalse();
    }

    /**
     * 属性变更（sortable 关掉、filterable 打开）→ 映射形态偏离基线 →
     * 需重建（社区版以 WARN 日志 + 状态表作为可见载体，不再写 AI 失败队列）。
     *
     * <p>在同一份字段定义实例上改属性，模拟「模型发布后 field_definition 属性被改」这条真实路径。
     */
    @Test
    void changedModelFlagsRebuild() {
        FieldDefinitionEntity def = sortableField("title", "STRING");
        SearchIndexMappingStateService service = service(def);
        markAppliedBoth(service);
        assertThat(service.rebuildRequired(SITE_ID)).isFalse();

        // 模型属性变更：键仍在（f_title），但角色由「可排序」变为「可过滤」→ 映射形态随之改变。
        // （若把三个角色全部关掉，schema 会变空 → 指纹为 null → 按「schema 不可得」跳过；
        //   那种情况下模型侧已不再声明任何字段级键，查询侧会对该字段直接 400，不构成静默错配。）
        def.setSortable(false);
        def.setFilterable(true);

        service.onContentIndexSchemaChanged(new ContentIndexSchemaChangedEvent(SITE_ID));

        // 管理/访客两个目标各自独立比对（各自有独立的 mapping 基线），两次都该判定需重建
        assertThat(service.rebuildRequired(SITE_ID)).isTrue();
    }

    /** 重建成功回执：applied 对齐 desired，需重建标记复位。 */
    @Test
    void markAppliedClearsRebuildRequired() {
        SearchIndexMappingStateService service = service(sortableField("title", "STRING"));
        markAppliedBoth(service);
        store.get("ADMIN").setAppliedFingerprint("stale");
        assertThat(service.rebuildRequired(SITE_ID)).isTrue();

        service.markApplied(SITE_ID, Target.ADMIN);

        assertThat(service.rebuildRequired(SITE_ID)).isFalse();
        assertThat(state(service, "ADMIN").appliedFingerprint())
            .isEqualTo(state(service, "ADMIN").desiredFingerprint());
    }

    /**
     * 冲突 / 不支持角色即使没有基线变化也必须进入状态表并给出可见告警：LONG_TEXT 开 sortable 是
     * 「UI 上勾了、保存成功、但排序永不生效」的静默失效。社区版没有 AI 模块的失败队列出口，
     * 因此以 WARN 日志 + schema 冲突项（随指纹进入基线）作为可见载体。
     *
     * <p>冲突必须纳入指纹：冲突解除（例如把两个内容类型的同 code 字段改成同类型）同样需要重建
     * mapping 才能让该键重新可排序。
     */
    @Test
    void unsupportedRoleMarksStateDirty() {
        SearchIndexMappingStateService service = service(field("body", "LONG_TEXT", def -> {
            def.setSortable(true);
            def.setHighlight(true);
        }));

        service.onContentIndexSchemaChanged(new ContentIndexSchemaChangedEvent(SITE_ID));

        // 管理/访客两个目标各登记一次（同一次发布、同一份冲突，分目标可见便于定位）
        assertThat(targets(service)).containsExactlyInAnyOrder("ADMIN", "PUBLIC");
        verify(writer, times(2)).upsert(any(SearchIndexMappingStateEntity.class));
        assertThat(service.states(SITE_ID)).allSatisfy(state ->
            assertThat(state.rebuildRequired()).isTrue());
    }

    /** schema 不可得（模型仓储缺失）时静默跳过：映射比对是旁路，不得影响模型发布。 */
    @Test
    void missingSchemaIsSilentlySkipped() {
        SearchIndexMappingStateService service = new SearchIndexMappingStateService(
            repo, writer, provider(null), ANALYZER);

        service.onContentIndexSchemaChanged(new ContentIndexSchemaChangedEvent(SITE_ID));

        assertThat(service.states(SITE_ID)).isEmpty();
            }

    /**
     * 重建回执的落库必须走 {@link SearchIndexMappingStateWriter}（独立事务），不得直连 repo。
     *
     * <p><b>回归护栏</b>：{@code AdminSearchService.indexAll} 是
     * {@code @Transactional(readOnly = true)}，只读事务里 Hibernate 的 flush 模式为
     * {@code MANUAL}，直连 {@code repo.save} 的新实体**不落库且无任何异常/告警**。实测后果：
     * 管理索引的 {@code applied} 基线永远为 {@code NULL} → {@code rebuildRequired} 恒真
     * （「需重建」提示永久亮着）、{@code search.mapping_stale} 永不触发（{@code changed}
     * 判定依赖 applied 非空）。访客索引因重建恰好是读写事务而正常，症状呈「一半正常」。
     */
    @Test
    void rebuildBaselineGoesThroughTheIsolatedWriter() {
        SearchIndexMappingStateService service = service(sortableField("title", "STRING"));

        // 重建回执（只读调用方 = AdminSearchService.indexAll）
        service.markApplied(SITE_ID, Target.ADMIN);

        verify(writer).upsert(any(SearchIndexMappingStateEntity.class));
    }

    /** 模型发布后的状态登记同样走独立事务（两个目标各自登记一次基线）。 */
    @Test
    void modelPublishStateRegistrationGoesThroughTheIsolatedWriter() {
        SearchIndexMappingStateService service = service(sortableField("title", "STRING"));

        service.onContentIndexSchemaChanged(new ContentIndexSchemaChangedEvent(SITE_ID));

        verify(writer, times(2)).upsert(any(SearchIndexMappingStateEntity.class));
    }

    /**
     * 冲突登记失败不得冒泡：映射比对是旁路能力，不能因为「记录失败」把模型发布这条主流程带崩。
     */
    @Test
    void conflictRecordingFailureDoesNotPropagate() {
        SearchIndexMappingStateService service = service(field("body", "LONG_TEXT", def -> {
            def.setSortable(true);
            def.setHighlight(true);
        }));
        org.mockito.Mockito.doThrow(new IllegalStateException("state store down"))
            .when(writer).upsert(any(SearchIndexMappingStateEntity.class));

        service.onContentIndexSchemaChanged(new ContentIndexSchemaChangedEvent(SITE_ID));

        // 落库失败被吞掉，主流程正常返回
        assertThat(service.states(SITE_ID)).isEmpty();
    }

    // ------------------------------------------------------------ helpers

    /** 以单个字段定义构造服务（schema / 状态仓储 / 独立事务写入桩）。 */
    private SearchIndexMappingStateService service(FieldDefinitionEntity field) {
        when(fieldRepo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any()))
            .thenReturn(List.of(field));
        ContentIndexSchema schema = new ContentIndexSchema(fieldRepo, typeRepo());
        return new SearchIndexMappingStateService(repo, writer, provider(schema), ANALYZER);
    }

    private static SearchIndexMappingStateService.MappingState state(
        SearchIndexMappingStateService service, String target) {
        return service.states(SITE_ID).stream()
            .filter(item -> target.equals(item.target()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("缺少目标 " + target + " 的映射状态"));
    }

    private static List<String> targets(SearchIndexMappingStateService service) {
        return service.states(SITE_ID).stream()
            .map(SearchIndexMappingStateService.MappingState::target)
            .toList();
    }

    /** 两个索引都重建过（回执基线），用于「模型未变 / 变更」两类比对的前置状态。 */
    private static void markAppliedBoth(SearchIndexMappingStateService service) {
        service.markApplied(SITE_ID, Target.ADMIN);
        service.markApplied(SITE_ID, Target.PUBLIC);
    }

    private static ContentTypeRepository typeRepo() {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(TYPE_ID);
        type.setSiteId(SITE_ID);
        ContentTypeRepository repo = mock(ContentTypeRepository.class);
        when(repo.findBySiteIdOrderByCodeAsc(SITE_ID)).thenReturn(List.of(type));
        return repo;
    }

    private static FieldDefinitionEntity sortableField(String code, String type) {
        return field(code, type, def -> def.setSortable(true));
    }

    private static FieldDefinitionEntity field(String code, String type,
                                               Consumer<FieldDefinitionEntity> roles) {
        FieldDefinitionEntity def = new FieldDefinitionEntity();
        def.setId(UUID.randomUUID());
        def.setContentTypeId(TYPE_ID);
        def.setCode(code);
        def.setName(code);
        def.setFieldType(type);
        def.setAdminIndex(true);
        def.setPublicIndex(true);
        roles.accept(def);
        return def;
    }

    /** 最小 {@link ObjectProvider} 桩（只需 getObject / getIfAvailable 的取值语义）。 */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }
}
