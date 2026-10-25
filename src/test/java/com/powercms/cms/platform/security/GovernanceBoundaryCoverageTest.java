package com.powercms.cms.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 治理边界覆盖回归（规格 §10.2）。
 *
 * <p>本测试以「路由清单 + 注解断言」的方式冻结治理面边界：清单内的每个端点必须带
 * {@link InteractiveSessionGuard} 约束，清单外被刻意保留的端点必须不带。新增 IAM / 站点 /
 * Knox 等治理路由却漏加守卫时，本测试即刻失败——避免再次出现「工具面无出口、HTTP 面可绕过」。
 */
class GovernanceBoundaryCoverageTest {

    private static final String GUARD = "interactiveSessionGuard.requireInteractive";

    /** 治理面：机读凭据（API Token / OAuth access token）一律不得调用。 */
    private static final List<Route> GUARDED = List.of(
        // 站点创建 / 站点包导入与导出（§10.2 第 1 条）
        route("com.powercms.cms.site.web.SiteController", "create"),
        // FR-SITE-LC-007：站点物理删除是治理面破坏性写操作，机读凭据不得调用（须交互式会话）
        route("com.powercms.cms.site.web.SiteController", "delete"),
        route("com.powercms.cms.sitepackage.web.SitePackageController", "importPackage",
            "exportPackage", "listPackages", "getPackage"), // 类级守卫，覆盖全站导入/导出/列举
        // FR-IAM 全域（§10.2 第 2 条）
        route("com.powercms.cms.identity.web.UserController", "list", "get", "create",
            "references", "disable", "unlock"),
        route("com.powercms.cms.identity.web.RoleController", "list", "create", "delete",
            "updateCapabilities", "updateLayouts"),
        route("com.powercms.cms.identity.web.UserRoleController", "assign", "revoke"),
        route("com.powercms.cms.identity.web.PermissionController", "view", "save", "restoreInheritance"),
        route("com.powercms.cms.identity.web.OrganizationSettingsController", "update"),
        // 凭据管理面（§10.2 第 3 条）
        route("com.powercms.cms.identity.web.ApiTokenController", "create", "list", "delete",
            "revoke", "listAll"),
        route("com.powercms.cms.identity.web.OAuthClientController", "create", "revoke", "list"),
        route("com.powercms.cms.identity.web.OAuthAuthorizationController", "authorize", "approve"),
        // 秘密承载读面 + 运维写面（治理边界 R1/R2）
        route("com.powercms.cms.delivery.web.RenderCacheController", "clear"),
        route("com.powercms.cms.delivery.web.CacheWarmupController", "trigger"),
        route("com.powercms.cms.search.web.SearchController", "rebuildPublic"));

    /** 刻意保留给机读凭据的端点：自助面、运行期探测、以及已由站点能力收敛的路由。 */
    private static final List<Route> INTENTIONALLY_OPEN = List.of(
        route("com.powercms.cms.identity.web.IdentityAccessController", "me", "getProfile",
            "putProfile", "getFrontendUser"),
        route("com.powercms.cms.identity.web.UserController", "changePassword"),
        route("com.powercms.cms.identity.web.PermissionController", "check"),
        route("com.powercms.cms.identity.web.OrganizationSettingsController", "get", "publicBranding"),
        // 站点生命周期（freeze/archive/unarchive）走 @siteAuthorization，
        // 由 SiteAuthorization.tokenPermits 做 scope 收敛，不在本守卫职责内。
        route("com.powercms.cms.site.web.SiteController", "freeze", "archive", "unarchive", "list", "get",
            "deletionPreview"),
        // 注：删除本身（delete）已加交互式会话守卫，归入上方 GUARDED。
        // 治理边界 R3：无秘密、无副作用的系统级**只读**面保持机读可用（审计只读为规格明许）。
        route("com.powercms.cms.delivery.web.RenderCacheController", "stats"),
        route("com.powercms.cms.delivery.web.CacheWarmupController", "status"));

    @Test
    void governanceRoutesRequireInteractiveSession() {
        assertThat(GUARDED).allSatisfy(route ->
            route.methods().forEach(method -> assertThat(expressionOf(route.controller(), method))
                .as("%s#%s 必须带交互式会话守卫", route.controller().getSimpleName(), method)
                .isNotNull()
                .contains(GUARD)));
    }

    @Test
    void deliberateExceptionsStayOpen() {
        assertThat(INTENTIONALLY_OPEN).allSatisfy(route ->
            route.methods().forEach(method -> {
                String expression = expressionOf(route.controller(), method);
                assertThat(expression == null ? "" : expression)
                    .as("%s#%s 应保持机读凭据可用", route.controller().getSimpleName(), method)
                    .doesNotContain(GUARD);
            }));
    }

    /** 生效表达式：方法级注解优先，缺省回退类级注解。 */
    private static String expressionOf(Class<?> controller, String methodName) {
        Method[] methods = Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName)).toArray(Method[]::new);
        assertThat(methods).as("%s#%s 不存在", controller.getSimpleName(), methodName).isNotEmpty();
        for (Method method : methods) {
            PreAuthorize methodLevel = method.getAnnotation(PreAuthorize.class);
            if (methodLevel != null) return methodLevel.value();
        }
        PreAuthorize classLevel = controller.getAnnotation(PreAuthorize.class);
        return classLevel == null ? null : classLevel.value();
    }

    private static Route route(String className, String... methods) {
        try {
            return new Route(Class.forName(className), List.of(methods));
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("治理面控制器不存在: " + className, ex);
        }
    }

    private record Route(Class<?> controller, List<String> methods) {}
}
