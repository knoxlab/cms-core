package com.powercms.cms.sitepackage;

import com.powercms.cms.model.ContentModelValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型 {@code layout.tabs} 声明（数字资源设计画布两页签）：导入生成 TAB 布局
 * （TAB → 每页一个 FORM_SECTION，与管理端设计器存储形态一致）；未列出的顶层字段
 * 宽容地追加到最后一个页签；引用不存在的字段或跨页签重复则阻断导入。
 * 系统字段（title_s 标题）可直接在页签里引用，落库后由 seedSystemFields 标记为系统字段。
 */
class SitePackageModelsLayoutTest {

    private static final String MODEL = """
        code: digital_asset_probe
        name: 数字资源
        fields:
          - code: media_items
            name: 关联媒体
            fieldType: REPEAT_GROUP
            fields:
              - code: media_title_s
                name: 媒体标题
                fieldType: STRING
          - code: asset_code_s
            name: 资源编码
            fieldType: STRING
          - code: summary_t
            name: 摘要
            fieldType: LONG_TEXT
        layout:
          tabs:
            - title: 关联媒体
              fields: [media_items]
            - title: 资源编目
              fields: [title_s, asset_code_s]
        """;

    private record Run(java.util.List<SitePackageModels.Def> defs,
                       java.util.List<SitePackageViolations.Violation> violations) {}

    private Run run(String modelYaml) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("models/digital_asset_probe.yaml", modelYaml.getBytes(StandardCharsets.UTF_8));
        var scan = new SitePackageArchive.Scan(entries, "", 0, 0, 0);
        var violations = new ArrayList<SitePackageViolations.Violation>();
        var defs = SitePackageModels.validate(scan, new ContentModelValidator(), violations);
        return new Run(defs, violations);
    }

    @Test
    void tabsDeclarationBuildsTabLayoutWithTwoPages() {
        Run run = run(MODEL);
        assertTrue(run.violations().isEmpty(), () -> run.violations().toString());
        assertEquals(1, run.defs().size());

        var layout = run.defs().get(0).request().layout();
        assertEquals(1, layout.size());
        var tab = layout.get(0);
        assertEquals("TAB", tab.elementType());

        var pages = tab.children();
        assertEquals(2, pages.size());
        assertEquals("FORM_SECTION", pages.get(0).elementType());
        assertEquals("关联媒体", pages.get(0).config().path("title").asText());
        assertEquals("media_items", pages.get(0).children().get(0).config().path("variableName").asText());

        assertEquals("资源编目", pages.get(1).config().path("title").asText());
        var metaChildren = pages.get(1).children();
        // 系统字段标题可直接在页签里引用，置于资源编目首位
        assertEquals("title_s", metaChildren.get(0).field().code());
        assertEquals("STRING", metaChildren.get(0).field().fieldType());
        assertEquals("标题", metaChildren.get(0).field().name());
        assertEquals("asset_code_s", metaChildren.get(1).field().code());
        // 未列出的顶层字段（summary_t）宽容地追加到最后一个页签
        assertEquals("summary_t", metaChildren.get(2).field().code());
    }

    @Test
    void tabsReferencingUnknownFieldBlocksImport() {
        Run run = run(MODEL.replace("fields: [title_s, asset_code_s]", "fields: [title_s, asset_code_s, missing_field_s]"));
        assertFalse(run.violations().isEmpty());
        assertTrue(run.violations().stream().anyMatch(v -> v.detail().contains("missing_field_s")),
            () -> run.violations().toString());
    }

    @Test
    void duplicateFieldAcrossTabsBlocksImport() {
        Run run = run(MODEL.replace("fields: [title_s, asset_code_s]", "fields: [title_s, asset_code_s, media_items]"));
        assertFalse(run.violations().isEmpty());
        assertTrue(run.violations().stream().anyMatch(v -> v.detail().contains("重复出现在多个页签")),
            () -> run.violations().toString());
    }
}
