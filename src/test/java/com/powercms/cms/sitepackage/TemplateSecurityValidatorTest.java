package com.powercms.cms.sitepackage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板静态校验回归（FR-TPL-013 / FR-AGT-TPL-003）。
 *
 * <p>重点覆盖「沙箱不支持指令」的校验期拦截：{@code #include} / {@code #import} 语法合法、
 * AST 解析绿灯，但沙箱单模板隔离下渲染必然失败。若不在校验期拦截，Agent 会拿到
 * 「validate_template 通过」却在渲染期撞不可诊断错误——本测试锁死该假绿灯不再回归。
 *
 * <p>配套基线见 {@code FreeMarkerSandboxTest#includePassesParseButFailsRenderBecauseSandboxIsSingleTemplate}。
 */
class TemplateSecurityValidatorTest {

    // ---------- 沙箱不支持指令：必须在校验期拦截 ----------

    @Test
    void rejectsIncludeDirectiveWithActionableHint() {
        List<String> issues = TemplateSecurityValidator.validate(
            "<div><#include \"templates/header.ftl\"></div>");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0))
            .contains("#include")
            .contains("单模板隔离")           // 说清为什么不行
            .contains("templatePath")        // 给出替代做法
            .contains("htmlHead");           // 主题片段的正解
    }

    @Test
    void rejectsImportDirective() {
        List<String> issues = TemplateSecurityValidator.validate(
            "<#import \"templates/macros.ftl\" as m><@m.foo/>");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0)).contains("#import").contains("单模板隔离");
    }

    /** 方括号标签语法（模板以 [#ftl] 开头时生效）同样必须拦截。 */
    @Test
    void rejectsSquareBracketSyntaxInclude() {
        List<String> issues = TemplateSecurityValidator.validate(
            "[#ftl]\n[#include \"templates/header.ftl\"]");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0)).contains("#include");
    }

    /** `<#` 与指令名之间允许空白，变体不得绕过。 */
    @Test
    void rejectsWhitespaceVariantBetweenHashAndDirectiveName() {
        assertThat(TemplateSecurityValidator.validate("<#  include \"templates/a.ftl\">"))
            .hasSize(1);
    }

    /** 命中多个不支持指令时逐条列出。 */
    @Test
    void reportsEachUnsupportedDirective() {
        List<String> issues = TemplateSecurityValidator.validate(
            "<#include \"templates/a.ftl\"><#import \"templates/b.ftl\" as b>");

        assertThat(issues).hasSize(2);
    }

    // ---------- 不得误伤 ----------

    /** 同一模板内定义并使用宏是受支持的复用方式，绝不能被拦。 */
    @Test
    void doesNotRejectSameTemplateMacros() {
        String source = "<#macro card title><div class=\"card\">${title}</div></#macro>"
            + "<@card title=\"标题\" />";

        assertThat(TemplateSecurityValidator.validate(source)).isEmpty();
    }

    /** 普通文案提到 include / import 一词（无 # 指令形态）不得误伤。 */
    @Test
    void doesNotRejectPlainTextMentioningIncludeOrImport() {
        assertThat(TemplateSecurityValidator.validate("<p>请 include 主题片段，import 宏库</p>"))
            .isEmpty();
    }

    /** 转义后的示例文本（&lt;#include）是纯文本输出，不得误伤。 */
    @Test
    void doesNotRejectEscapedDirectiveInDocumentationText() {
        assertThat(TemplateSecurityValidator.validate("<code>&lt;#include \"x.ftl\"&gt;</code>"))
            .isEmpty();
    }

    /** 注释里出现 #include 只是注释内容，不得误伤。 */
    @Test
    void doesNotRejectDirectiveMentionedInsideComment() {
        assertThat(TemplateSecurityValidator.validate("<#-- 旧版曾用 # include 引入片段 -- ><p>ok</p>"))
            .isEmpty();
    }

    // ---------- 默认值运算符优先级陷阱：必须在校验期拦截 ----------

    /**
     * 线上真实事故形态：{@code (it.desc_t)!"" != ""} 实为 {@code (it.desc_t) ! ("" != "")}，
     * x 存在时整式求值为字符串，{@code #if} 抛 NonBooleanException。语法合法、解析绿灯，
     * 若不在校验期拦截就只会等到渲染/预览才炸。
     */
    @Test
    void rejectsDefaultOperatorPrecedenceTrap() {
        List<String> issues = TemplateSecurityValidator.validate(
            "<#if (it.desc_t)!'' != ''><p>${it.desc_t}</p></#if>");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0))
            .contains("优先级陷阱")
            .contains("NonBooleanException")   // 说清为什么不行
            .contains("!'' !=");               // 回显命中的原文
        assertThat(issues.get(0))
            .contains("((x)!")                 // 给出正确改写
            .contains("?has_content");         // 给出替代写法
    }

    /** 双引号空串变体（线上 75 处全部是这一形态）。 */
    @Test
    void rejectsDoubleQuotedVariant() {
        assertThat(TemplateSecurityValidator.validate("<#if (it.icon_s)!\"\" != \"\">x</#if>"))
            .hasSize(1);
    }

    /** 默认值不带括号同样中招：{@code x!'' != ''} 一样被解析成 {@code x ! ('' != '')}。 */
    @Test
    void rejectsTrapWithoutOuterParensOnVariable() {
        assertThat(TemplateSecurityValidator.validate("<#if x!'' != ''>y</#if>")).hasSize(1);
    }

    /** 其它比较运算符同属一类错误，不得只挡住 !=。 */
    @Test
    void rejectsOtherComparisonOperators() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls("<#if (a)!''==''>x</#if>")).hasSize(1);
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls("<#if (a)!0>=1>x</#if>")).hasSize(1);
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls("<#if (a)!1 gte 1>x</#if>")).hasSize(1);
        // FreeMarker 字面量单词运算符
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls("<#if a!0 gt 0>x</#if>")).hasSize(1);
    }

    /** 报错必须带行号，Agent 才能定位。 */
    @Test
    void reportsLineNumberOfTrap() {
        List<String> issues = TemplateSecurityValidator.checkExpressionPitfalls(
            "<div>\n  <span>ok</span>\n  <#if (a)!'' != ''>x</#if>\n</div>");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0)).contains("第 3 行");
    }

    /** 命中多行时逐条列出。 */
    @Test
    void reportsEachTrapOccurrence() {
        List<String> issues = TemplateSecurityValidator.checkExpressionPitfalls(
            "<#if (a)!'' != ''>1</#if>\n<#if (b)!'' == ''>2</#if>");

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0)).contains("第 1 行");
        assertThat(issues.get(1)).contains("第 2 行");
    }

    /** 容器 FTL 走本方法而非 validate：容器合法使用 {@code <#assign>}，不能被安全黑名单挡住。 */
    @Test
    void checkExpressionPitfallsIsIndependentOfSecurityBlacklist() {
        String containerFtl = "<#assign rows = items!contentQuery(\"case\", 10, \"sort\")>"
            + "<#list rows as it><#if ((it.desc_t)!'') != ''>${it.desc_t}</#if></#list>";

        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(containerFtl)).isEmpty();
        // 同一段走 validate 会因 <#assign> 被安全黑名单拦下（这正是容器不能复用它做陷阱检查的原因）
        assertThat(TemplateSecurityValidator.validate(containerFtl))
            .anyMatch(issue -> issue.contains("危险模式"));
    }

    // ---------- 陷阱检查不得误伤 ----------

    /** 正确写法（外层加括号）必须放行——这是修复后的标准形态。 */
    @Test
    void doesNotRejectCorrectlyParenthesizedDefault() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<#if ((item.image_s)!'') != ''>x</#if>")).isEmpty();
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<#if ((it.icon_s)!\"\") == \"\">x</#if>")).isEmpty();
    }

    /** {@code ?has_content} 是推荐替代写法。 */
    @Test
    void doesNotRejectHasContentForm() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<#if item.badge_s?has_content>x</#if>")).isEmpty();
    }

    /** 无 {@code !} 的普通比较（如 {@code ?trim}）不属该陷阱。 */
    @Test
    void doesNotRejectBuiltinComparison() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<#if nm?trim != ''>x</#if>")).isEmpty();
    }

    /** 独立的默认值（后面没有比较运算符）是正常写法。 */
    @Test
    void doesNotRejectBareDefaultWithoutComparison() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "${(it.title_s)!''}<span>${(it.icon_s)!''}</span>")).isEmpty();
    }

    /**
     * 裸 {@code >} / {@code <} 不纳入陷阱判定：{@code <#if (x)!''>} 里的 {@code >} 是标签结束符，
     * 若当作比较运算符会大面积误报（实测命中）。
     */
    @Test
    void doesNotConfuseTagTerminatorWithGreaterThanOperator() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<#if (it.icon_s)!''>x</#if><#list items as it>${(it.name_s)!''}</#list>")).isEmpty();
    }

    /** FTL 注释里的示例不参与渲染，不应误报。 */
    @Test
    void doesNotRejectTrapMentionedInsideFtlComment() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<#-- 反面示例：<#if (a)!'' != ''> 要写成 ((a)!'') != '' --><p>ok</p>")).isEmpty();
    }

    /**
     * 模板内嵌 {@code <script>} 的 JS 逻辑非不得误伤：FreeMarker 没有 {@code !} 前缀逻辑非，
     * 故这类 {@code !var ||}/{@code !var &&} 与陷阱无关（曾用宽运算符集合实测为误报源）。
     */
    @Test
    void doesNotRejectJavaScriptLogicalNot() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<script>if (!el || el.hidden) { el.value = !KEY && f(); }</script>")).isEmpty();
    }

    /** JS 严格不等 {@code !==} 的 {@code !} 后面不是默认值字面量，不得误伤。 */
    @Test
    void doesNotRejectJsStrictInequality() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<script>if (a !== b) { c(); }</script>")).isEmpty();
    }

    /** CSS {@code !important} 不得误伤。 */
    @Test
    void doesNotRejectCssImportant() {
        assertThat(TemplateSecurityValidator.checkExpressionPitfalls(
            "<style>.x{color:red !important}</style>")).isEmpty();
    }

    // ---------- 既有行为不回归 ----------

    @Test
    void ordinaryTemplatePasses() {
        String source = "<div class=\"content-item\"><h2>${content.title!}</h2></div>";
        assertThat(TemplateSecurityValidator.validate(source)).isEmpty();
        assertThat(TemplateSecurityValidator.isSafe(source)).isTrue();
    }

    @Test
    void stillRejectsExistingDangerousPatterns() {
        assertThat(TemplateSecurityValidator.validate("${\"x\"?new()}")).isNotEmpty();
        assertThat(TemplateSecurityValidator.validate(
            "<#assign x = \"java.lang.Runtime\">")).isNotEmpty();
    }

    @Test
    void stillRejectsBlankAndOversizedContent() {
        assertThat(TemplateSecurityValidator.validate("   "))
            .containsExactly("模板内容不能为空");
        assertThat(TemplateSecurityValidator.validate("a".repeat(TemplateSecurityValidator.MAX_CONTENT_LENGTH + 1)))
            .anyMatch(issue -> issue.contains("上限"));
    }
}
