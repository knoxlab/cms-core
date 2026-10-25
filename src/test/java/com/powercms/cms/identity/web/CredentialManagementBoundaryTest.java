package com.powercms.cms.identity.web;

import com.powercms.cms.identity.ApiTokenService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.identity.OAuthClientService;
import com.powercms.cms.identity.OAuthTokenService;
import com.powercms.cms.identity.OrganizationSettingsService;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.identity.dto.ApiTokenCreatedResponse;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.platform.security.InteractiveSessionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治理面边界回归（规格 §10.2 / 登记 §八）：Agent 持有的机读凭据（API Token / OAuth access token）
 * 不得触碰凭据管理面（自我扩权）、FR-IAM 全域、组织设置。
 *
 * <p>两个 principal 都带 {@code ROLE_system-admin}，因此 403 的唯一成因只能是
 * {@link InteractiveSessionGuard}——避免「因缺角色而 403」的假绿；交互式会话组验证守卫未误伤管理台。
 */
@WebMvcTest(controllers = {ApiTokenController.class, OAuthClientController.class,
    OAuthAuthorizationController.class, UserController.class, RoleController.class,

    UserRoleController.class, OrganizationSettingsController.class, PermissionController.class}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
        com.powercms.cms.platform.web.ManagementAuditInterceptor.class,
        com.powercms.cms.platform.web.OrganizationContextFilter.class,
        // SiteFrozenWriteGuard 是站点冻结写门禁（WebMvcConfigurer），构造期需要 SiteRepository，
        // 而本切片不装配 JPA 仓储。这里排除它：本用例验证的是授权边界（403），
        // 与冻结门禁（423）无关——不排除则上下文会因缺少 SiteRepository 而启动失败。
        com.powercms.cms.site.web.SiteFrozenWriteGuard.class
    })
})
@Import(CredentialManagementBoundaryTest.MethodSecurityConfig.class)
class CredentialManagementBoundaryTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ApiTokenService tokens;
    @MockitoBean OAuthClientService oauthClients;
    @MockitoBean OAuthTokenService oauthTokens;
    @MockitoBean IdentityService identityService;
    @MockitoBean OrganizationSettingsService settingsService;
    @MockitoBean ObjectPermissionService objectPermissions;
    @MockitoBean SiteCapabilityEvaluator capabilities;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID ROLE_ID = UUID.randomUUID();

    private static RequestPostProcessor interactiveSession() {
        AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(USER_ID,
            User.withUsername("tester").password("").roles("system-admin").build());
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static RequestPostProcessor machineCredential() {
        AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(USER_ID,
            User.withUsername("tester").password("").roles("system-admin").build(),
            TOKEN_ID, null, Set.of("mcp:publish"));
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static final String CREATE_TOKEN_BODY = "{\"name\":\"agent\",\"expiresAt\":\"2027-01-01T00:00:00Z\"}";

    // ---- 凭据管理面：防 Agent 自我扩权 ----

    @Test
    void machineCredentialCannotMintToken() throws Exception {
        mockMvc.perform(post("/api/v1/me/tokens")
                .with(machineCredential())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_TOKEN_BODY))
            .andExpect(status().isForbidden());
        verify(tokens, never()).create(any(), any());
    }

    @Test
    void machineCredentialCannotListOrDeleteOwnTokens() throws Exception {
        mockMvc.perform(get("/api/v1/me/tokens").with(machineCredential()))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/me/tokens/{id}", TOKEN_ID)
                .with(machineCredential())
                .with(csrf()))
            .andExpect(status().isForbidden());
        verify(tokens, never()).delete(any(), any());
    }

    @Test
    void machineCredentialCannotRegisterOAuthClient() throws Exception {
        mockMvc.perform(post("/api/v1/oauth/clients")
                .with(machineCredential())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"agent-client\",\"redirectUris\":[\"http://localhost/cb\"]}"))
            .andExpect(status().isForbidden());
        verify(oauthClients, never()).register(any(), any());
    }

    @Test
    void machineCredentialCannotApproveConsent() throws Exception {
        mockMvc.perform(post("/oauth/authorize")
                .with(machineCredential())
                .with(csrf())
                .param("client_id", "pcms_client")
                .param("redirect_uri", "http://localhost/cb")
                .param("approve", "true"))
            .andExpect(status().isForbidden());
    }

    // ---- FR-IAM 全域与组织设置 ----

    @Test
    void machineCredentialCannotReadUsersOrRoles() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .with(machineCredential())
                .requestAttr("organizationId", ORG_ID))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/roles")
                .with(machineCredential())
                .requestAttr("organizationId", ORG_ID))
            .andExpect(status().isForbidden());
        verify(identityService, never()).listUsers(any());
        verify(identityService, never()).listRoles(any());
    }

    @Test
    void machineCredentialCannotAssignRole() throws Exception {
        mockMvc.perform(post("/api/v1/users/{userId}/roles", USER_ID)
                .with(machineCredential())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + ROLE_ID + "\"}"))
            .andExpect(status().isForbidden());
        verify(identityService, never()).assignRole(any(), any(), any());
    }

    @Test
    void machineCredentialCannotTouchObjectPermissionsOrOrganizationSettings() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                .with(machineCredential())
                .param("objectType", "CONTENT")
                .param("objectId", UUID.randomUUID().toString()))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/organization/settings")
                .with(machineCredential())
                .with(csrf())
                .requestAttr("organizationId", ORG_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
        verify(settingsService, never()).update(any(), any());
    }

    @Test
    void interactiveSessionCanStillMintToken() throws Exception {
        when(tokens.create(any(), any())).thenReturn(new ApiTokenCreatedResponse(UUID.randomUUID(),
            "pcm_public", "agent", null, List.of("mcp:read"), Instant.now().plusSeconds(3600),
            null, Instant.now(), "pcm_secret"));
        mockMvc.perform(post("/api/v1/me/tokens")
                .with(interactiveSession())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_TOKEN_BODY))
            .andExpect(status().isCreated());
    }

    @Test
    void interactiveSessionCanStillReadUsers() throws Exception {
        when(identityService.listUsers(ORG_ID)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/users")
                .with(interactiveSession())
                .requestAttr("organizationId", ORG_ID))
            .andExpect(status().isOk());
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
