package com.powercms.cms.content;

/**
 * 内容字段在检索索引中的**字段级**映射描述（FR-MODEL-006 / AC-CM-009）。
 *
 * <p>背景：{@link ContentSearchTextAssembler} 只把字段文本**拍平**进单个 {@code body}
 * （可表达 {@code adminIndex}/{@code publicIndex} 的「值在不在语料里」），但
 * {@code sortable}/{@code filterable}/{@code highlight} 要求字段**可独立寻址**
 * （OpenSearch 的 {@code sort}/{@code term}/{@code highlight.fields} 都必须指名道姓），
 * 因此需要为每个开了这三类属性的字段在 mapping 里生成独立键 —— 本记录即该键的描述。
 *
 * <p><b>键命名</b>：{@code f_<code>}（{@code code} 受 {@code FR-CM-PROP-003} 约束为
 * {@code [a-z][a-zA-Z0-9_]{0,99}}，无需转义），与既有系统键
 * （{@code title/body/status/siteId/categoryId/contentTypeId/sourceDomain/embedding*、
 * _visibleGroups/currentVersion/routePath/updatedAt}）无碰撞。
 *
 * <p><b>text 与 keyword 的双角色</b>：同一字段既要高亮（需 {@code text} 分词以出片段）
 * 又要排序/过滤（需 {@code keyword} 精确值）时，{@code type=text} 并追加
 * {@code fields.kw} 子字段；排序/过滤走 {@link #sortKey()}（{@code f_x.kw}），
 * 高亮走 {@link #key()}（{@code f_x}）。只承担单向角色时不追加子字段，避免无谓索引膨胀。
 *
 * @param code       字段 code（内容类型内唯一）
 * @param key        索引键：{@code f_<code>}
 * @param type       OpenSearch 映射类型：{@code keyword|long|double|boolean|date|text}
 * @param sortable   该字段可作为**搜索结果排序条件**（§5.3）
 * @param filterable 该字段可作为**搜索过滤条件**（§5.3）
 * @param highlight  该字段值在搜索结果中**高亮**（§5.3）
 */
public record FieldIndexSpec(
    String code, String key, String type, boolean sortable, boolean filterable, boolean highlight) {

  /** 字段级索引键前缀（与系统键隔离）。 */
  public static final String KEY_PREFIX = "f_";

  /** 双角色字段的 keyword 子字段名（{@code f_x.kw}）。 */
  public static final String KEYWORD_SUBFIELD = "kw";

  /**
   * keyword 子字段的 {@code ignore_above}（字符数）。
   *
   * <p>超长值不进入 keyword 子字段（排序/过滤取不到该条），以换取索引体积可控 —— 这是
   * 「把任意长度的正文当 keyword 存」的必然代价，取值对齐 OpenSearch 社区常用实践。
   */
  public static final int KEYWORD_IGNORE_ABOVE = 1024;

  public static String keyOf(String code) {
    return KEY_PREFIX + code;
  }

  /** 需要 keyword 子字段：基础类型为 text（因高亮）但还要承担排序/过滤。 */
  public boolean hasKeywordSubfield() {
    return "text".equals(type) && (sortable || filterable);
  }

  /** 排序/过滤实际使用的键（双角色时为 {@code f_x.kw}）。 */
  public String sortKey() {
    return hasKeywordSubfield() ? key + "." + KEYWORD_SUBFIELD : key;
  }

  /** 过滤与排序同键（同为基础类型的精确值语义）。 */
  public String filterKey() {
    return sortKey();
  }

  /** 该字段是否至少承担一个字段级角色；都不承担时不应生成映射键。 */
  public boolean hasAnyRole() {
    return sortable || filterable || highlight;
  }
}
