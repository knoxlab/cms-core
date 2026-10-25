package com.powercms.cms.search.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SearchControllerSecurityTest {
    @Test
    void publicRebuildLivesOnSystemAdminManagementController() {
        PreAuthorize authorization;
        try {
            authorization = SearchController.class.getDeclaredMethod("rebuildPublic", java.util.UUID.class)
                .getAnnotation(PreAuthorize.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError(ex);
        }
        assertThat(authorization).isNotNull();
        assertThat(authorization.value())
            .isEqualTo("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)");
        assertThat(Arrays.stream(SearchController.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(PostMapping.class))
            .filter(java.util.Objects::nonNull)
            .flatMap(mapping -> Arrays.stream(mapping.value())))
            .contains("/public/rebuild");
        assertThat(Arrays.stream(PublicSearchController.class.getDeclaredMethods())
            .anyMatch(method -> method.getAnnotation(PostMapping.class) != null)).isFalse();
    }

    /**
     * DEF-SEARCH-04：管理索引全量重建属系统级运维写面，收敛到 system-admin。此前为站点 {@code write}，
     * 使「菜单已隐藏」的站点管理员仍可直连 {@code POST /sites/{siteId}/search/rebuild} 成功提交，
     * 与 TC-M60-022「直接访问时不渲染可成功提交的写控件；提交被拒绝并给出权限说明」冲突。
     */
    @Test
    void adminRebuildRequiresSystemAdmin() {
        PreAuthorize authorization;
        try {
            authorization = SearchController.class.getDeclaredMethod("rebuild", java.util.UUID.class)
                .getAnnotation(PreAuthorize.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError(ex);
        }
        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('system-admin')");
        assertThat(authorization.value()).doesNotContain("'write'");
    }

    /** 检索本身是站点 read 能力，收敛写面不得误伤读面。 */
    @Test
    void adminSearchStillRequiresSiteRead() {
        PreAuthorize authorization;
        try {
            authorization = SearchController.class
                .getDeclaredMethod("search", java.util.UUID.class, SearchController.SearchRequest.class)
                .getAnnotation(PreAuthorize.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError(ex);
        }
        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("@siteAuthorization.check(authentication, #siteId, 'read')");
    }
}
