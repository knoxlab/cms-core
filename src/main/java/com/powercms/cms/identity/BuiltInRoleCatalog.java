package com.powercms.cms.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in role codes, scopes and capability matrix (identity spec ch. 3–4).
 */
public final class BuiltInRoleCatalog {

    public static final String SYSTEM_ADMIN = "system-admin";
    public static final String MIGRATION_ADMIN = "migration-admin";
    public static final String MIGRATION_AUDITOR = "migration-auditor";
    public static final String SITE_ADMIN = "site-admin";
    public static final String TEMPLATE_ENGINEER = "template-engineer";
    public static final String EDITOR = "editor";
    public static final String REVIEWER = "reviewer";
    public static final String PUBLISHER = "publisher";
    public static final String CONTENT_PUBLISHER = "content-publisher";

    /** 登录通道系统角色（spec 3.3）：只控制登录通道与后台工具资格，不授予站点能力。 */
    public static final String FRONT_END_USER = "front-end-user";
    public static final String BACK_END_USER = "back-end-user";
    /** 未登录访客（spec 3.3）：系统伪角色，只能出现在对象 ACL 的 view。 */
    public static final String ANONYMOUS = "anonymous";
    /** 对象所有者（spec 3.3）：系统伪角色，不可当普通角色分配。 */
    public static final String OWNER = "owner";

    public static final String SCOPE_ORG = "ORG";
    public static final String SCOPE_SITE = "SITE";

    public record Seed(String code, String name, String description, String scope) {}

    private static final Map<String, Set<String>> MATRIX = new LinkedHashMap<>();
    private static final Set<String> CHANNEL_ROLES = Set.of(FRONT_END_USER, BACK_END_USER, ANONYMOUS, OWNER);

    static {
        MATRIX.put(SYSTEM_ADMIN, SiteCapabilities.ALL);
        MATRIX.put(SITE_ADMIN, Set.of(
            SiteCapabilities.READ, SiteCapabilities.WRITE, SiteCapabilities.REVIEW,
            SiteCapabilities.DEPLOY, SiteCapabilities.MODEL, SiteCapabilities.TEMPLATE,
            SiteCapabilities.ADMIN, SiteCapabilities.STATS, SiteCapabilities.STATS_ADMIN,
            // FR-LOG-PM-001：site-admin 仅持有 logs（SITE 可见域），不含 logs-admin（不得导出）
            SiteCapabilities.LOGS));
        MATRIX.put(EDITOR, Set.of(SiteCapabilities.READ, SiteCapabilities.WRITE));
        MATRIX.put(REVIEWER, Set.of(
            SiteCapabilities.READ, SiteCapabilities.REVIEW));
        MATRIX.put(PUBLISHER, Set.of(SiteCapabilities.READ, SiteCapabilities.REVIEW, SiteCapabilities.DEPLOY));
        MATRIX.put(TEMPLATE_ENGINEER, Set.of(SiteCapabilities.READ, SiteCapabilities.TEMPLATE));
        MATRIX.put(CONTENT_PUBLISHER, Set.of(
            SiteCapabilities.READ, SiteCapabilities.WRITE, SiteCapabilities.REVIEW));
        MATRIX.put(MIGRATION_ADMIN, Set.of(SiteCapabilities.MIGRATE));
        MATRIX.put(MIGRATION_AUDITOR, Set.of(SiteCapabilities.READ));
    }

    private BuiltInRoleCatalog() {}

    public static List<Seed> seeds() {
        return List.of(
            new Seed(SYSTEM_ADMIN, "系统管理员", "组织级全部管理权限", SCOPE_ORG),
            new Seed(MIGRATION_ADMIN, "迁移管理员", "配置与执行 V11 迁移", SCOPE_ORG),
            new Seed(MIGRATION_AUDITOR, "迁移审计员", "审核迁移证据，无切换权", SCOPE_ORG),
            new Seed(SITE_ADMIN, "站点管理员", "站点设置、模型、流程与本站成员", SCOPE_SITE),
            new Seed(TEMPLATE_ENGINEER, "模板工程师", "模板、主题、容器与预览构建", SCOPE_SITE),
            new Seed(EDITOR, "编辑", "创建修改内容并提交审核", SCOPE_SITE),
            new Seed(REVIEWER, "审核员", "批准、驳回与任务委派", SCOPE_SITE),
            new Seed(PUBLISHER, "发布管理员", "Release、部署与回滚", SCOPE_SITE),
            new Seed(CONTENT_PUBLISHER, "内容发布者", "编辑+审核便利角色，仍受同行评审约束", SCOPE_SITE),
            new Seed(FRONT_END_USER, "前台登录用户", "允许走前台登录通道；不得绑定后台布局", SCOPE_ORG),
            new Seed(BACK_END_USER, "后台登录用户", "允许走后台登录通道；仅 kind=BACKEND 的员工账号可授予", SCOPE_ORG),
            new Seed(ANONYMOUS, "匿名访客", "未登录访客伪角色；只能出现在对象 ACL 的 view", SCOPE_ORG)
        );
    }

    public static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isBuiltIn(String code) {
        return MATRIX.containsKey(normalize(code)) || CHANNEL_ROLES.contains(normalize(code));
    }

    /** 登录通道 / 伪角色：不授予站点能力，也不出现在能力矩阵中。 */
    public static boolean isChannelRole(String code) {
        return CHANNEL_ROLES.contains(normalize(code));
    }

    public static boolean isOrgScope(String code) {
        return SCOPE_ORG.equals(scope(code));
    }

    public static String scope(String code) {
        String normalized = normalize(code);
        if (SYSTEM_ADMIN.equals(normalized) || MIGRATION_ADMIN.equals(normalized)
            || MIGRATION_AUDITOR.equals(normalized)
            || CHANNEL_ROLES.contains(normalized)) {
            return SCOPE_ORG;
        }
        return SCOPE_SITE;
    }

    public static Set<String> capabilities(String code) {
        return MATRIX.getOrDefault(normalize(code), Set.of());
    }
}
