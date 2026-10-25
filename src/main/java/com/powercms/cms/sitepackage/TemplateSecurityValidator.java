package com.powercms.cms.sitepackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板静态安全校验（FR-TPL-013 / FR-AGT-TPL-003）：危险表达式与危险类引用的确定性黑名单 + 资源上限。
 *
 * <p>单一实现来源：{@code save_template}、{@code validate_template}、{@code preview_template} 与
 * 代码模板生成自检共用本类，避免各处复制黑名单导致治理口径漂移。
 *
 * <p>策略为**黑名单 + 上限**（不是白名单）：命中即拒绝并可定位到具体标记；FreeMarker 语法层面的
 * 未知标签/语法错误由 {@code FreeMarkerSandbox.parse} 在解析阶段拒绝，两者互补。
 *
 * <p>除安全黑名单外，本类还拦截两类**语法合法但渲染期必然失败**的写法——它们都会形成
 * 「校验通过 → 渲染报错」的假绿灯，对 Agent 是最不可诊断的失败模式：
 * <ol>
 *   <li><b>沙箱不支持的指令</b>（见 {@link #UNSUPPORTED_DIRECTIVE_HINT}）：{@code #include} / {@code #import}
 *       按名引用其他模板，与单模板隔离的沙箱不兼容。</li>
 *   <li><b>默认值运算符优先级陷阱</b>（见 {@link #PRECEDENCE_HINT}）：{@code (x)!"d" != "e"} 实为
 *       {@code (x) ! ("d" != "e")}，{@code #if} 会收到字符串而抛 {@code NonBooleanException}。</li>
 * </ol>
 *
 * <p>{@link #checkExpressionPitfalls(String)} 可单独调用（不含安全黑名单）：容器 FTL 片段合法使用
 * {@code <#assign>}，不能走 {@link #validate(String)}。
 */
public final class TemplateSecurityValidator {

    /** 模板内容上限（字符数）。 */
    public static final int MAX_CONTENT_LENGTH = 512_000;

    /** 禁止出现的危险标记（小写比对）。 */
    public static final Set<String> FORBIDDEN_PATTERNS = Set.of(
        "runtime.getruntime",
        "processbuilder",
        "java.io",
        "java.nio",
        "java.net",
        "java.lang.reflect",
        "classloader",
        "class.forname",
        "getruntime",
        "exec(",
        "<#assign",
        "?new(",
        "freemarker.template.utility",
        "objectconstructor",
        "jythonruntime",
        "beanshell"
    );

    /**
     * 沙箱不支持、必须在校验期拦截的 FreeMarker 指令。
     *
     * <p>沙箱每次渲染只向 {@code StringTemplateLoader} 注册**当前这一个**模板
     * （见 {@code FreeMarkerSandbox#render}），因此按名引用其他模板的 {@code #include} / {@code #import}
     * 在渲染期必然抛 {@code TemplateNotFoundException}。而它是**合法 FTL 语法**，
     * {@code FreeMarkerSandbox.parse} 只做 AST 解析（include 到 {@code process} 期才按名解析），
     * 会返回「解析通过」——所以单靠解析阶段的校验拦不住，必须在此显式识别。
     *
     * <p>匹配同时兼容两种标签语法（默认尖括号与 {@code [#ftl]} 声明的方括号）：
     * {@code <#include ...>}、{@code [#include ...]}。裸写 {@code <#include} 在 FTL 里本身就是指令，
     * 想原样输出必须转义，故不存在误伤正常文案的可能。
     */
    private static final Pattern UNSUPPORTED_DIRECTIVE_PATTERN =
        Pattern.compile("[<\\[]\\s*#\\s*(include|import)\\b");

    /**
     * FreeMarker 默认值运算符 {@code !} 的**优先级陷阱**。
     *
     * <p>{@code !} 的优先级**低于**比较/逻辑运算符，因此 {@code (x)!"d" != "e"} 会被解析为
     * {@code (x) ! ("d" != "e")}：x 存在时整个表达式求值为 x 本身（字符串），{@code #if} 收到字符串后
     * 抛 {@code NonBooleanException}；x 缺失时才恰好得到布尔值。这类写法语法合法、AST 解析绿灯，
     * 但**渲染期必然失败**——与 {@link #UNSUPPORTED_DIRECTIVE_PATTERN} 同属「假绿灯」，必须在校验期拦截。
     *
     * <p>匹配策略：默认值字面量/路径之后**只隔空白**就出现比较运算符。安全写法
     * {@code ((x)!"d") != "e"} 的默认值之后紧跟 {@code )}，因此不会命中。
     *
     * <p>刻意排除三类运算符，均为误报源、收益低于代价：
     * <ul>
     *   <li>{@code &&} / {@code ||}：模板内嵌 {@code <script>} 中 {@code if (!el || ...)} 这类 JS
     *       逻辑非会大面积误报，而 FreeMarker 本身没有 {@code !} 前缀逻辑非。</li>
     *   <li>裸 {@code >} / {@code <}：与 FTL 标签结束符冲突——{@code <#if (x)!''>} 会把标签的
     *       {@code >} 误判成比较运算符（实测命中的误报）。带等号的 {@code >=} / {@code <=} 无此歧义，保留。</li>
     * </ul>
     */
    private static final Pattern DEFAULT_OPERATOR_PRECEDENCE_TRAP = Pattern.compile(
        "!\\s*(?:'[^'\\n]*'|\"[^\"\\n]*\"|\\d+(?:\\.\\d+)?|[A-Za-z_][\\w.]*)\\s*"
            + "(?:!=|==|>=|<=|\\bgt\\b|\\blt\\b|\\bgte\\b|\\blte\\b)");

    /** FTL 注释（{@code <#-- -->}）：其中的文本不参与渲染，扫描前应剔除。 */
    private static final Pattern FTL_COMMENT_PATTERN = Pattern.compile("<#--.*?-->", Pattern.DOTALL);

    /** 优先级陷阱的拦截话术：说明「为什么错」+「怎么改」。 */
    public static final String PRECEDENCE_HINT =
        "FreeMarker 默认值运算符 `!` 的优先级低于比较/逻辑运算符，`(x)!\"d\" != \"e\"` 实为 `(x) ! (\"d\" != \"e\")`，"
            + "x 存在时整式求值为 x 本身（字符串），`#if` 收到字符串即抛 NonBooleanException。改写："
            + "① 给默认值整体加外层括号 `((x)!\"d\") != \"e\"`（推荐）；② 或改用 `x?has_content`。";

    /** 不支持指令的拦截话术：说明「为什么不行」+「替代做法」，供 Agent 直接照做。 */
    public static final String UNSUPPORTED_DIRECTIVE_HINT =
        "模板沙箱为单模板隔离（每次渲染只注册当前模板），不支持 #include / #import 按名引用其他模板。替代做法："
            + "① 主题片段请直接使用服务端已组合的注入变量 `htmlHead` / `header` / `footer`；"
            + "② 组件复用请在容器结构或其组件实例上声明 `templatePath`，"
            + "或按 content type 走展示模板（displayTemplate / 容器结构 code）分发；"
            + "③ 同一模板内定义并使用宏（`#macro` / `<@...>`）不受影响。";

    private TemplateSecurityValidator() {
    }

    /**
     * 校验模板内容安全性（含沙箱不支持指令的拦截）。
     *
     * @param content 模板源码（FreeMarker/HTML）
     * @return 违规明细；空列表代表通过
     */
    public static List<String> validate(String content) {
        List<String> issues = new ArrayList<>();
        if (content == null || content.isBlank()) {
            issues.add("模板内容不能为空");
            return issues;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            issues.add("模板内容超过 " + (MAX_CONTENT_LENGTH / 1024) + "KB 上限");
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (lower.contains(pattern)) {
                issues.add("模板包含危险模式: " + pattern);
            }
        }
        Matcher unsupported = UNSUPPORTED_DIRECTIVE_PATTERN.matcher(lower);
        while (unsupported.find()) {
            issues.add("模板使用了不支持的指令 #" + unsupported.group(1) + "：" + UNSUPPORTED_DIRECTIVE_HINT);
        }
        issues.addAll(checkExpressionPitfalls(content));
        return issues;
    }

    /**
     * 仅做「默认值运算符优先级陷阱」检查（不含安全黑名单与资源上限）。
     *
     * <p>容器 FTL 必须单独调用本方法，不能走 {@link #validate(String)}：容器片段合法使用
     * {@code <#assign}，而该方法把 {@code <#assign} 列为危险模式。
     *
     * @param content FTL 源码（也可为模板文件路径，路径不含陷阱形态故自然放行）
     * @return 违规明细（带行号）；空列表代表通过
     */
    public static List<String> checkExpressionPitfalls(String content) {
        List<String> issues = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return issues;
        }
        String scanned = blankOutFtlComments(content);
        Matcher matcher = DEFAULT_OPERATOR_PRECEDENCE_TRAP.matcher(scanned);
        while (matcher.find()) {
            issues.add("第 " + lineOf(content, matcher.start()) + " 行存在 FreeMarker 默认值优先级陷阱：`"
                + matcher.group().replaceAll("\\s+", " ") + "`。" + PRECEDENCE_HINT);
        }
        return issues;
    }

    /** 将 FTL 注释内容替换为等长空白（保留换行），使行号与原始源码一致。 */
    private static String blankOutFtlComments(String content) {
        Matcher matcher = FTL_COMMENT_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder(content);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (sb.charAt(i) != '\n') {
                    sb.setCharAt(i, ' ');
                }
            }
        }
        return sb.toString();
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 是否通过安全校验。 */
    public static boolean isSafe(String content) {
        return validate(content).isEmpty();
    }
}
