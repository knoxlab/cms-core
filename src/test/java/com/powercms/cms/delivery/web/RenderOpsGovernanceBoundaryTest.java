package com.powercms.cms.delivery.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.CacheWarmupService;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.platform.security.InteractiveSessionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 渲染运维写面（缓存手动刷新、预热触发）的运行时边界回归。
 *
 * <p>两个 principal 均带 {@code ROLE_system-admin}，故 403 只能由 {@link InteractiveSessionGuard} 造成。
 * 同时验证治理边界 R3：只读的命中率快照与预热进度**刻意保持机读凭据可用**。
 */
@WebMvcTest(controllers = {RenderCacheController.class, CacheWarmupController.class}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
        com.powercms.cms.platform.web.ManagementAuditInterceptor.class,
        com.powercms.cms.platform.web.OrganizationContextFilter.class,
        // SiteFrozenWriteGuard 是站点冻结写门禁（WebMvcConfigurer），构造期需要 SiteRepository，
        // 而本切片不装配 JPA 仓储。这里排除它：本用例验证的是授权边界（403），
        // 与冻结门禁（423）无关——不排除则上下文会因缺少 SiteRepository 而启动失败。
        com.powercms.cms.site.web.SiteFrozenWriteGuard.class
    })
})
@Import(RenderOpsGovernanceBoundaryTest.MethodSecurityConfig.class)
class RenderOpsGovernanceBoundaryTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RenderDatasetCache cache;
    @MockitoBean AuditService auditService;
    @MockitoBean CacheWarmupService warmupService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final UUID SITE_ID = UUID.randomUUID();

    @Test
    void machineCredentialCannotClearCacheOrTriggerWarmup() throws Exception {
        mockMvc.perform(post("/api/v1/delivery/render-cache/clear").with(machineAdmin()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/sites/{siteId}/render-cache/warmup", SITE_ID).with(machineAdmin()))
            .andExpect(status().isForbidden());
        verify(cache, never()).clearAll();
        verify(warmupService, never()).warm(any());
    }

    @Test
    void readOnlySnapshotsStayAvailableToMachineCredential() throws Exception {
        mockMvc.perform(get("/api/v1/delivery/render-cache/stats").with(machineAdmin()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sites/{siteId}/render-cache/warmup", SITE_ID).with(machineAdmin()))
            .andExpect(status().isOk());
    }

    @Test
    void interactiveAdminCanClearCache() throws Exception {
        mockMvc.perform(post("/api/v1/delivery/render-cache/clear")
                .with(interactiveAdmin())
                .with(csrf()))
            .andExpect(status().isOk());
        verify(cache).clearAll();
    }

    private static RequestPostProcessor interactiveAdmin() {
        AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(USER_ID,
            User.withUsername("tester").password("").roles("system-admin").build());
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static RequestPostProcessor machineAdmin() {
        AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(USER_ID,
            User.withUsername("tester").password("").roles("system-admin").build(),
            TOKEN_ID, null, Set.of("mcp:publish"));
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
        @Bean
        InteractiveSessionGuard interactiveSessionGuard() {
            return new InteractiveSessionGuard();
        }
    }
}
