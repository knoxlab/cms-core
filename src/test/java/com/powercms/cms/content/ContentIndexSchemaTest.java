package com.powercms.cms.content;

import com.powercms.cms.content.ContentIndexSchema.MappingConflict;
import com.powercms.cms.content.ContentIndexSchema.SiteSchema;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC-CM-009 / FR-MODEL-006：{@code sortable}/{@code filterable}/{@code highlight} 三属性落地为
 * **字段级索引键**（此前三者可配置、可持久化，但对索引与检索完全无效果 —— 扁平 schema 里
 * 字段值被拍平进单个 {@code body}，无法被 sort/term/highlight.fields 指名寻址）。
 *
 * <p>覆盖点：
 * <ul>
 *   <li>角色 → 映射类型矩阵（keyword/long/double/boolean/date/text + text 的 kw 子字段）；</li>
 *   <li>目标索引闸门优先：未开 {@code adminIndex}/{@code publicIndex} 的字段绝不生成 {@code f_*} 键
 *       （否则字段级键会成为绕过 publicIndex 敏感隔离的旁路）；</li>
 *   <li>不支持的角色如实登记为 {@link MappingConflict#KIND_ROLE_UNSUPPORTED}，不静默失效；</li>
 *   <li>跨内容类型并集：同 code 同族 → 合并角色；异族 → {@code TYPE_CONFLICT} + 该键整体剔除
 *       （剔除而非「取赢家」：mapping 只容一种类型，异型文档会被 bulk 拒绝并拖垮整次重建）；</li>
 *   <li>文档取值类型保真 + 冲突键不写入。</li>
 * </ul>
 */
class ContentIndexSchemaTest {

  private static final UUID SITE_ID = UUID.randomUUID();
  private static final UUID TYPE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID TYPE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID TYPE_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

  // ------------------------------------------------------------ 角色矩阵

  @Test
  void mapsEachRoleToTheExpectedOpenSearchType() {
    assertThat(typeOf(field("code_s", "STRING").sortable())).isEqualTo("keyword");
    assertThat(typeOf(field("count_i", "INTEGER").sortable())).isEqualTo("long");
    assertThat(typeOf(field("price_d", "DECIMAL").sortable())).isEqualTo("double");
    assertThat(typeOf(field("flag_b", "BOOLEAN").filterable())).isEqualTo("boolean");
    assertThat(typeOf(field("published_dt", "DATE_TIME").filterable())).isEqualTo("date");
    assertThat(typeOf(field("intro_t", "LONG_TEXT").highlight())).isEqualTo("text");
  }

  /** 同时要排序与高亮：必须 text（分词才出片段）+ keyword 子字段（排序需精确值）。 */
  @Test
  void dualRoleTextFieldGetsKeywordSubfield() {
    FieldIndexSpec spec = specOf(field("title_s", "STRING").sortable().highlight());

    assertThat(spec.type()).isEqualTo("text");
    assertThat(spec.hasKeywordSubfield()).isTrue();
    assertThat(spec.key()).isEqualTo("f_title_s");
    assertThat(spec.sortKey()).isEqualTo("f_title_s.kw");
    assertThat(spec.filterKey()).isEqualTo("f_title_s.kw");
  }

  /** 只高亮不排序/过滤：不追加子字段（避免无谓的索引膨胀）。 */
  @Test
  void highlightOnlyTextFieldHasNoKeywordSubfield() {
    FieldIndexSpec spec = specOf(field("intro_t", "LONG_TEXT").highlight());

    assertThat(spec.type()).isEqualTo("text");
    assertThat(spec.hasKeywordSubfield()).isFalse();
    assertThat(spec.sortKey()).isEqualTo("f_intro_t");
  }

  @Test
  void keywordTypedFieldSortKeyIsTheBaseKey() {
    FieldIndexSpec spec = specOf(field("code_s", "STRING").sortable().filterable());

    assertThat(spec.type()).isEqualTo("keyword");
    assertThat(spec.hasKeywordSubfield()).isFalse();
    assertThat(spec.sortKey()).isEqualTo("f_code_s");
  }

  // ------------------------------------------------------------ 不支持的角色

  /**
   * 正文级文本开 {@code sortable} 无业务意义（排序键会是整段正文），且会因 keyword 子字段的
   * {@code ignore_above} 大面积取不到值 —— 如实登记为「角色不支持」，而不是生成一个永远取不到值的键。
   */
  @Test
  void sortableOnLongTextIsReportedAsUnsupportedNotSilentlyDropped() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("body_t", "LONG_TEXT").sortable()));

    assertThat(fx.schema().specs()).isEmpty();
    assertThat(fx.schema().conflicts()).singleElement().satisfies(c -> {
      assertThat(c.kind()).isEqualTo(MappingConflict.KIND_ROLE_UNSUPPORTED);
      assertThat(c.code()).isEqualTo("body_t");
      assertThat(c.key()).isEqualTo("f_body_t");
      assertThat(c.detail()).contains("sortable");
    });
  }

  @Test
  void referenceTypesCannotBeFiltered() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("cover", "MEDIA_REF").filterable()));

    assertThat(fx.schema().specs()).isEmpty();
    assertThat(fx.schema().conflicts()).singleElement()
        .satisfies(c -> assertThat(c.kind()).isEqualTo(MappingConflict.KIND_ROLE_UNSUPPORTED));
  }

  /** 同一字段既开合法角色又开不合法角色：合法角色生效，不合法角色单独登记。 */
  @Test
  void validRoleStillAppliesWhileUnsupportedRoleIsReported() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("intro_t", "LONG_TEXT").highlight().sortable()));

    assertThat(fx.schema().specs()).singleElement().satisfies(s -> {
      assertThat(s.type()).isEqualTo("text");
      assertThat(s.highlight()).isTrue();
      assertThat(s.sortable()).isFalse();
    });
    assertThat(fx.schema().conflicts()).singleElement()
        .satisfies(c -> assertThat(c.kind()).isEqualTo(MappingConflict.KIND_ROLE_UNSUPPORTED));
  }

  // ------------------------------------------------------------ 目标索引闸门

  /**
   * 未开 {@code adminIndex}/{@code publicIndex} 的字段即使开了 {@code sortable} 也不得有字段级键 ——
   * 否则 {@code f_*} 会成为「字段值未进正文、但仍在索引里可精确检索」的旁路。
   */
  @Test
  void fieldsOutsideTargetIndexGetNoFieldLevelKey() {
    FieldDefinitionEntity def = field("secret_s", "STRING").sortable().adminIndex(false).publicIndex(false);

    ContentIndexSchema schema = new ContentIndexSchema(null);
    assertThat(schema.resolve(def, ContentSearchTextAssembler.Target.ADMIN).spec()).isNull();
    assertThat(schema.resolve(def, ContentSearchTextAssembler.Target.PUBLIC).spec()).isNull();
  }

  /** publicIndex=false（sensitive 语义）的字段绝不进访客索引 mapping，管理索引照常。 */
  @Test
  void publicIndexGateBlocksFieldLevelKeysForVisitorIndexOnly() {
    FieldDefinitionEntity def = field("internal_s", "STRING").filterable().publicIndex(false);

    ContentIndexSchema schema = new ContentIndexSchema(null);
    assertThat(schema.resolve(def, ContentSearchTextAssembler.Target.ADMIN).spec()).isNotNull();
    assertThat(schema.resolve(def, ContentSearchTextAssembler.Target.PUBLIC).spec()).isNull();
  }

  /** 同一批字段定义在两个目标索引上派生出的键集合必须不同（两侧 mapping 因此不可互换）。 */
  @Test
  void siteSchemaForAdminAndPublicDifferOnScopeGates() {
    FieldDefinitionEntity adminOnly = field("admin_s", "STRING").filterable().publicIndex(false);
    FieldDefinitionEntity publicOnly = field("public_s", "STRING").filterable().adminIndex(false);

    Fixture admin = fixture(ADMIN, of(TYPE_A, adminOnly, publicOnly));
    Fixture publicFx = fixture(ContentSearchTextAssembler.Target.PUBLIC, of(TYPE_A, adminOnly, publicOnly));

    assertThat(admin.schema().keys()).containsExactly("f_admin_s");
    assertThat(publicFx.schema().keys()).containsExactly("f_public_s");
  }

  // ------------------------------------------------------------ 跨内容类型并集

  /** 同 code 同族（keyword/keyword）：合并角色，二者都不丢。 */
  @Test
  void sameCodeSameFamilyMergesRolesAcrossContentTypes() {
    Fixture fx = fixture(ADMIN,
        of(TYPE_A, field("title_s", "STRING").sortable()),
        of(TYPE_B, field("title_s", "STRING").highlight()));

    assertThat(fx.schema().conflicts()).isEmpty();
    assertThat(fx.schema().specs()).singleElement().satisfies(s -> {
      assertThat(s.key()).isEqualTo("f_title_s");
      assertThat(s.type()).isEqualTo("text");
      assertThat(s.sortable()).isTrue();
      assertThat(s.highlight()).isTrue();
      assertThat(s.hasKeywordSubfield()).isTrue();
    });
  }

  /**
   * 同 code 异族（long vs keyword）：{@code TYPE_CONFLICT}，该键整体剔除。
   *
   * <p>关键在「整体剔除」而非「取一个赢家」：mapping 只允许一种类型，另一内容类型的文档写入
   * 异型值会被 OpenSearch bulk 拒绝，进而让整次重建失败；剔除后字段仍可全文检索，且冲突可见。
   */
  @Test
  void sameCodeDifferentFamilyDropsTheKeyAndReportsConflict() {
    Fixture fx = fixture(ADMIN,
        of(TYPE_A, field("amount", "INTEGER").sortable()),
        of(TYPE_B, field("amount", "STRING").sortable()));

    assertThat(fx.schema().specs()).isEmpty();
    assertThat(fx.schema().conflicts()).singleElement().satisfies(c -> {
      assertThat(c.kind()).isEqualTo(MappingConflict.KIND_TYPE_CONFLICT);
      assertThat(c.code()).isEqualTo("amount");
      assertThat(c.key()).isEqualTo("f_amount");
      assertThat(c.detail()).contains("剔除");
    });
  }

  /** 第三个内容类型与第一个同族时，冲突键不复活（一次冲突即整体剔除）。 */
  @Test
  void conflictedKeyStaysDroppedAfterwards() {
    Fixture fx = fixture(ADMIN,
        of(TYPE_A, field("amount", "INTEGER").sortable()),
        of(TYPE_B, field("amount", "STRING").sortable()),
        of(TYPE_C, field("amount", "INTEGER").sortable()));

    assertThat(fx.schema().specs()).isEmpty();
    assertThat(fx.schema().conflicts()).hasSize(1);
  }

  @Test
  void specsAreSortedByKeyForStableMappingDiff() {
    Fixture fx = fixture(ADMIN,
        of(TYPE_A, field("z_s", "STRING").sortable(), field("a_i", "INTEGER").sortable()));

    assertThat(fx.schema().specs()).extracting(FieldIndexSpec::key).containsExactly("f_a_i", "f_z_s");
  }

  // ------------------------------------------------------------ 文档取值

  @Test
  void valuesAreTypePreserving() {
    Fixture fx = fixture(ADMIN, of(TYPE_A,
        field("code_s", "STRING").sortable(),
        field("count_i", "INTEGER").sortable(),
        field("price_d", "DECIMAL").sortable(),
        field("flag_b", "BOOLEAN").filterable(),
        field("published_dt", "DATE_TIME").filterable()));
    String json = "{\"code_s\":\"A-1\",\"count_i\":7,\"price_d\":12.5,"
        + "\"flag_b\":true,\"published_dt\":\"2026-09-20T03:00:00Z\"}";

    Map<String, Object> values = fx.valuesFor(TYPE_A, json);

    assertThat(values).containsEntry("f_code_s", "A-1")
        .containsEntry("f_count_i", 7L)
        .containsEntry("f_price_d", 12.5d)
        .containsEntry("f_flag_b", true)
        .containsEntry("f_published_dt", "2026-09-20T03:00:00Z");
  }

  /** 数值以字符串形式存储（表单提交常见形态）时仍按声明类型转换，保证与 mapping 一致。 */
  @Test
  void numericStringsAreCoercedToDeclaredType() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("count_i", "INTEGER").sortable()));

    assertThat(fx.valuesFor(TYPE_A, "{\"count_i\":\"42\"}")).containsEntry("f_count_i", 42L);
  }

  /** 类型不符时该键本次不写（不抛错阻断重建）。 */
  @Test
  void unconvertibleValueIsSkippedInsteadOfFailingTheRebuild() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("count_i", "INTEGER").sortable()));

    assertThat(fx.valuesFor(TYPE_A, "{\"count_i\":\"not-a-number\"}")).isEmpty();
  }

  /** 高亮字段取清洗后文本：片段里不能出现 HTML 标签（与 body 装配同口径）。 */
  @Test
  void highlightFieldsStoreCleanedText() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("intro_t", "LONG_TEXT").highlight()));

    Map<String, Object> values =
        fx.valuesFor(TYPE_A, "{\"intro_t\":\"<p>段落一</p>\\n<p>段落二 <b>加粗</b></p>\"}");

    assertThat((String) values.get("f_intro_t"))
        .isEqualTo("段落一 段落二 加粗")
        .doesNotContain("<p>").doesNotContain("<b>");
  }

  /** keyword 字段保留原值（排序/过滤需精确匹配，不得被清洗改写）。 */
  @Test
  void keywordFieldsKeepRawValue() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("code_s", "STRING").sortable()));

    assertThat(fx.valuesFor(TYPE_A, "{\"code_s\":\"  A  B \"}")).containsEntry("f_code_s", "A  B");
  }

  /** 冲突被剔除的键绝不写入文档：写了就会与 mapping 类型不符并被 bulk 拒绝。 */
  @Test
  void droppedConflictKeyIsNeverWrittenIntoDocuments() {
    Fixture fx = fixture(ADMIN,
        of(TYPE_A, field("amount", "INTEGER").sortable()),
        of(TYPE_B, field("amount", "STRING").sortable()));

    assertThat(fx.valuesFor(TYPE_A, "{\"amount\":7}")).doesNotContainKey("f_amount").isEmpty();
  }

  @Test
  void nullAndBlankValuesAreNotWritten() {
    Fixture fx = fixture(ADMIN, of(TYPE_A,
        field("code_s", "STRING").sortable(), field("other_s", "STRING").sortable()));

    assertThat(fx.valuesFor(TYPE_A, "{\"code_s\":null,\"other_s\":\"   \"}")).isEmpty();
  }

  @Test
  void invalidJsonYieldsNoValues() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("code_s", "STRING").sortable()));

    assertThat(fx.valuesFor(TYPE_A, "{not-json")).isEmpty();
    assertThat(fx.valuesFor(TYPE_A, "  ")).isEmpty();
    assertThat(fx.valuesFor(null, "{\"code_s\":\"x\"}")).isEmpty();
  }

  /** i18n 对象值（{@code {"zh":"甲","en":"乙"}}）取全部文本叶子，与 body 装配同口径。 */
  @Test
  void i18nObjectValuesYieldAllTextLeaves() {
    Fixture fx = fixture(ADMIN, of(TYPE_A, field("topic_t", "LONG_TEXT").highlight()));

    Map<String, Object> values = fx.valuesFor(TYPE_A, "{\"topic_t\":{\"zh\":\"中文标题\",\"en\":\"English\"}}");

    assertThat((String) values.get("f_topic_t")).isEqualTo("中文标题 English");
  }

  /** 仓储缺失（未接线/单测桩）时安全降级为空，不影响既有重建路径。 */
  @Test
  void missingRepositoryDegradesToNothing() {
    ContentIndexSchema schema = new ContentIndexSchema(null);

    assertThat(schema.merge(List.of(TYPE_A), ContentSearchTextAssembler.Target.ADMIN))
        .isEqualTo(SiteSchema.empty());
    assertThat(schema.valuesFor(SiteSchema.empty(), TYPE_A, "{\"a\":1}")).isEmpty();
    assertThat(schema.siteSchema(SITE_ID, ContentSearchTextAssembler.Target.ADMIN))
        .isEqualTo(SiteSchema.empty());
  }

  // ------------------------------------------------------------ helpers

  private static final ContentSearchTextAssembler.Target ADMIN = ContentSearchTextAssembler.Target.ADMIN;
  private static final ContentSearchTextAssembler.Target PUBLIC = ContentSearchTextAssembler.Target.PUBLIC;

  /** 字段定义的可读构造器（含属性角色链）。 */
  private static final class Def extends FieldDefinitionEntity {
    private Def(String code, String type) {
      setId(UUID.randomUUID());
      setContentTypeId(TYPE_A);
      setCode(code);
      setName(code);
      setFieldType(type);
      setAdminIndex(true);
      setPublicIndex(true);
      setSortOrder(0);
    }

    private Def sortable() { setSortable(true); return this; }

    private Def filterable() { setFilterable(true); return this; }

    private Def highlight() { setHighlight(true); return this; }

    private Def adminIndex(boolean value) { setAdminIndex(value); return this; }

    private Def publicIndex(boolean value) { setPublicIndex(value); return this; }

    private Def inType(UUID contentTypeId) { setContentTypeId(contentTypeId); return this; }
  }

  private static Def field(String code, String type) {
    return new Def(code, type);
  }

  private static List<FieldDefinitionEntity> of(UUID contentTypeId, FieldDefinitionEntity... fields) {
    List<FieldDefinitionEntity> defs = new ArrayList<>();
    for (FieldDefinitionEntity f : fields) {
      ((Def) f).inType(contentTypeId);
      defs.add(f);
    }
    return defs;
  }

  /** 已派生 schema + 其仓储桩（供值抽取用例复用同一份字段定义）。 */
  private record Fixture(SiteSchema schema, FieldDefinitionRepository repo) {
    Map<String, Object> valuesFor(UUID contentTypeId, String json) {
      return new ContentIndexSchema(repo).valuesFor(schema, contentTypeId, json);
    }
  }

  @SafeVarargs
  private static Fixture fixture(ContentSearchTextAssembler.Target target,
                                 List<FieldDefinitionEntity>... groups) {
    List<UUID> ids = new ArrayList<>();
    List<FieldDefinitionEntity> all = new ArrayList<>();
    Map<UUID, List<FieldDefinitionEntity>> byType = new LinkedHashMap<>();
    for (List<FieldDefinitionEntity> group : groups) {
      for (FieldDefinitionEntity def : group) {
        UUID id = def.getContentTypeId();
        if (!ids.contains(id)) ids.add(id);
        byType.computeIfAbsent(id, k -> new ArrayList<>()).add(def);
        all.add(def);
      }
    }
    FieldDefinitionRepository repo = mock(FieldDefinitionRepository.class);
    when(repo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any())).thenReturn(all);
    when(repo.findByContentTypeIdOrderBySortOrder(any())).thenAnswer(inv -> {
      UUID id = inv.getArgument(0);
      return byType.getOrDefault(id, List.of());
    });
    return new Fixture(new ContentIndexSchema(repo).merge(ids, target), repo);
  }

  private static String typeOf(FieldDefinitionEntity def) {
    return specOf(def).type();
  }

  private static FieldIndexSpec specOf(FieldDefinitionEntity def) {
    FieldIndexSpec spec =
        new ContentIndexSchema(null).resolve(def, ADMIN).spec();
    assertThat(spec).as("字段 %s(%s) 应解析出字段级键", def.getCode(), def.getFieldType()).isNotNull();
    return spec;
  }

  // ------------------------------------------------------------ 映射指纹（FR-MODEL-006）

  /**
   * 指纹语义：只随**映射形态**变化 —— 同形态重复计算必须稳定（否则每次模型发布都会误报「需重建」），
   * 形态一变（这里把 filterable 关掉）指纹必须变（否则属性变更会静默漏检）。
   */
  @Test
  void fingerprintIsStableForSameShapeAndChangesWithShape() {
    ContentIndexSchema schema = new ContentIndexSchema(
        fieldRepo(of(TYPE_A, field("title_s", "STRING").sortable().filterable())), typeRepo(TYPE_A));
    String before = schema.fingerprint(SITE_ID, ADMIN);
    assertThat(before).as("模型未变时指纹必须稳定").isEqualTo(schema.fingerprint(SITE_ID, ADMIN));

    ContentIndexSchema changed = new ContentIndexSchema(
        fieldRepo(of(TYPE_A, field("title_s", "STRING").sortable())), typeRepo(TYPE_A));
    assertThat(changed.fingerprint(SITE_ID, ADMIN)).isNotEqualTo(before);
  }

  /** 冲突也纳入指纹：冲突解除（键重新可用）同样要触发重建。 */
  @Test
  void fingerprintIncludesConflicts() {
    ContentIndexSchema conflicting = new ContentIndexSchema(fieldRepo(
        of(TYPE_A, field("shared", "STRING").sortable()),
        of(TYPE_B, field("shared", "INTEGER").sortable())), typeRepo(TYPE_A, TYPE_B));
    assertThat(conflicting.siteSchema(SITE_ID, ADMIN).conflicts()).isNotEmpty();

    ContentIndexSchema consistent = new ContentIndexSchema(fieldRepo(
        of(TYPE_A, field("shared", "STRING").sortable()),
        of(TYPE_B, field("shared", "STRING").sortable())), typeRepo(TYPE_A, TYPE_B));
    assertThat(consistent.fingerprint(SITE_ID, ADMIN))
        .isNotEqualTo(conflicting.fingerprint(SITE_ID, ADMIN));
  }

  /**
   * 目标索引隔离：只在 {@code adminIndex} 里的字段不得进入访客索引指纹 —— 否则访客侧会
   * 以「指纹相同」误判为无需重建，而访客索引里其实没有这个键。
   */
  @Test
  void fingerprintDiffersPerTarget() {
    ContentIndexSchema schema = new ContentIndexSchema(fieldRepo(
        of(TYPE_A, field("secret_s", "STRING").sortable().publicIndex(false))), typeRepo(TYPE_A));
    String admin = schema.fingerprint(SITE_ID, ADMIN);
    String publicFp = schema.fingerprint(SITE_ID, PUBLIC);
    assertThat(admin).as("字段进了管理索引 → 管理侧有形态可比对").isNotNull();
    assertThat(publicFp).as("同字段未开访客索引 → 访客侧指纹不得与之一致").isNotEqualTo(admin);
  }

  /** schema 不可得（仓储缺失）时返回 null：不得被当作「空 schema 已生效」而触发空索引重建。 */
  @Test
  void fingerprintIsNullWhenSchemaUnavailable() {
    assertThat(new ContentIndexSchema(null).fingerprint(SITE_ID, ADMIN)).isNull();
    assertThat(new ContentIndexSchema(fieldRepo(List.of())).fingerprint(null, ADMIN)).isNull();
  }

  // ------------------------------------------------ 附加映射形态（OBS-MSRC-07）

  private static final String SHAPE_1024 = "embedding|1024|bge-m3@1024|bge-m3";

  /**
   * OBS-MSRC-07：向量字段形态（维度/模型）也是 mapping 的一部分，必须并入同一指纹 ——
   * 否则「索引建好之后才配 embedding 模型」这种形态变化对映射基线完全不可见，运维只会看到
   * 首次检索/问答 500（{@code Field 'embedding' is not knn_vector type}），无从知道该做什么。
   */
  @Test
  void fingerprintIncludesAdditionalMappingShape() {
    ContentIndexSchema schema = new ContentIndexSchema(
        fieldRepo(of(TYPE_A, field("title_s", "STRING").sortable())), typeRepo(TYPE_A));

    String fieldOnly = schema.fingerprint(SITE_ID, ADMIN, null);
    String withVector = schema.fingerprint(SITE_ID, ADMIN, SHAPE_1024);
    String withOtherDimension = schema.fingerprint(SITE_ID, ADMIN, "embedding|512|bge-m3@512|bge-m3");

    assertThat(withVector).as("启用向量能力 → 形态变化 → 指纹必须变").isNotEqualTo(fieldOnly);
    assertThat(withOtherDimension).as("维度变化同样要触发重建（knn_vector 维度不可改）")
        .isNotEqualTo(withVector);
    // 稳定性：同形态重复计算必须一致（否则每次读取都会误报「需重建」）
    assertThat(schema.fingerprint(SITE_ID, ADMIN, SHAPE_1024)).isEqualTo(withVector);
  }

  /**
   * 无附加形态（{@code null}/空白）时指纹必须与旧口径**逐字节一致** —— 这是「纯关键词站点
   * 升级后不得出现一次假『需重建』」的回归护栏（旧基线是用同一份 canonical 算出来的）。
   */
  @Test
  void blankAdditionalShapeKeepsLegacyFingerprint() {
    ContentIndexSchema schema = new ContentIndexSchema(
        fieldRepo(of(TYPE_A, field("title_s", "STRING").sortable())), typeRepo(TYPE_A));

    String legacy = schema.fingerprint(SITE_ID, ADMIN);
    assertThat(schema.fingerprint(SITE_ID, ADMIN, null)).isEqualTo(legacy);
    assertThat(schema.fingerprint(SITE_ID, ADMIN, "   ")).isEqualTo(legacy);
  }

  /**
   * 提供附加形态时，「没有任何字段级键」不再等于「不可得」：此时映射里仍有向量字段，
   * 把它标为过期是有意义的（索引确实要重建才会有 {@code embedding} 键）。
   */
  @Test
  void additionalShapeYieldsFingerprintEvenWithoutFieldLevelKeys() {
    ContentIndexSchema schema = new ContentIndexSchema(fieldRepo(List.of()), typeRepo(TYPE_A));

    assertThat(schema.fingerprint(SITE_ID, ADMIN, null)).as("无字段级键且无向量形态 → 不可得").isNull();
    assertThat(schema.fingerprint(SITE_ID, ADMIN, SHAPE_1024)).as("有向量形态 → 仍应给出可比对基线")
        .isNotNull();
  }

  private static com.powercms.cms.model.repo.ContentTypeRepository typeRepo(UUID... typeIds) {
    com.powercms.cms.model.entity.ContentTypeEntity[] types =
        new com.powercms.cms.model.entity.ContentTypeEntity[typeIds.length];
    for (int i = 0; i < typeIds.length; i++) {
      com.powercms.cms.model.entity.ContentTypeEntity type =
          new com.powercms.cms.model.entity.ContentTypeEntity();
      type.setId(typeIds[i]);
      type.setSiteId(SITE_ID);
      types[i] = type;
    }
    com.powercms.cms.model.repo.ContentTypeRepository repo =
        mock(com.powercms.cms.model.repo.ContentTypeRepository.class);
    when(repo.findBySiteIdOrderByCodeAsc(SITE_ID)).thenReturn(List.of(types));
    return repo;
  }

  @SafeVarargs
  private static FieldDefinitionRepository fieldRepo(List<FieldDefinitionEntity>... groups) {
    List<FieldDefinitionEntity> defs = new ArrayList<>();
    for (List<FieldDefinitionEntity> group : groups) defs.addAll(group);
    FieldDefinitionRepository repo = mock(FieldDefinitionRepository.class);
    when(repo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(any())).thenReturn(defs);
    return repo;
  }
}
