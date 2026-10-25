package com.powercms.cms.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台布局目录（spec 4.8 / 8.7）：内置工具布局与内置角色默认绑定。
 * front-end-user 禁止绑定任何布局。
 */
public final class BackendLayoutCatalog {

    public static final String CONTENT = "content";
    public static final String MODEL = "model";
    public static final String MEDIA = "media";
    public static final String WORKFLOW = "workflow";
    public static final String TEMPLATE = "template";
    public static final String PUBLISH = "publish";
    public static final String USERS = "users";
    public static final String SITE_SETTINGS = "site-settings";
    public static final String AUDIT = "audit";
    public static final String MIGRATE = "migrate";

    public static final List<String> ALL = List.of(
        CONTENT, MODEL, MEDIA, WORKFLOW, TEMPLATE, PUBLISH, USERS, SITE_SETTINGS, AUDIT, MIGRATE);

    public record Seed(String code, String name) {}

    private static final Map<String, String> LAYOUTS = new LinkedHashMap<>();
    private static final Map<String, Set<String>> ROLE_LAYOUTS = new LinkedHashMap<>();

    static {
        LAYOUTS.put(CONTENT, "内容");
        LAYOUTS.put(MODEL, "模型");
        LAYOUTS.put(MEDIA, "媒体");
        LAYOUTS.put(WORKFLOW, "工作流");
        LAYOUTS.put(TEMPLATE, "模板");
        LAYOUTS.put(PUBLISH, "发布");
        LAYOUTS.put(USERS, "用户");
        LAYOUTS.put(SITE_SETTINGS, "站点设置");
        LAYOUTS.put(AUDIT, "审计");
        LAYOUTS.put(MIGRATE, "迁移");

        ROLE_LAYOUTS.put(BuiltInRoleCatalog.SYSTEM_ADMIN, Set.copyOf(ALL));
        ROLE_LAYOUTS.put(BuiltInRoleCatalog.MIGRATION_ADMIN, Set.of(MIGRATE));
        ROLE_LAYOUTS.put(BuiltInRoleCatalog.MIGRATION_AUDITOR, Set.of(MIGRATE, AUDIT));
        ROLE_LAYOUTS.put(BuiltInRoleCatalog.SITE_ADMIN, Set.of(
            CONTENT, MODEL, MEDIA, WORKFLOW, TEMPLATE, PUBLISH, USERS, SITE_SETTINGS));
        ROLE_LAYOUTS.put(BuiltInRoleCatalog.TEMPLATE_ENGINEER, Set.of(TEMPLATE, CONTENT));
        ROLE_LAYOUTS.put(BuiltInRoleCatalog.EDITOR, Set.of(CONTENT, MEDIA));
        ROLE_LAYOUTS.put(BuiltInRoleCatalog.REVIEWER, Set.of(WORKFLOW, CONTENT));
        ROLE_LAYOUTS.put(BuiltInRoleCatalog.PUBLISHER, Set.of(PUBLISH, WORKFLOW, CONTENT));
        ROLE_LAYOUTS.put(BuiltInRoleCatalog.CONTENT_PUBLISHER, Set.of(CONTENT, MEDIA, WORKFLOW));
    }

    private BackendLayoutCatalog() {}

    public static List<Seed> seeds() {
        return LAYOUTS.entrySet().stream()
            .map(e -> new Seed(e.getKey(), e.getValue()))
            .toList();
    }

    /** 内置角色默认布局；front-end-user / anonymous / owner 返回空集。 */
    public static Set<String> layoutsForRole(String roleCode) {
        return ROLE_LAYOUTS.getOrDefault(BuiltInRoleCatalog.normalize(roleCode), Set.of());
    }

    public static boolean isKnown(String code) {
        return code != null && LAYOUTS.containsKey(BuiltInRoleCatalog.normalize(code));
    }
}
