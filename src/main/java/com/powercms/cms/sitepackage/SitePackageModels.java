package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.powercms.cms.model.ContentModelValidator;
import com.powercms.cms.model.dto.ContentTypeResponse;
import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.powercms.cms.sitepackage.SitePackageViolations.Violation;

/**
 * 站点包内容模型解析与校验（FR-PACKAGE-002「模型可通过 Schema 验证」、FR-PACKAGE-006「模型校验」）。
 *
 * <p>此前的实现把 YAML 解析失败当作「跳过该文件」静默忽略，导致包里声明的模型缺失却仍建站成功
 * （DEF-PKG-04）。现在任何语法/形状/字段错误都在建站之前汇总阻断，并对齐
 * {@link ContentModelValidator} 的权威校验，保证预检与导入结论一致。</p>
 */
final class SitePackageModels {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 校验通过的模型定义（可直接落库）。 */
    record Def(String file, String code, String name, CreateContentTypeRequest request) {}

    private SitePackageModels() {}

    @SuppressWarnings("unchecked")
    static List<Def> validate(SitePackageArchive.Scan scan, ContentModelValidator validator,
                              List<Violation> violations) {
        List<Def> defs = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        for (String path : scan.pathsMatching(p -> p.startsWith("models/") && p.endsWith(".yaml"))) {
            Map<String, Object> model;
            try {
                model = YAML.readValue(scan.entries().get(path), Map.class);
            } catch (IOException e) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    "模型 YAML 解析失败: " + e.getMessage()));
                continue;
            }
            if (model == null) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path, "模型内容为空"));
                continue;
            }
            String code = SitePackageManifest.text(model.get("code"));
            String name = SitePackageManifest.text(model.get("name"));
            if (code == null) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path, "缺少模型编码 code"));
            }
            if (name == null) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path, "缺少模型名称 name"));
            }
            if (code == null || name == null) continue;
            if (!codes.add(code)) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    "包内模型编码重复: " + code));
                continue;
            }

            List<Violation> shapeViolations = new ArrayList<>();
            CreateContentTypeRequest request = toCreateRequest(model, code, name, path, shapeViolations);
            if (!shapeViolations.isEmpty()) {
                violations.addAll(shapeViolations);
                continue;
            }
            try {
                validator.validate(request);
            } catch (BusinessException ex) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    "模型 " + code + " 未通过校验: " + ex.getMessage()));
                continue;
            } catch (RuntimeException ex) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    "模型 " + code + " 校验异常: " + ex.getMessage()));
                continue;
            }
            defs.add(new Def(path, code, name, request));
        }
        return defs;
    }

    /** 一条字段草稿：原始 YAML 条目 + 解析出的父重复组编码（嵌套 fields 或显式 parent）。 */
    private static final class FieldDraft {
        final Map<String, Object> map;
        final String parent;
        final CreateContentTypeRequest.FieldDefDto dto;

        FieldDraft(Map<String, Object> map, String parent, CreateContentTypeRequest.FieldDefDto dto) {
            this.map = map;
            this.parent = parent;
            this.dto = dto;
        }
    }

    /**
     * 递归收集字段条目（FR-PACKAGE-002）。重复组可用嵌套 {@code fields:} 声明子字段，
     * 平铺条目也可用 {@code parent: <重复组 code>} 显式挂父 —— 两种写法在此归一为
     * 「父重复组编码」，随后 {@link #buildLayout} 还原成与管理端设计器一致的布局树。
     *
     * <p>{@code enclosingRepeats} 为当前列表外层的重复组层数：只有重复组递归一层子字段，
     * 深度在解析侧即封顶（≤3，与 {@code ContentModelValidator#walk} 同口径），避免恶意包把
     * 递归撑爆。</p>
     */
    @SuppressWarnings("unchecked")
    private static void collectFields(List<?> entries, String parentCode, int enclosingRepeats,
                                      String listName, String modelCode, String path,
                                      List<CreateContentTypeRequest.FieldDefDto> fields,
                                      List<FieldDraft> drafts, List<Violation> violations) {
        int index = 0;
        for (Object item : entries) {
            String at = listName + "[" + index + "]";
            if (!(item instanceof Map<?, ?>)) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    at + " 必须是对象（模型 " + modelCode + "）"));
                index++;
                continue;
            }
            Map<String, Object> field = (Map<String, Object>) item;
            String fieldCode = SitePackageManifest.text(field.get("code"));
            String fieldName = SitePackageManifest.text(field.get("name"));
            String fieldType = SitePackageManifest.text(field.get("fieldType"));
            if (fieldCode == null || fieldName == null || fieldType == null) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    at + " 缺少 code/name/fieldType（模型 " + modelCode + "）"));
                index++;
                continue;
            }
            String declaredParent = SitePackageManifest.text(field.get("parent"));
            if (declaredParent != null && parentCode != null && !declaredParent.equals(parentCode)) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    at + " 的 parent 与嵌套层级不一致（模型 " + modelCode + "）"));
                index++;
                continue;
            }
            String effectiveParent = declaredParent != null ? declaredParent : parentCode;
            String normalizedCode = normalizeLegacyCode(fieldCode);
            String normalizedType = normalizeLegacyFieldType(fieldType);
            int sortOrder = fields.size();
            fields.add(new CreateContentTypeRequest.FieldDefDto(
                null,
                normalizedCode, fieldName, normalizedType,
                Boolean.TRUE.equals(field.get("required")), sortOrder,
                SitePackageManifest.text(field.get("groupName")),
                SitePackageManifest.text(field.get("helpText")),
                SitePackageManifest.text(field.get("defaultValue")),
                Boolean.TRUE.equals(field.get("readOnly")),
                toJson(field.get("validation")),
                toNode(field.get("fieldConfig")),
                SitePackageManifest.text(field.get("conditionExpr")),
                Boolean.TRUE.equals(field.get("i18n")),
                flag(field, "adminIndex"),
                flag(field, "publicIndex"),
                false,
                flag(field, "sortable"),
                flag(field, "filterable"),
                flag(field, "highlight"),
                Boolean.TRUE.equals(field.get("uniqueField"))));
            drafts.add(new FieldDraft(field, effectiveParent,
                fields.get(fields.size() - 1)));
            boolean isRepeat = "REPEAT_GROUP".equals(normalizedType);
            Object nested = field.get("fields");
            if (nested != null) {
                if (!isRepeat) {
                    violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                        at + " 仅重复组（fieldType: REPEAT_GROUP）可声明子字段 fields（模型 " + modelCode + "）"));
                } else if (!(nested instanceof List<?> childList)) {
                    violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                        at + ".fields 必须是数组（模型 " + modelCode + "）"));
                } else if (enclosingRepeats + 1 >= 3) {
                    // 本层重复组已是第 3 层（元素深度 = enclosing + 1 + 1 > 3），与 walk() 同口径封顶
                    violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                        at + " 重复组嵌套深度不能超过 3（模型 " + modelCode + "）"));
                } else {
                    collectFields(childList, normalizedCode, enclosingRepeats + 1,
                        at + ".fields", modelCode, path, fields, drafts, violations);
                }
            }
            index++;
        }
    }

    /**
     * 把收集到的字段草稿还原为布局树：顶层元素包一个 FORM_SECTION（与管理端/字段驱动
     * 保存的存储形态一致），重复组元素带嵌套子字段元素、配置含 title/variableName/minItems，
     * 供 {@code ContentModelValidator#walk} 校验、{@code ContentTypeService#saveNodes} 落库、
     * 管理端设计器与内容编辑表单按布局驱动渲染。
     *
     * <p>排序与管理端同口径：同层按（序号+1）*10 递增，顺序即 YAML 书写顺序。</p>
     */
    private static List<CreateContentTypeRequest.LayoutElementDto> buildLayout(
            List<FieldDraft> drafts, String path, List<Violation> violations) {
        return buildLayout(drafts, null, Map.of(), path, violations);
    }

    /**
     * 页签布局声明：{@code layout.tabs: [{title, fields: [...]}]}。声明后顶层字段按页签分组
     * （TAB → 每页一个 FORM_SECTION，与管理端设计器存储形态一致）；未列出的顶层字段
     * 宽容地追加到最后一个页签（与「基本属性」兜底同思路，不让声明的完备性阻断导入）。
     *
     * <p>fields 亦可引用系统字段 code（如通用系统字段 {@code title_s}）：导入时合成 FIELD
     * 元素，落库后由 {@code seedSystemFields} 按 code 标记为系统字段，与设计器拖放
     * 系统字段到页签内的存储形态一致（标题可作为页签首位字段）。</p>
     */
    private record TabSpec(String title, List<String> fields) {}

    /** 解析模型 YAML 顶层 {@code layout.tabs} 段；形状不合法时记录阻断性违例并返回 null。 */
    @SuppressWarnings("unchecked")
    private static List<TabSpec> parseTabs(Object layoutNode, String path, List<Violation> violations) {
        if (layoutNode == null) return null;
        if (!(layoutNode instanceof Map<?, ?> layout)) {
            violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                "layout 必须是对象"));
            return null;
        }
        Object rawTabs = ((Map<String, Object>) layout).get("tabs");
        if (rawTabs == null) return null;
        if (!(rawTabs instanceof List<?> tabList) || tabList.isEmpty()) {
            violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                "layout.tabs 必须是非空数组"));
            return null;
        }
        var tabs = new ArrayList<TabSpec>();
        int index = 0;
        for (Object item : tabList) {
            String at = "layout.tabs[" + index + "]";
            if (!(item instanceof Map<?, ?> tab)) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path, at + " 必须是对象"));
                index++;
                continue;
            }
            String title = SitePackageManifest.text(((Map<String, Object>) tab).get("title"));
            Object rawFields = ((Map<String, Object>) tab).get("fields");
            if (title == null || !(rawFields instanceof List<?> fieldList)) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    at + " 缺少 title 或 fields（必须是数组）"));
                index++;
                continue;
            }
            var codes = new ArrayList<String>();
            for (Object f : fieldList) {
                String c = SitePackageManifest.text(f);
                if (c == null || c.isBlank()) continue;
                codes.add(c.trim());
            }
            tabs.add(new TabSpec(title, codes));
            index++;
        }
        return tabs.isEmpty() ? null : tabs;
    }

    private static List<CreateContentTypeRequest.LayoutElementDto> buildLayout(
            List<FieldDraft> drafts, List<TabSpec> tabs,
            Map<String, ContentModelValidator.SystemField> systemFields,
            String path, List<Violation> violations) {
        if (drafts.isEmpty()) return null;
        // code → 草稿序（重复组按 code 挂接子字段；显式 parent 与嵌套 fields 都指向 code）
        var orderOf = new java.util.HashMap<String, Integer>();
        var repeatCode = new java.util.HashMap<Integer, Boolean>();  // 草稿序 → 是否重复组
        for (int i = 0; i < drafts.size(); i++) {
            FieldDraft d = drafts.get(i);
            String code = SitePackageManifest.text(d.map.get("code"));
            boolean isRepeat = d.dto != null && "REPEAT_GROUP".equals(d.dto.fieldType());
            if (code != null) orderOf.putIfAbsent(normalizeLegacyCode(code), i);
            repeatCode.put(i, isRepeat);
        }
        // parent → 子草稿序（保持收集顺序，即 YAML 书写顺序）
        var childrenOf = new java.util.HashMap<Integer, java.util.List<Integer>>();
        var tops = new java.util.ArrayList<Integer>();
        for (int i = 0; i < drafts.size(); i++) {
            String parent = drafts.get(i).parent;
            if (parent == null) {
                tops.add(i);
                continue;
            }
            Integer p = orderOf.get(normalizeLegacyCode(parent));
            if (p == null || !Boolean.TRUE.equals(repeatCode.get(p))) {
                violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                    "字段 " + normalizeLegacyCode(SitePackageManifest.text(drafts.get(i).map.get("code")))
                        + " 的 parent 不是本模型内的重复组: " + parent));
                continue;
            }
            childrenOf.computeIfAbsent(p, k -> new ArrayList<>()).add(i);
        }
        var built = new java.util.HashMap<Integer, CreateContentTypeRequest.LayoutElementDto>();
        var building = new java.util.HashSet<Integer>();
        java.util.function.BiFunction<Integer, Integer, CreateContentTypeRequest.LayoutElementDto> build =
            new java.util.function.BiFunction<>() {
                @Override
                public CreateContentTypeRequest.LayoutElementDto apply(Integer i, Integer sortOrder) {
                    if (built.containsKey(i)) return reorder(built.get(i), sortOrder);
                    if (!building.add(i)) {
                        violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                            "字段 " + normalizeLegacyCode(SitePackageManifest.text(drafts.get(i).map.get("code")))
                                + " 的 parent 存在循环引用"));
                        return null;
                    }
                    FieldDraft d = drafts.get(i);
                    var kids = new java.util.ArrayList<CreateContentTypeRequest.LayoutElementDto>();
                    List<Integer> childIdx = childrenOf.getOrDefault(i, List.of());
                    for (int k = 0; k < childIdx.size(); k++) {
                        CreateContentTypeRequest.LayoutElementDto child = apply(childIdx.get(k), (k + 1) * 10);
                        if (child != null) kids.add(child);
                    }
                    CreateContentTypeRequest.LayoutElementDto element;
                    if (Boolean.TRUE.equals(repeatCode.get(i))) {
                        String code = normalizeLegacyCode(SitePackageManifest.text(d.map.get("code")));
                        String name = SitePackageManifest.text(d.map.get("name"));
                        element = new CreateContentTypeRequest.LayoutElementDto(
                            null, "REPEAT_GROUP", sortOrder, null, repeatConfig(d, code, name), kids);
                    } else {
                        element = new CreateContentTypeRequest.LayoutElementDto(
                            null, "FIELD", sortOrder, d.dto, null, kids);
                    }
                    building.remove(i);
                    built.put(i, element);
                    return element;
                }
            };
        var topLevel = new java.util.ArrayList<CreateContentTypeRequest.LayoutElementDto>();
        var elementOfCode = new LinkedHashMap<String, CreateContentTypeRequest.LayoutElementDto>();
        for (int i = 0; i < tops.size(); i++) {
            CreateContentTypeRequest.LayoutElementDto element = build.apply(tops.get(i), (i + 1) * 10);
            if (element != null) {
                topLevel.add(element);
                String code = normalizeLegacyCode(SitePackageManifest.text(drafts.get(tops.get(i)).map.get("code")));
                if (code != null) elementOfCode.putIfAbsent(code, element);
            }
        }
        if (topLevel.isEmpty()) return null;
        if (tabs != null && !tabs.isEmpty()) {
            List<CreateContentTypeRequest.LayoutElementDto> tabbed = buildTabLayout(
                tabs, elementOfCode, systemFields, path, violations);
            if (tabbed != null) return tabbed;
            // 声明不合法（违例已记录）时回退默认节，validate() 阶段会整体阻断导入
        }
        JsonNode sectionConfig = new com.fasterxml.jackson.databind.node.ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance)
            .put("title", "基本属性")
            .put("variableName", "basic")
            .put("collapsed", false);
        return List.of(new CreateContentTypeRequest.LayoutElementDto(
            null, "FORM_SECTION", 10, null, sectionConfig, topLevel));
    }

    /** 页签声明 → TAB 布局元素（TAB → 每页一个 FORM_SECTION，与管理端设计器存储形态一致）。 */
    private static List<CreateContentTypeRequest.LayoutElementDto> buildTabLayout(
            List<TabSpec> tabs, LinkedHashMap<String, CreateContentTypeRequest.LayoutElementDto> elementOfCode,
            Map<String, ContentModelValidator.SystemField> systemFields,
            String path, List<Violation> violations) {
        var grouped = new LinkedHashMap<String, java.util.ArrayList<CreateContentTypeRequest.LayoutElementDto>>();
        var claimed = new HashSet<String>();
        for (TabSpec tab : tabs) {
            var group = new java.util.ArrayList<CreateContentTypeRequest.LayoutElementDto>();
            for (String raw : tab.fields()) {
                String code = normalizeLegacyCode(raw);
                CreateContentTypeRequest.LayoutElementDto el = code == null ? null : elementOfCode.get(code);
                // 页签可引用系统字段（如通用系统字段 title_s）：合成 FIELD 元素，导入落库后由
                // seedSystemFields 按 code 权威标记为系统字段（与设计器拖放系统字段同一存储形态）。
                if (el == null && code != null && systemFields.containsKey(code)) {
                    ContentModelValidator.SystemField sf = systemFields.get(code);
                    el = new CreateContentTypeRequest.LayoutElementDto(null, "FIELD", 0,
                        new CreateContentTypeRequest.FieldDefDto(null, sf.code(), sf.name(), sf.fieldType(),
                            false, 0, null, null, null, false, null, null, null, false,
                            true, false, false, false, false, false, false),
                        null, List.of());
                    elementOfCode.putIfAbsent(code, el);
                }
                if (el == null) {
                    violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                        "layout.tabs 页签「" + tab.title() + "」引用了模型顶层不存在的字段: " + raw));
                    continue;
                }
                if (!claimed.add(code)) {
                    violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                        "layout.tabs 字段重复出现在多个页签: " + raw));
                    continue;
                }
                group.add(el);
            }
            grouped.put(tab.title(), group);
        }
        if (!violations.isEmpty()) return null;
        // 未列出的顶层字段宽容地追加到最后一个页签（与「基本属性」兜底同思路，不阻断导入）
        Object[] titles = grouped.keySet().toArray();
        var lastGroup = grouped.get(titles[titles.length - 1]);
        for (var entry : elementOfCode.entrySet()) {
            if (!claimed.contains(entry.getKey())) lastGroup.add(entry.getValue());
        }
        var pages = new java.util.ArrayList<CreateContentTypeRequest.LayoutElementDto>();
        int seq = 0;
        for (var entry : grouped.entrySet()) {
            seq++;
            var kids = new java.util.ArrayList<CreateContentTypeRequest.LayoutElementDto>();
            for (int k = 0; k < entry.getValue().size(); k++) {
                kids.add(reorder(entry.getValue().get(k), (k + 1) * 10));
            }
            JsonNode pageConfig = new com.fasterxml.jackson.databind.node.ObjectNode(
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance)
                .put("title", entry.getKey())
                .put("variableName", "tab_" + seq)
                .put("tabId", "tab_" + seq)
                .put("collapsed", false);
            pages.add(new CreateContentTypeRequest.LayoutElementDto(
                null, "FORM_SECTION", seq * 10, null, pageConfig, kids));
        }
        JsonNode tabConfig = new com.fasterxml.jackson.databind.node.ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance)
            .put("title", "内容分组")
            .put("variableName", "tabs_main");
        return List.of(new CreateContentTypeRequest.LayoutElementDto(
            null, "TAB", 10, null, tabConfig, pages));
    }

    /** 重建元素（record 不可变）以写入排序值。 */
    private static CreateContentTypeRequest.LayoutElementDto reorder(
            CreateContentTypeRequest.LayoutElementDto e, int sortOrder) {
        if (e.sortOrder() == sortOrder) return e;
        return new CreateContentTypeRequest.LayoutElementDto(
            e.elementId(), e.elementType(), sortOrder, e.field(), e.config(), e.children());
    }

    /** 重复组元素配置：fieldConfig 之上补齐管理端必需的 title / variableName。 */
    private static JsonNode repeatConfig(FieldDraft d, String code, String name) {
        JsonNode base = d.dto != null ? d.dto.fieldConfig() : null;
        var cfg = base != null && base.isObject()
            ? ((com.fasterxml.jackson.databind.node.ObjectNode) base).deepCopy()
            : new com.fasterxml.jackson.databind.node.ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        if (!cfg.hasNonNull("title") || cfg.get("title").asText().isBlank()) cfg.put("title", name == null ? code : name);
        if (!cfg.hasNonNull("variableName") || cfg.get("variableName").asText().isBlank()) cfg.put("variableName", code);
        return cfg;
    }

    // ========== 导出：布局树 → 嵌套 fields（重复组往返保真） ==========

    /**
     * 导出字段定义：以布局树为准还原重复组嵌套层级（响应的扁平 fields 会过滤掉
     * REPEAT_GROUP 结构元素，直接导出会让重复组丢失、子字段变成孤儿顶层字段，
     * 重导入即丢层级）；布局之外的字段（系统字段等）平铺追加。
     */
    static List<Map<String, Object>> exportFields(ContentTypeResponse ct) {
        var out = new ArrayList<Map<String, Object>>();
        var emitted = new HashSet<UUID>();
        if (ct.layout() != null) for (ContentTypeResponse.LayoutInfo n : ct.layout()) emitLayout(n, out, emitted);
        if (ct.fields() != null) for (ContentTypeResponse.FieldInfo f : ct.fields()) {
            if (f == null || (f.id() != null && emitted.contains(f.id()))) continue;
            out.add(fieldMap(f));
        }
        return out;
    }

    /**
     * 页签布局导出：布局顶层恰为一个 TAB（TAB → 每页一个 FORM_SECTION）时，还原为模型 YAML 的
     * {@code layout.tabs} 段（页签分组往返保真）；结构复杂（混排/嵌套列等）时不导出，重导入
     * 回退默认「基本属性」节。
     */
    static Map<String, Object> exportLayout(ContentTypeResponse ct) {
        if (ct.layout() == null || ct.layout().size() != 1) return null;
        ContentTypeResponse.LayoutInfo tab = ct.layout().get(0);
        if (!"TAB".equals(tab.elementType()) || tab.children() == null || tab.children().isEmpty()) return null;
        var tabList = new ArrayList<Map<String, Object>>();
        for (ContentTypeResponse.LayoutInfo page : tab.children()) {
            if (!"FORM_SECTION".equals(page.elementType())) return null;
            var codes = new ArrayList<String>();
            collectLeafCodes(page, codes);
            String title = page.config() != null && page.config().hasNonNull("title")
                ? page.config().get("title").asText() : null;
            var m = new LinkedHashMap<String, Object>();
            m.put("title", title == null || title.isBlank() ? "页签 " + (tabList.size() + 1) : title);
            m.put("fields", codes);
            tabList.add(m);
        }
        if (tabList.isEmpty()) return null;
        var layout = new LinkedHashMap<String, Object>();
        layout.put("tabs", tabList);
        return layout;
    }

    /** 递归收集页内叶子字段码（FIELD 取 field.code，重复组整体作为一个条目取 variableName）。 */
    private static void collectLeafCodes(ContentTypeResponse.LayoutInfo n, List<String> out) {
        if (n == null) return;
        if ("FIELD".equals(n.elementType()) && n.field() != null && n.field().code() != null) {
            out.add(n.field().code());
            return;
        }
        if ("REPEAT_GROUP".equals(n.elementType())) {
            JsonNode cfg = n.config();
            if (cfg != null && cfg.hasNonNull("variableName")) out.add(cfg.get("variableName").asText());
            return;
        }
        if (n.children() != null) for (ContentTypeResponse.LayoutInfo c : n.children()) collectLeafCodes(c, out);
    }

    /** 把布局元素写入 target：结构容器就地展开，重复组带嵌套 fields，普通字段写完整条目。 */
    private static void emitLayout(ContentTypeResponse.LayoutInfo n, List<Map<String, Object>> target,
                                   Set<UUID> emitted) {
        if (n == null) return;
        if ("REPEAT_GROUP".equals(n.elementType())) {
            var group = new LinkedHashMap<String, Object>();
            JsonNode cfg = n.config();
            String code = cfg != null && cfg.hasNonNull("variableName") ? cfg.get("variableName").asText() : null;
            String title = cfg != null && cfg.hasNonNull("title") ? cfg.get("title").asText() : null;
            group.put("code", code);
            group.put("name", title == null || title.isBlank() ? code : title);
            group.put("fieldType", "REPEAT_GROUP");
            group.put("sortOrder", n.sortOrder());
            group.put("required", false);
            if (cfg != null && !cfg.isNull()) group.put("fieldConfig", cfg);
            var kids = new ArrayList<Map<String, Object>>();
            if (n.children() != null) for (ContentTypeResponse.LayoutInfo c : n.children()) emitLayout(c, kids, emitted);
            group.put("fields", kids);
            target.add(group);
            return;
        }
        if ("FIELD".equals(n.elementType()) && n.field() != null) {
            target.add(fieldMap(n.field()));
            if (n.field().id() != null) emitted.add(n.field().id());
        }
        // FORM_SECTION / TAB / ROW / COLUMN / LINE_DIVIDER 与 FIELD 的子节点：就地展开
        if (n.children() != null) for (ContentTypeResponse.LayoutInfo c : n.children()) emitLayout(c, target, emitted);
    }

    /** 字段条目 → YAML 映射：只输出导入侧认识的键（不含 id，避免往返时复用实例主键）。 */
    private static Map<String, Object> fieldMap(ContentTypeResponse.FieldInfo f) {
        var m = new LinkedHashMap<String, Object>();
        m.put("code", f.code());
        m.put("name", f.name());
        m.put("fieldType", f.fieldType());
        m.put("required", f.required());
        m.put("sortOrder", f.sortOrder());
        if (f.groupName() != null) m.put("groupName", f.groupName());
        if (f.helpText() != null) m.put("helpText", f.helpText());
        if (f.defaultValue() != null) m.put("defaultValue", f.defaultValue());
        m.put("readOnly", f.readOnly());
        if (f.validation() != null) m.put("validation", f.validation());
        if (f.fieldConfig() != null) m.put("fieldConfig", f.fieldConfig());
        if (f.conditionExpr() != null) m.put("conditionExpr", f.conditionExpr());
        m.put("i18n", f.i18n());
        m.put("adminIndex", f.adminIndex());
        m.put("publicIndex", f.publicIndex());
        m.put("sortable", f.sortable());
        m.put("filterable", f.filterable());
        m.put("highlight", f.highlight());
        m.put("uniqueField", f.uniqueField());
        return m;
    }

    /**
     * 导出模型级属性（FR-PACKAGE-008 往返保真）：只写非默认值，既保持导出紧凑，
     * 又保证「自己导出的包自己导回去」不会丢掉 {@code formController} / 控制器 / 快速创建等配置。
     *
     * <p>内容继承（{@code parentTypeId}）不导出：包内引用的是目标实例的新 id，跨实例无法表达，
     * 导入侧遇到声明会显式阻断而不是静默忽略。</p>
     */
    static Map<String, Object> exportAttributes(ContentTypeResponse m) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (m.templateType() != null && !"PAGE".equals(m.templateType())) attrs.put("templateType", m.templateType());
        if (m.objectType() != null) attrs.put("objectType", m.objectType());
        if (m.displayTemplate() != null && !"templates/content.ftl".equals(m.displayTemplate()))
            attrs.put("displayTemplate", m.displayTemplate());
        if (notBlank(m.icon())) attrs.put("icon", m.icon());
        if (notBlank(m.thumbnail())) attrs.put("thumbnail", m.thumbnail());
        if (notBlank(m.controllerPath())) attrs.put("controllerPath", m.controllerPath());
        if (notBlank(m.formController())) attrs.put("formController", m.formController());
        if (m.mergeStrategy() != null && !"NONE".equals(m.mergeStrategy()))
            attrs.put("mergeStrategy", m.mergeStrategy());
        if (m.quickCreate()) attrs.put("quickCreate", true);
        if (notBlank(m.destinationPattern())) attrs.put("destinationPattern", m.destinationPattern());
        if (notBlank(m.previewImage())) attrs.put("previewImage", m.previewImage());
        if (m.config() != null && !m.config().isNull() && m.config().size() > 0)
            attrs.put("config", new ObjectMapper().convertValue(m.config(), Map.class));
        if (!m.flattenComponents()) attrs.put("flattenComponents", false);
        if (notBlank(m.subtype())) attrs.put("subtype", m.subtype());
        if (notBlank(m.formNotificationEmails())) attrs.put("formNotificationEmails", m.formNotificationEmails());
        if (notBlank(m.mediaKind())) attrs.put("mediaKind", m.mediaKind());
        if (notBlank(m.schemaOrgType())) attrs.put("schemaOrgType", m.schemaOrgType());
        return attrs;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static CreateContentTypeRequest toCreateRequest(Map<String, Object> model, String code, String name,
                                                            String path, List<Violation> violations) {
        Object rawFields = model.get("fields");
        if (rawFields != null && !(rawFields instanceof List)) {
            violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                "fields 必须是数组（模型 " + code + "）"));
        }
        List<CreateContentTypeRequest.FieldDefDto> fields = new ArrayList<>();
        List<FieldDraft> drafts = new ArrayList<>();
        if (rawFields instanceof List<?> list) {
            collectFields(list, null, 0, "fields", code, path, fields, drafts, violations);
        }
        List<TabSpec> tabs = parseTabs(model.get("layout"), path, violations);
        // 页签可引用系统字段（如 title_s）：按模型对象类型给出权威系统字段集
        // （与 ContentTypeService.seedSystemFields 同口径：由 objectType 决定系统字段）。
        String objectType = attrText(model, "objectType", "SHARED", 20, "objectType", code, path, violations);
        String mediaKind = attrText(model, "mediaKind", null, 20, "mediaKind", code, path, violations);
        Map<String, ContentModelValidator.SystemField> systemFields = new LinkedHashMap<>();
        for (ContentModelValidator.SystemField sf : ContentModelValidator.systemFields(objectType, mediaKind)) {
            systemFields.put(sf.code(), sf);
        }
        List<CreateContentTypeRequest.LayoutElementDto> layout =
            violations.isEmpty() ? buildLayout(drafts, tabs, systemFields, path, violations) : null;
        // 内容继承需要先把父类型落库拿到 id 才能绑定：包内声明必须显式阻断，不能被静默丢弃。
        if (model.get("parentTypeId") != null || model.get("parentTypeCode") != null) {
            violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                "站点包暂不支持声明内容继承父类型（parentTypeId/parentTypeCode），"
                    + "请导入后于管理端设置（模型 " + code + "）"));
        }
        // 默认 SHARED（可在内容库录入并发布）：四参构造会把模型落成 COMPONENT，前台交付接口读不到。
        return new CreateContentTypeRequest(
            code,
            name,
            // 描述是自由文本：沿用既有的宽松口径（非字符串也转文本），不因历史包写法而阻断
            SitePackageManifest.text(model.get("description")),
            attrText(model, "templateType", "PAGE", 20, "templateType", code, path, violations),
            attrText(model, "displayTemplate", "templates/content.ftl", 500, "displayTemplate", code, path, violations),
            attrText(model, "icon", null, 100, "icon", code, path, violations),
            attrText(model, "thumbnail", null, 500, "thumbnail", code, path, violations),
            layout,
            fields,
            objectType,
            attrText(model, "controllerPath", null, 500, "controllerPath", code, path, violations),
            attrText(model, "formController", null, 1000, "formController", code, path, violations),
            attrText(model, "mergeStrategy", null, 20, "mergeStrategy", code, path, violations),
            null, // parentTypeId：需要父类型落库后的 id，包内声明已在上方阻断
            attrFlag(model, "quickCreate", false, code, path, violations),
            attrText(model, "destinationPattern", null, 500, "destinationPattern", code, path, violations),
            attrText(model, "previewImage", null, 500, "previewImage", code, path, violations),
            attrConfig(model, "config", code, path, violations),
            attrBoolean(model, "flattenComponents", code, path, violations),
            attrText(model, "subtype", null, 20, "subtype", code, path, violations),
            attrText(model, "formNotificationEmails", null, 500, "formNotificationEmails", code, path, violations),
            mediaKind,
            attrText(model, "schemaOrgType", null, 50, "schemaOrgType", code, path, violations));
    }

    /**
     * 模型级字符串属性：缺省 → default（保持旧包行为不变）；声明了非字符串或超长 → 阻断。
     *
     * <p>长度上限与 {@link CreateContentTypeRequest} 的 {@code @Size} 一致：包层先拦下，避免
     * 走到落库阶段才因 Bean Validation 失败（那时站点已创建，错误信息也不指向 YAML 位置）。</p>
     */
    private static String attrText(Map<String, Object> model, String key, String fallback, int maxLength,
                                   String label, String code, String path, List<Violation> violations) {
        Object raw = model.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof String s)) {
            violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                label + " 必须是字符串（模型 " + code + "）"));
            return fallback;
        }
        if (s.length() > maxLength) {
            violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                label + " 超长（上限 " + maxLength + " 字符，模型 " + code + "）"));
            return fallback;
        }
        return s;
    }

    /** 模型级布尔属性：缺省 → fallback；声明了非布尔 → 阻断。 */
    private static boolean attrFlag(Map<String, Object> model, String key, boolean fallback,
                                    String code, String path, List<Violation> violations) {
        Boolean value = attrBoolean(model, key, code, path, violations);
        return value == null ? fallback : value;
    }

    private static Boolean attrBoolean(Map<String, Object> model, String key,
                                       String code, String path, List<Violation> violations) {
        Object raw = model.get(key);
        if (raw == null) return null;
        if (!(raw instanceof Boolean b)) {
            violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                key + " 必须是布尔值（模型 " + code + "）"));
            return null;
        }
        return b;
    }

    /** 模型级 config（JSON 对象）：形状错误在这里阻断，正则等语义交给 ContentModelValidator。 */
    private static JsonNode attrConfig(Map<String, Object> model, String key,
                                       String code, String path, List<Violation> violations) {
        Object raw = model.get(key);
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?>)) {
            violations.add(Violation.blocking(ErrorCodes.SITE_PACKAGE_MODEL_INVALID, path,
                key + " 必须是对象（模型 " + code + "）"));
            return null;
        }
        return toNode(raw);
    }

    /** 兼容 V11 遗留编码（camelCase → snake_case）与字段类型别名。 */
    static String normalizeLegacyCode(String code) {
        return code.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    static String normalizeLegacyFieldType(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "TEXT" -> "STRING";
            case "DATETIME" -> "DATE_TIME";
            case "MARKDOWN" -> "LONG_TEXT";
            default -> type.toUpperCase(Locale.ROOT);
        };
    }

    /** 未声明的索引标记保持 false，避免旧站点包在导入后被悄悄改成可检索。 */
    private static boolean flag(Map<String, Object> field, String key) {
        return Boolean.TRUE.equals(field.get(key));
    }

    /** ENUM / TAG 等类型的选项与约束必须原样进入 fieldConfig，否则模型校验会因空选项失败。 */
    private static JsonNode toNode(Object value) {
        if (value == null) return null;
        return new ObjectMapper().valueToTree(value);
    }

    private static String toJson(Object value) {
        if (value == null) return null;
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 模型编码是否命中保留前缀（FR-PACKAGE-005：系统模型不得被包内定义覆盖）。 */
    static boolean isReserved(String code, SitePackageProperties props) {
        if (code == null) return false;
        String lower = code.toLowerCase(Locale.ROOT);
        return props.getReservedModelPrefixes().stream()
            .anyMatch(prefix -> lower.startsWith(prefix.toLowerCase(Locale.ROOT)));
    }

    /** 供冲突报告展示的模型摘要。 */
    static Map<String, String> summary(List<Def> defs) {
        Map<String, String> out = new LinkedHashMap<>();
        defs.forEach(d -> out.put(d.code(), d.name()));
        return out;
    }
}
