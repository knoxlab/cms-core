package com.powercms.cms.sitepackage;

import com.powercms.cms.model.ContentModelValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型级属性（{@code formController} / 控制器 / 快速创建等）的导入解析。
 *
 * <p>回归背景：{@code toCreateRequest} 曾把这些参数硬编码为 {@code null} —— 包内声明的
 * 客户端表单控制器被**静默丢弃**，导入后必须到管理端手填一次。现在缺省仍保持旧口径，
 * 声明了就落库，形状/长度不对则在建站前阻断。</p>
 */
class SitePackageModelsAttributesTest {

    private static final String BASE = """
        code: attribute_probe
        name: 属性探针
        fields:
          - code: summary_t
            name: 摘要
            fieldType: LONG_TEXT
        """;

    private record Run(java.util.List<SitePackageModels.Def> defs,
                       java.util.List<SitePackageViolations.Violation> violations) {}

    private Run run(String extra) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("models/attribute_probe.yaml", (BASE + extra).getBytes(StandardCharsets.UTF_8));
        var scan = new SitePackageArchive.Scan(entries, "", 0, 0, 0);
        var violations = new ArrayList<SitePackageViolations.Violation>();
        var defs = SitePackageModels.validate(scan, new ContentModelValidator(), violations);
        return new Run(defs, violations);
    }

    private static String details(Run run) {
        return run.violations().toString();
    }

    @Test
    void formControllerIsReadFromYamlInsteadOfDropped() {
        Run run = run("formController: /scripts/digital-asset-form-controller.js\n");
        assertTrue(run.violations().isEmpty(), () -> details(run));
        assertEquals("/scripts/digital-asset-form-controller.js",
            run.defs().get(0).request().formController());
    }

    @Test
    void absentAttributesKeepLegacyDefaults() {
        Run run = run("");
        assertTrue(run.violations().isEmpty(), () -> details(run));
        var req = run.defs().get(0).request();
        assertNull(req.formController(), "未声明时不得凭空写入脚本路径");
        assertNull(req.controllerPath());
        assertEquals("SHARED", req.objectType(), "包内模型默认 SHARED（可在内容库录入并发布）");
        assertEquals("PAGE", req.templateType());
        assertEquals("templates/content.ftl", req.displayTemplate());
        assertEquals(false, req.quickCreate());
        assertNull(req.mergeStrategy(), "未声明时交给服务端默认 NONE");
        assertNull(req.flattenComponents());
    }

    @Test
    void readsObjectLevelConfigurationWholesale() {
        Run run = run("""
            objectType: SHARED
            displayTemplate: /templates/dam.ftl
            controllerPath: /scripts/dam-lifecycle.groovy
            formController: /scripts/dam-form.js
            mergeStrategy: NONE
            quickCreate: true
            destinationPattern: /assets/{yyyy}/{objectId}
            previewImage: /static-assets/previews/dam.png
            flattenComponents: false
            schemaOrgType: CreativeWork
            config:
              pathIncludes: ["^/assets/.*"]
            """);
        assertTrue(run.violations().isEmpty(), () -> details(run));
        var req = run.defs().get(0).request();
        assertEquals("/scripts/dam-lifecycle.groovy", req.controllerPath());
        assertEquals("/scripts/dam-form.js", req.formController());
        assertEquals("NONE", req.mergeStrategy());
        assertEquals(true, req.quickCreate());
        assertEquals("/assets/{yyyy}/{objectId}", req.destinationPattern());
        assertEquals("/static-assets/previews/dam.png", req.previewImage());
        assertEquals(false, req.flattenComponents());
        assertEquals("CreativeWork", req.schemaOrgType());
        assertEquals("/templates/dam.ftl", req.displayTemplate());
        assertEquals("^/assets/.*", req.config().path("pathIncludes").get(0).asText());
    }

    @Test
    void mediaObjectTypeFlowsIntoSystemFieldsAndRequiresMediaKind() {
        Run missingKind = run("objectType: MEDIA\n");
        assertFalse(missingKind.violations().isEmpty(), "MEDIA 必须声明 mediaKind");
        assertTrue(details(missingKind).contains("mediaKind"), () -> details(missingKind));

        Run withKind = run("objectType: MEDIA\nmediaKind: image\n");
        assertTrue(withKind.violations().isEmpty(), () -> details(withKind));
        var req = withKind.defs().get(0).request();
        assertEquals("MEDIA", req.objectType());
        assertEquals("image", req.mediaKind());
    }

    @Test
    void nonStringFormControllerBlocksImportWithPositionalDetail() {
        Run run = run("formController:\n  script: /scripts/x.js\n");
        assertTrue(run.defs().isEmpty());
        assertTrue(details(run).contains("formController 必须是字符串"), () -> details(run));
        assertTrue(details(run).contains("models/attribute_probe.yaml"), () -> details(run));
    }

    @Test
    void overlongFormControllerBlocksBeforePersist() {
        Run run = run("formController: /scripts/" + "x".repeat(1000) + ".js\n");
        assertTrue(run.defs().isEmpty());
        assertTrue(details(run).contains("formController 超长"), () -> details(run));
    }

    @Test
    void booleanAttributesRejectStringValues() {
        Run run = run("quickCreate: \"yes\"\n");
        assertTrue(run.defs().isEmpty());
        assertTrue(details(run).contains("quickCreate 必须是布尔值"), () -> details(run));
    }

    @Test
    void configMustBeAnObject() {
        Run run = run("config: [1, 2]\n");
        assertTrue(run.defs().isEmpty());
        assertTrue(details(run).contains("config 必须是对象"), () -> details(run));
    }

    @Test
    void declaredInheritanceBlocksInsteadOfBeingSilentlyDropped() {
        Run run = run("parentTypeCode: base_asset\n");
        assertTrue(run.defs().isEmpty());
        assertTrue(details(run).contains("内容继承父类型"), () -> details(run));
    }
}
