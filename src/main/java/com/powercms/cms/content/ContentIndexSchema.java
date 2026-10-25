package com.powercms.cms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 字段级索引 schema 派生（FR-MODEL-006 / AC-CM-009）。
 *
 * <p>{@code ContentSearchTextAssembler} 负责「哪些字段文本进入检索语料」（{@code adminIndex}/
 * {@code publicIndex}）；本类负责「哪些字段可被独立排序/过滤/高亮」（{@code sortable}/
 * {@code filterable}/{@code highlight}）—— 即 §5.3 五属性中**要求字段可寻址**的三项。
 *
 * <p><b>为何放在 content 包</b>：需要读 {@code field_definition}/{@code content_type}（model），
 * 而 Spring Modulith 边界为 {@code search → content → model}。search 模块经本类取 schema，
 * 不直接依赖 model（与 {@link ContentSearchTextAssembler} 同款理由）。
 *
 * <p><b>索引是「每站点一索引」，schema 必须跨内容类型并集</b>：同一站点所有内容类型共用
 * {@code powercms-admin-<env>-<siteId>}，而字段 code 只保证**内容类型内**唯一
 * （{@code field_definition} 的唯一约束是 {@code (content_type_id, code)}）。因此：
 * <ul>
 *   <li>同 code 同「基础类型族」→ **合并角色**（并集，二者不冲突）；</li>
 *   <li>同 code 不同族（如 {@code keyword} vs {@code long}）→ **映射冲突**：该键**整体剔除**，
 *       并登记 {@link MappingConflict}。剔除而非「取一个赢家」是刻意的：mapping 只允许一种类型，
 *       另一内容类型的文档写入异型值会被 OpenSearch bulk 拒绝（{@code requireBulkOk} 直接抛错），
 *       导致**整次重建失败**；剔除后冲突键可检索但不可排序/过滤，且**冲突可见**（不静默丢弃）。</li>
 * </ul>
 *
 * <p><b>与目标索引的关系</b>：字段级键只在字段**已进入该目标索引**时生成
 * （管理索引看 {@code adminIndex}、访客索引看 {@code publicIndex}）。否则「未开访客索引」的
 * 敏感字段会以 {@code f_x} 精确值形式把原值写进访客索引 —— 那正是 FR-CM-PROP-008 /
 * FR-AI-RAG-006 要拦的。因此任一角色都不放宽目标索引约束。
 */
@Component
public class ContentIndexSchema {

  private static final ObjectMapper OM = new ObjectMapper();

  /** 字段类型 → 索引键的「基础类型族」；缺省（结构控件/各种 REF/JSON 等）不支持字段级索引。 */
  private static final Map<String, String> FAMILY_BY_TYPE = Map.ofEntries(
      Map.entry("STRING", "keyword"),
      Map.entry("ENUM", "keyword"),
      Map.entry("COLOR", "keyword"),
      Map.entry("CONSTANT", "keyword"),
      Map.entry("CUSTOM_CODE", "keyword"),
      Map.entry("INTEGER", "long"),
      Map.entry("DECIMAL", "double"),
      Map.entry("BOOLEAN", "boolean"),
      Map.entry("DATE_TIME", "date"),
      Map.entry("LONG_TEXT", "text"),
      Map.entry("RICH_TEXT", "text"),
      Map.entry("BLOCK_EDITOR", "text"));

  /**
   * 可作为**排序**条件的字段类型（§5.3「字段可作为搜索结果排序条件」）。
   *
   * <p>刻意排除 LONG_TEXT/RICH_TEXT/BLOCK_EDITOR：正文级文本作排序键既无业务意义，
   * 又会因 {@link FieldIndexSpec#KEYWORD_IGNORE_ABOVE} 而大面积取不到值 —— 这类组合按
   * {@link MappingConflict#KIND_ROLE_UNSUPPORTED} 如实登记，而不是静默无效。
   */
  static final Set<String> SORTABLE_TYPES = Set.of(
      "STRING", "ENUM", "COLOR", "CONSTANT", "CUSTOM_CODE",
      "INTEGER", "DECIMAL", "BOOLEAN", "DATE_TIME");

  /** 可作为**过滤**条件的字段类型（§5.3「字段可作为搜索过滤条件」）。 */
  static final Set<String> FILTERABLE_TYPES = SORTABLE_TYPES;

  /** 可**高亮**的字段类型（§5.3「搜索结果中高亮该字段值」）——仅文本语义字段可出片段。 */
  static final Set<String> HIGHLIGHT_TYPES = Set.of(
      "STRING", "LONG_TEXT", "RICH_TEXT", "ENUM", "BLOCK_EDITOR");

  private final FieldDefinitionRepository fieldRepo;
  private final ContentTypeRepository typeRepo;

  /**
   * 生产构造：字段定义（必需）+ 内容类型（{@link #siteSchema} 需要按站点列出内容类型）。
   */
  @org.springframework.beans.factory.annotation.Autowired
  public ContentIndexSchema(FieldDefinitionRepository fieldRepo, ContentTypeRepository typeRepo) {
    this.fieldRepo = fieldRepo;
    this.typeRepo = typeRepo;
  }

  /** 无内容类型仓储的构造（单测 / 只用到 {@link #resolve} 与 {@link #valuesFor} 的场景）。 */
  public ContentIndexSchema(FieldDefinitionRepository fieldRepo) {
    this(fieldRepo, null);
  }

  /** 冲突/不支持项（TC-M66-023「映射冲突进入失败队列而非静默丢弃」的载体）。 */
  public record MappingConflict(String code, String key, String kind, String detail) {
    /** 同一 code 在多个内容类型中解析出不同基础类型族 —— 该键被整体剔除。 */
    public static final String KIND_TYPE_CONFLICT = "TYPE_CONFLICT";
    /** 字段类型不支持所请求的角色（如 LONG_TEXT 开 sortable）—— 该角色被忽略。 */
    public static final String KIND_ROLE_UNSUPPORTED = "ROLE_UNSUPPORTED";

    @Override
    public String toString() {
      return kind + "[" + code + "@" + key + "] " + detail;
    }
  }

  /**
   * 站点某个目标索引的字段级 schema（跨内容类型并集）。
   *
   * @param specs     最终生效的字段键（按内容类型 code、字段 sortOrder 稳定排序）
   * @param conflicts 冲突与不支持角色（剔除/忽略的原因，供失败队列与告警使用）
   */
  public record SiteSchema(List<FieldIndexSpec> specs, List<MappingConflict> conflicts) {

    public static SiteSchema empty() {
      return new SiteSchema(List.of(), List.of());
    }

    public Optional<FieldIndexSpec> byKey(String key) {
      return specs.stream().filter(s -> s.key().equals(key)).findFirst();
    }

    public Set<String> keys() {
      Set<String> keys = new LinkedHashSet<>();
      specs.forEach(s -> keys.add(s.key()));
      return keys;
    }

    /** 按 code 查（同 code 在并集后唯一）。 */
    public Optional<FieldIndexSpec> byCode(String code) {
      return specs.stream().filter(s -> s.code().equals(code)).findFirst();
    }
  }

  /** 单个字段解析出的角色（含被忽略的角色原因）。 */
  record RoleResolution(FieldIndexSpec spec, String family, List<String> unsupportedRoles) {}

  // ============================================================ 站点 schema

  /**
   * 站点在指定目标索引上的字段级 schema。
   *
   * @param target 目标索引（管理/访客）；决定字段是否**可进入**该索引（再看是否开角色属性）
   */
  public SiteSchema siteSchema(UUID siteId, ContentSearchTextAssembler.Target target) {
    List<ContentTypeEntity> types = typeRepo == null || siteId == null
        ? List.of() : typeRepo.findBySiteIdOrderByCodeAsc(siteId);
    return merge(types.stream().map(ContentTypeEntity::getId).toList(), target);
  }

  /**
   * schema 指纹（FR-MODEL-006 / TC-M66-023）：把**映射形态**固化为稳定摘要，
   * 供「模型属性变更 → 索引需重建」的比对。
   *
   * <p>只含生效键（键名/类型/三角色）与冲突项，**不含内容数据** —— 指纹在两次重建之间稳定，
   * 不会因为发布内容而反复提示重建。冲突项也纳入指纹：冲突解除（例如把两个内容类型的
   * 同 code 字段改成同类型）同样需要重建 mapping 才能让该键重新可排序。
   *
   * <p>{@link SiteSchema#empty()} 时返回 {@code null}：schema 不可得（仓储缺失/查询失败）不得被
   * 当作「空 schema 已生效」——那会诱导调用方去重建一个空索引。
   *
   * <p><b>空 schema 的另一来源是「模型把所有字段级属性都关掉」</b>：此时指纹同样为 {@code null}，
   * 调用方按「不可得」跳过比对（索引里残留的 {@code f_*} 键不会被标记为过期）。这是可接受的：
   * 模型已不再声明任何字段级键，查询侧对这些字段直接 400（{@code SEARCH_SORT_UNSUPPORTED}），
   * 不存在「静默给出错误结果」的路径；把清空动作也当作变更去催重建反而会让「撤销一个属性」
   * 变成一次全量重建的强制动作。
   */
  public String fingerprint(UUID siteId, ContentSearchTextAssembler.Target target) {
    return fingerprint(siteId, target, null);
  }

  /**
   * 带**附加映射形态**的指纹（OBS-MSRC-07）。
   *
   * <p>索引 mapping 不由内容模型独占：向量字段（{@code embedding} 的 {@code knn_vector} 与维度）、
   * 分词器、以及 {@code embedding_version}/{@code embedding_model} 元数据键同样属于 mapping。这些
   * 形态由 {@code search} 模块（embedding 能力）决定，本模块不该反向依赖它 —— 于是把「附加形态」
   * 作为一段**不透明文本**并入同一份 canonical 后再哈希：任一来源的形态变化都会改变这个指纹，
   * 而本模块无需理解其内容。
   *
   * @param additionalMappingShape 调用方贡献的附加映射形态（如 {@code embedding|1024|bge-m3@1024|bge-m3}）；
   *                               {@code null}/空白 = 无附加形态，结果与
   *                               {@link #fingerprint(UUID, ContentSearchTextAssembler.Target)} 完全一致
   *
   * <p><b>空 schema 的例外</b>：提供了附加形态时，即使站点没有任何字段级键（{@code specs} 与
   * {@code conflicts} 皆空）也**不**返回 {@code null} —— 此时映射里仍有向量字段，被标为过期是有意义的
   * （例如「所有字段级属性都关掉 + 刚启用 embedding」，索引确实需要重建才能有 {@code embedding} 键）。
   */
  public String fingerprint(UUID siteId, ContentSearchTextAssembler.Target target,
                            String additionalMappingShape) {
    if (typeRepo == null || fieldRepo == null || siteId == null) return null;
    SiteSchema schema;
    try {
      schema = siteSchema(siteId, target);
    } catch (RuntimeException ex) {
      return null;
    }
    boolean hasAdditionalShape = additionalMappingShape != null && !additionalMappingShape.isBlank();
    if (schema.specs().isEmpty() && schema.conflicts().isEmpty() && !hasAdditionalShape) return null;
    StringBuilder canonical = new StringBuilder();
    for (FieldIndexSpec spec : schema.specs()) {
      canonical.append(spec.key()).append('|').append(spec.type()).append('|')
          .append(spec.sortable() ? '1' : '0')
          .append(spec.filterable() ? '1' : '0')
          .append(spec.highlight() ? '1' : '0').append('\n');
    }
    canonical.append("--conflicts--\n");
    schema.conflicts().stream().map(MappingConflict::toString).sorted()
        .forEach(c -> canonical.append(c).append('\n'));
    if (hasAdditionalShape) {
      canonical.append("--additional-mapping--\n").append(additionalMappingShape.trim()).append('\n');
    }
    return sha256Hex(canonical.toString());
  }

  /** SHA-256 摘要（算法缺失时退化为内容哈希，仅影响指纹长度，不影响比对语义）。 */
  static String sha256Hex(String canonical) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException ex) {
      return Integer.toHexString(canonical.hashCode());
    }
  }

  /** 不依赖仓储的合并入口（供单测与「已知字段定义集合」场景直接调用）。 */
  public SiteSchema merge(Collection<UUID> contentTypeIds, ContentSearchTextAssembler.Target target) {
    ContentSearchTextAssembler.Target effective =
        target == null ? ContentSearchTextAssembler.Target.ADMIN : target;
    if (fieldRepo == null || contentTypeIds == null || contentTypeIds.isEmpty()) return SiteSchema.empty();

    Map<String, FieldIndexSpec> merged = new LinkedHashMap<>();
    Map<String, Set<String>> owners = new LinkedHashMap<>();
    List<MappingConflict> conflicts = new ArrayList<>();
    Set<String> conflictedKeys = new LinkedHashSet<>();

    List<FieldDefinitionEntity> definitions =
        fieldRepo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(contentTypeIds);
    for (FieldDefinitionEntity def : definitions) {
      RoleResolution res = resolve(def, effective);
      // 不支持的角色**只要被请求过就登记** —— 包括「字段同时开了合法角色」的部分生效场景，
      // 否则用户会在 UI 上看到勾选已保存、却没有任何提示说明该勾选不生效。
      res.unsupportedRoles().forEach(role -> conflicts.add(new MappingConflict(
          def.getCode(), FieldIndexSpec.keyOf(def.getCode()),
          MappingConflict.KIND_ROLE_UNSUPPORTED,
          "字段类型 " + def.getFieldType() + " 不支持 " + role + "（该角色不生效）")));
      if (res.spec() == null) continue;
      String key = res.spec().key();
      owners.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(def.getFieldType());
      if (conflictedKeys.contains(key)) continue;
      FieldIndexSpec existing = merged.get(key);
      if (existing == null) {
        merged.put(key, res.spec());
        continue;
      }
      if (compatible(existing.type(), res.spec().type())) {
        merged.put(key, combine(existing, res.spec()));
      } else {
        merged.remove(key);
        conflictedKeys.add(key);
        conflicts.add(new MappingConflict(def.getCode(), key,
            MappingConflict.KIND_TYPE_CONFLICT,
            "同一 code 在多个内容类型中解析出不同索引类型（"
                + String.join("/", owners.get(key)) + " → " + existing.type()
                + "，本次 " + def.getFieldType() + " → " + res.spec().type()
                + "）；该键已整体剔除，字段可检索但不可排序/过滤"));
      }
    }
    // 按键名排序：结果与内容类型 UUID 顺序解耦，便于快照式断言与 mapping diff
    List<FieldIndexSpec> specs = new ArrayList<>(merged.values());
    specs.sort(java.util.Comparator.comparing(FieldIndexSpec::key));
    return new SiteSchema(List.copyOf(specs), List.copyOf(conflicts));
  }

  // ============================================================ 单字段解析

  /** 解析单个字段定义在该目标索引下的键规格；不承担任何角色或未进入目标索引时返回 null。 */
  RoleResolution resolve(FieldDefinitionEntity def, ContentSearchTextAssembler.Target target) {
    ContentSearchTextAssembler.Target effective =
        target == null ? ContentSearchTextAssembler.Target.ADMIN : target;
    if (def == null || def.getCode() == null || def.getCode().isBlank()) {
      return new RoleResolution(null, null, List.of());
    }
    // 目标索引闸门优先：未开目标索引的字段一律不生成字段级键（不得借 f_* 绕过 publicIndex 隔离）
    if (!effective.includes(def)) return new RoleResolution(null, null, List.of());

    String family = FAMILY_BY_TYPE.get(def.getFieldType());
    List<String> unsupported = new ArrayList<>();
    boolean sort = def.isSortable() && SORTABLE_TYPES.contains(def.getFieldType());
    if (def.isSortable() && !sort) unsupported.add(roleLabel("sortable", family, def.getFieldType()));
    boolean filter = def.isFilterable() && FILTERABLE_TYPES.contains(def.getFieldType());
    if (def.isFilterable() && !filter) unsupported.add(roleLabel("filterable", family, def.getFieldType()));
    boolean highlight = def.isHighlight() && HIGHLIGHT_TYPES.contains(def.getFieldType());
    if (def.isHighlight() && !highlight) {
      unsupported.add(roleLabel("highlight", family, def.getFieldType()));
    }

    // 类型不支持字段级索引且三个角色都没落到：无键可生成
    if (!sort && !filter && !highlight) return new RoleResolution(null, family, unsupported);
    if (family == null) return new RoleResolution(null, null, unsupported);

    // 高亮必须走 text（要分词才出片段）；其余情况直接用基础类型。
    // HIGHLIGHT_TYPES ⊆ {keyword, text} 两族，故 highlight=true 时 family 必为这两者之一。
    String type = highlight || "text".equals(family) ? "text" : family;
    FieldIndexSpec spec = new FieldIndexSpec(def.getCode(), FieldIndexSpec.keyOf(def.getCode()),
        type, sort, filter, highlight);
    return new RoleResolution(spec, family, unsupported);
  }

  /** 角色不被支持时的可读原因（含「字段类型本身不支持字段级索引」的场景）。 */
  private static String roleLabel(String role, String family, String fieldType) {
    return family == null ? role + "（字段类型 " + fieldType + " 不支持字段级索引）" : role;
  }

  /** 两个类型是否可以共存于同一键：keyword 与 text 可并（text 胜出），其余必须完全相同。 */
  private static boolean compatible(String a, String b) {
    if (Objects.equals(a, b)) return true;
    return isTextish(a) && isTextish(b);
  }

  private static boolean isTextish(String type) {
    return "text".equals(type) || "keyword".equals(type);
  }

  private static FieldIndexSpec combine(FieldIndexSpec a, FieldIndexSpec b) {
    boolean highlight = a.highlight() || b.highlight();
    boolean sortable = a.sortable() || b.sortable();
    boolean filterable = a.filterable() || b.filterable();
    // keyword + text → text（高亮需要分词），此时 highlight 必为 true（见 resolve 的类型推导）
    String type = isTextish(a.type()) && isTextish(b.type())
        ? (highlight ? "text" : "keyword")
        : a.type();
    return new FieldIndexSpec(a.code(), a.key(), type, sortable, filterable, highlight);
  }

  // ============================================================ 文档取值

  /**
   * 抽取写入索引文档的**字段级键值**（类型保真：long/double/boolean 用原生 JSON 类型，
   * date/keyword 用字符串，text 用清洗后文本）。
   *
   * <p>只写 {@code schema} 里存在的键 —— 冲突被剔除的键若仍写入，会因与 mapping 类型不符
   * 导致 OpenSearch bulk 拒绝、整次重建失败。
   *
   * @param schema            站点 schema（决定哪些键可写、按哪个类型写）
   * @param contentTypeId     本条内容的内容类型
   * @param fieldValuesJson   当前版本的 fieldValues JSON
   */
  public Map<String, Object> valuesFor(SiteSchema schema, UUID contentTypeId, String fieldValuesJson) {
    if (schema == null || schema.specs().isEmpty() || fieldRepo == null || contentTypeId == null) {
      return Map.of();
    }
    String raw = fieldValuesJson == null ? "" : fieldValuesJson.trim();
    if (raw.isBlank()) return Map.of();
    JsonNode root;
    try {
      root = OM.readTree(raw);
    } catch (Exception ex) {
      return Map.of();
    }
    if (root == null || !root.isObject()) return Map.of();

    Map<String, Object> values = new LinkedHashMap<>();
    for (FieldDefinitionEntity def : fieldRepo.findByContentTypeIdOrderBySortOrder(contentTypeId)) {
      if (def.getCode() == null) continue;
      String key = FieldIndexSpec.keyOf(def.getCode());
      FieldIndexSpec spec = schema.byKey(key).orElse(null);
      if (spec == null) continue;
      JsonNode node = root.get(def.getCode());
      if (node == null || node.isNull()) continue;
      Object value = coerce(node, spec.type());
      if (value == null) continue;
      if (value instanceof String s && s.isBlank()) continue;
      if (value instanceof Collection<?> c && c.isEmpty()) continue;
      values.put(key, value);
    }
    return values;
  }

  /** 按目标映射类型转换 JSON 值；不可转换（类型不符）时返回 null（该键本次不写，不抛错阻断重建）。 */
  static Object coerce(JsonNode node, String type) {
    if (node == null || node.isNull()) return null;
    if (node.isArray()) {
      List<Object> out = new ArrayList<>();
      node.forEach(item -> {
        Object single = coerce(item, type);
        if (single != null) {
          if (single instanceof Collection<?> nested) out.addAll(nested);
          else out.add(single);
        }
      });
      return out;
    }
    return switch (type) {
      case "long" -> node.isNumber() ? (Object) node.asLong() : parseLong(node.asText(null));
      case "double" -> node.isNumber() ? (Object) node.asDouble() : parseDouble(node.asText(null));
      case "boolean" -> node.isBoolean() ? (Object) node.asBoolean() : parseBoolean(node.asText(null));
      case "date" -> textOrNull(node);
      case "text" -> cleanOrNull(node);
      default -> textOrNull(node);
    };
  }

  private static Object parseLong(String text) {
    try {
      return text == null || text.isBlank() ? null : Long.valueOf(text.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static Object parseDouble(String text) {
    try {
      return text == null || text.isBlank() ? null : Double.valueOf(text.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static Object parseBoolean(String text) {
    if (text == null || text.isBlank()) return null;
    String t = text.trim();
    if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
    if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
    return null;
  }

  /** 结构控件/引用类字段的对象取值：取全部文本叶子（i18n 值等）。 */
  private static String textOrNull(JsonNode node) {
    if (node.isValueNode()) {
      String s = node.asText("");
      return s.isBlank() ? null : s.trim();
    }
    String joined = joinLeaves(node);
    return joined.isBlank() ? null : joined;
  }

  /** 高亮用的可读文本：剥离 HTML/Markdown 记号并压缩空白（与 body 装配同口径）。 */
  private static String cleanOrNull(JsonNode node) {
    String raw = textOrNull(node);
    if (raw == null) return null;
    String cleaned = ContentSearchTextAssembler.cleanText(raw);
    return cleaned.isBlank() ? null : cleaned;
  }

  private static String joinLeaves(JsonNode node) {
    List<String> parts = new ArrayList<>();
    collectLeaves(node, parts);
    return String.join(" ", parts).trim();
  }

  private static void collectLeaves(JsonNode node, List<String> out) {
    if (node == null || node.isNull()) return;
    if (node.isArray()) {
      node.forEach(item -> collectLeaves(item, out));
      return;
    }
    if (node.isObject()) {
      node.fields().forEachRemaining(e -> collectLeaves(e.getValue(), out));
      return;
    }
    String s = node.asText("");
    if (!s.isBlank()) out.add(s.trim());
  }
}
