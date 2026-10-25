package com.powercms.cms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 检索 / 向量化文本装配（FR-AI-RAG-001，验收 R6）。
 *
 * <p>把内容字段值组装为可索引的干净文本，对齐规划口径「标题 + 摘要 + 正文 + 标签」在当前
 * 内容模型（无系统 summary/tags 列，正文为 fieldValues JSON，标签为 TAG 类型字段）上的落地：
 * <ul>
 *   <li><b>标签</b>：字段类型 {@code TAG} 的值数组（按字段定义 sort_order 遍历）；</li>
 *   <li><b>摘要</b>：code 命中约定集（summary/abstract/excerpt/teaser/description）的文本字段值；</li>
 *   <li><b>正文</b>：其余文本型字段（STRING/LONG_TEXT/RICH_TEXT/BLOCK_EDITOR/ENUM/MULTI_ENUM）值，
 *       按字段定义顺序拼接；HTML/Markdown 记号剥离、空白压缩；</li>
 *   <li><b>字段级过滤按目标索引区分</b>（{@link Target}，DEF-SEARCH-02）：
 *       <b>管理索引</b>按 {@code adminIndex}（§5.3「字段值进入管理端全文索引」）、
 *       <b>访客索引</b>按 {@code publicIndex}（§5.3「字段值进入访客端全文索引，敏感字段禁止开启」）
 *       —— 此前二者共用 {@code publicIndex}，导致字段属性 {@code adminIndex} 对检索链路完全无效、
 *       管理索引正文恒为空（正文关键词搜不到、{@code snapshot} 恒空）。</li>
 * </ul>
 *
 * <p>无字段定义或 JSON 解析失败时降级返回原始 fieldValues（向后兼容既有行为；真实内容类型恒有字段定义）。
 *
 * <p>归属 content 模块：field_definition（model）查询复用 ContentTypeService/TagService 同款依赖；
 * search 模块经本服务取文本，不直接依赖 model（Spring Modulith 边界：search→content→model）。
 */
@Component
public class ContentSearchTextAssembler {

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * 目标索引（DEF-SEARCH-02）：字段级搜索索引属性是**按目标索引**区分的（§5.3 / AC-CM-009
     * 「模型发布后 OpenSearch 索引映射与配置一致」），不能用单一属性同时服务两侧。
     */
    public enum Target {
        /** 管理索引（{@code powercms-admin-*}）：按 {@code adminIndex} 纳入字段。 */
        ADMIN,
        /**
         * 访客索引（{@code powercms-public-*}）：按 {@code publicIndex} 纳入字段。
         *
         * <p>未开访客索引（{@code publicIndex=false}）的字段一律不进——模型校验禁止
         * {@code sensitive} 与 {@code publicIndex} 并存，故该开关同时承载敏感字段隔离语义
         * （FR-CM-PROP-008 / FR-AI-RAG-006）。
         */
        PUBLIC;

        /** 该字段是否进入本目标索引。 */
        boolean includes(FieldDefinitionEntity def) {
            return this == ADMIN ? def.isAdminIndex() : def.isPublicIndex();
        }
    }

    /** code 命中即视为「摘要」域的约定（内容类型建模可选用；缺失不影响拼接）。 */
    static final Set<String> SUMMARY_CODES = Set.of("summary", "abstract", "excerpt", "teaser", "description");

    /** 文本型字段：值进入正文/摘要拼接。引用/媒体/JSON/结构控件等不进正文。 */
    static final Set<String> TEXT_TYPES = Set.of(
        "STRING", "LONG_TEXT", "RICH_TEXT", "BLOCK_EDITOR", "ENUM", "MULTI_ENUM");

    /**
     * 散文字段类型：真正的「正文」语义（schema.org {@code articleBody} 等）。与 {@link #TEXT_TYPES} 相比
     * 排除短文本/枚举/结构字段，避免把标题、联系方式、里程碑等结构化字段的原文当作正文外泄。
     */
    static final Set<String> PROSE_TYPES = Set.of("RICH_TEXT", "LONG_TEXT", "BLOCK_EDITOR");

    private static final Pattern BLOCK_CLOSE = Pattern.compile("(?i)</p>|<br\\s*/?>|</div>|</li>|</h[1-6]>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MD_NOISE = Pattern.compile("(?m)^[\\s]*[-*+#>]+\\s*");
    private static final Pattern BACKTICK = Pattern.compile("`+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 组装结果：标题 + 摘要 + 正文 + 标签（排序见 {@link #extract} 遍历）。 */
    public record IndexText(String title, String summary, String body, List<String> tags) {

        /** 摘要 + 正文 + 标签合成一条索引文本（写入 {@code body} 供 BM25/highlight/snapshot，亦作向量化输入）。 */
        public String content() {
            StringBuilder sb = new StringBuilder();
            appendText(sb, summary);
            appendText(sb, body);
            if (tags != null && !tags.isEmpty()) appendText(sb, String.join(" ", tags));
            return sb.toString().trim();
        }

        /** 向量化输入：标题 + 内容（FR-AI-RAG-001 拼接口径「标题+摘要+正文+标签」）。 */
        public String embeddingText() {
            String content = content();
            if (title == null || title.isBlank()) return content;
            return content.isBlank() ? title.trim() : title.trim() + "\n" + content;
        }
    }

    private final FieldDefinitionRepository fieldRepo;

    public ContentSearchTextAssembler(FieldDefinitionRepository fieldRepo) {
        this.fieldRepo = fieldRepo;
    }

    /** 按内容类型字段定义抽取当前版本 fieldValues 的可索引文本（**访客索引**口径，向后兼容）。
     *
     * @deprecated 目标索引不确定时使用；索引构建请显式传 {@link Target}（DEF-SEARCH-02）。
     */
    @Deprecated
    public IndexText assemble(UUID contentTypeId, String title, String fieldValuesJson) {
        return assemble(contentTypeId, title, fieldValuesJson, Target.PUBLIC);
    }

    /**
     * 按内容类型字段定义抽取当前版本 fieldValues 的可索引文本（FR-AI-RAG-001）。
     *
     * @param target 目标索引：{@link Target#ADMIN} 按 {@code adminIndex} 过滤字段、
     *               {@link Target#PUBLIC} 按 {@code publicIndex} 过滤（DEF-SEARCH-02）。
     */
    public IndexText assemble(UUID contentTypeId, String title, String fieldValuesJson, Target target) {
        List<FieldDefinitionEntity> defs = fieldRepo == null ? List.of()
            : fieldRepo.findByContentTypeIdOrderBySortOrder(contentTypeId);
        return extract(title, fieldValuesJson, defs, target);
    }

    /**
     * 按内容类型字段定义抽取「正文」文本（schema.org {@code articleBody} 等正文语义）。
     *
     * <p>仅纳入 {@link #PROSE_TYPES} 且 {@code publicIndex=true} 的字段；HTML/Markdown 记号剥离、
     * 空白压缩，按字段定义顺序拼接。与 {@link #assemble} 的差异：<b>不会</b>把结构化字段（STRING/ENUM/
     * KEY_VALUE/REPEAT_GROUP 等）原文带入正文。
     *
     * <p>**口径固定为访客语义（{@link Target#PUBLIC}）**：本方法服务于站外可见的 SEO/结构化数据产出
     * （{@code MetaService} / {@code SchemaOrgService}），与访客索引同口径，不随管理索引字段属性放宽。
     */
    public String proseText(UUID contentTypeId, String fieldValuesJson) {
        List<FieldDefinitionEntity> defs = fieldRepo == null ? List.of()
            : fieldRepo.findByContentTypeIdOrderBySortOrder(contentTypeId);
        return extractProse(fieldValuesJson, defs);
    }

    /**
     * 正文抽取纯函数入口（同包单测直接调用）。
     *
     * <p>口径：fieldValues 为纯文本/非法 JSON/非对象时按历史「纯文本即正文」口径返回清洗后原文；为 JSON
     * 对象时<b>只</b>取 {@link #PROSE_TYPES} 且 {@code publicIndex=true} 的字段值——无字段定义或无命中
     * 一律返回空串（fail-safe：绝不回退为原始字段 JSON，避免把全量字段原文泄露进正文）。
     */
    static String extractProse(String fieldValuesJson, List<FieldDefinitionEntity> defs) {
        String raw = fieldValuesJson == null ? "" : fieldValuesJson.trim();
        if (raw.isBlank()) return "";
        JsonNode root;
        try {
            root = OM.readTree(raw);
        } catch (Exception ex) {
            return clean(raw);
        }
        if (root == null || !root.isObject()) return clean(raw);
        if (defs == null || defs.isEmpty()) return "";
        StringBuilder body = new StringBuilder();
        for (FieldDefinitionEntity def : defs) {
            if (!def.isPublicIndex()) continue;
            if (!PROSE_TYPES.contains(def.getFieldType())) continue;
            JsonNode value = root.get(def.getCode());
            if (value == null || value.isNull()) continue;
            String text = textOf(value);
            if (text.isBlank()) continue;
            if (body.length() > 0) body.append("\n");
            body.append(text);
        }
        return body.toString();
    }

    /**
     * 纯函数入口（同包单测直接调用）：由字段定义驱动，从 fieldValues JSON 抽出可索引干净文本
     * （**访客索引**口径，向后兼容）。
     *
     * @deprecated 目标索引不确定时使用；索引构建请显式传 {@link Target}（DEF-SEARCH-02）。
     */
    @Deprecated
    static IndexText extract(String title, String fieldValuesJson, List<FieldDefinitionEntity> defs) {
        return extract(title, fieldValuesJson, defs, Target.PUBLIC);
    }

    /**
     * 纯函数入口（同包单测直接调用）：由字段定义驱动，从 fieldValues JSON 抽出可索引干净文本。
     *
     * @param target 目标索引：{@link Target#ADMIN} 按 {@code adminIndex}、{@link Target#PUBLIC} 按
     *               {@code publicIndex} 决定字段是否纳入（DEF-SEARCH-02）。
     */
    static IndexText extract(String title, String fieldValuesJson, List<FieldDefinitionEntity> defs,
                             Target target) {
        Target effective = target == null ? Target.PUBLIC : target;
        String raw = fieldValuesJson == null ? "" : fieldValuesJson.trim();
        if (defs == null || defs.isEmpty() || raw.isBlank()) {
            return new IndexText(title, "", clean(raw), List.of());
        }
        JsonNode root;
        try {
            root = OM.readTree(raw);
        } catch (Exception ex) {
            return new IndexText(title, "", clean(raw), List.of());
        }
        if (root == null || !root.isObject()) {
            return new IndexText(title, "", clean(raw), List.of());
        }
        StringBuilder summary = new StringBuilder();
        StringBuilder body = new StringBuilder();
        Set<String> tags = new LinkedHashSet<>();
        for (FieldDefinitionEntity def : defs) {
            // §5.3 字段级搜索索引属性：按目标索引过滤（管理索引 adminIndex / 访客索引 publicIndex，
            // 后者同时承载敏感字段隔离语义），未开启的字段内容绝不进入检索正文与向量化文本
            if (!effective.includes(def)) continue;
            JsonNode value = root.get(def.getCode());
            if (value == null || value.isNull()) continue;
            if ("TAG".equals(def.getFieldType())) {
                collectTags(value, tags);
                continue;
            }
            if (!TEXT_TYPES.contains(def.getFieldType())) continue;
            String text = textOf(value);
            if (text.isBlank()) continue;
            if (SUMMARY_CODES.contains(def.getCode())) {
                appendText(summary, text);
            } else {
                appendText(body, text);
            }
        }
        return new IndexText(title, summary.toString(), body.toString(), List.copyOf(tags));
    }

    /** 标签：数组取文本元素，单值字符串兼容；去除空项与重复。 */
    private static void collectTags(JsonNode value, Set<String> tags) {
        if (value.isArray()) {
            value.forEach(v -> addTag(v, tags));
        } else {
            addTag(value, tags);
        }
    }

    private static void addTag(JsonNode node, Set<String> tags) {
        String tag = node.isTextual() ? node.asText() : node.asText("");
        if (tag != null && !tag.isBlank()) {
            tags.add(tag.trim());
        }
    }

    /** 叶子文本抽取：字符串/数字/布尔直取；对象取全部文本叶子（如 i18n 值 / 引用 title）；数组递归。 */
    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return clean(node.asText());
        if (node.isNumber() || node.isBoolean()) return clean(node.asText());
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(v -> {
                String t = textOf(v);
                if (!t.isBlank()) parts.add(t);
            });
            return clean(String.join(" ", parts));
        }
        if (node.isObject()) {
            List<String> parts = new ArrayList<>();
            node.fields().forEachRemaining(e -> {
                String t = textOf(e.getValue());
                if (!t.isBlank()) parts.add(t);
            });
            return clean(String.join(" ", parts));
        }
        return "";
    }

    private static void appendText(StringBuilder sb, String part) {
        String p = part == null ? "" : clean(part);
        if (p.isBlank()) return;
        if (sb.length() > 0) sb.append("\n");
        sb.append(p);
    }

    /** 清洗：块级标签换行 → 去 HTML 标签 → 去 Markdown 列表记号/反引号 → 压缩空白。 */
    private static String clean(String text) {
        if (text == null || text.isBlank()) return "";
        String s = BLOCK_CLOSE.matcher(text).replaceAll("\n");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = MD_NOISE.matcher(s).replaceAll("");
        s = BACKTICK.matcher(s).replaceAll("");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /**
     * 清洗入口（供 {@link ContentIndexSchema} 的字段级高亮取值复用**同一口径**）。
     *
     * <p>刻意只暴露清洗而不再暴露装配：字段级索引键的取值必须与 {@code body} 的清洗一致，
     * 否则同一条内容在「摘要快照」与「字段级高亮片段」里会出现两种文本形态。
     */
    static String cleanText(String text) {
        return clean(text);
    }
}
