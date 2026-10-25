package com.powercms.cms.delivery;

import freemarker.template.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreeMarkerSandboxTest {
    private final FreeMarkerSandbox sandbox = new FreeMarkerSandbox();

    @Test
    void rendersSafeModel() {
        String html = sandbox.render("ok.ftl", "Hello ${content.title}!",
            Map.of("content", Map.of("title", "PowerCMS")), true);
        assertThat(html).isEqualTo("Hello PowerCMS!");
    }

    @Test
    void disablesApiBuiltin() {
        Configuration config = sandbox.secureConfiguration(true);
        assertThat(config.isAPIBuiltinEnabled()).isFalse();
    }

    @Test
    void previewIncludesLineNumberOnSyntaxError() {
        assertThatThrownBy(() -> sandbox.render("bad.ftl", "${", Map.of(), true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("行 ")
            .hasMessageContaining("traceId=");
    }

    @Test
    void productionHidesTemplateDetail() {
        assertThatThrownBy(() -> sandbox.render("bad.ftl", "${", Map.of(), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("暂时无法渲染")
            .hasMessageContaining("traceId=")
            .hasMessageNotContaining("${");
    }

    @Test
    void dangerousNewBuiltinIsBlocked() {
        assertThatThrownBy(() -> sandbox.render("exec.ftl",
            "<#assign x = \"freemarker.template.utility.Execute\"?new()>${x(\"echo hi\")}", Map.of(), true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 基线（假绿灯根因）：`#include` 是合法 FTL 语法，AST 解析绿灯——因为 include 到 {@code process}
     * 期才按名解析，而沙箱每次只注册当前一个模板，届时必然抛 {@code TemplateNotFoundException}。
     * 故「校验通过、渲染失败」是结构性的，必须由 {@code TemplateSecurityValidator} 在校验期另行拦截。
     */
    @Test
    void includePassesParseButFailsRenderBecauseSandboxIsSingleTemplate() {
        String source = "<#include \"templates/other.ftl\">";
        assertThat(sandbox.parse("t.ftl", source).valid())
            .as("解析阶段对 #include 是绿灯，故校验期必须另行拦截")
            .isTrue();
        assertThatThrownBy(() -> sandbox.render("t.ftl", source, Map.of(), true))
            .as("渲染期因单模板隔离必然失败")
            .isInstanceOf(IllegalArgumentException.class);
    }
}
