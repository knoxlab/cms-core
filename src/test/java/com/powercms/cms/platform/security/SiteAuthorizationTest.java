package com.powercms.cms.platform.security;

import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.SiteCapabilities;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.publish.repo.*;
import com.powercms.cms.sitepackage.repo.SiteTemplateAclRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import com.powercms.cms.sitepackage.repo.TemplateProjectRepository;
import com.powercms.cms.workflow.repo.NotificationRepository;
import com.powercms.cms.workflow.repo.WorkflowDefinitionRepository;
import com.powercms.cms.workflow.repo.WorkflowTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteAuthorizationTest {
    private final SiteCapabilityEvaluator evaluator = mock(SiteCapabilityEvaluator.class);
    private final ObjectPermissionService objectPermissions = mock(ObjectPermissionService.class);
    private final SiteAuthorization authorization = new SiteAuthorization(evaluator, objectPermissions,
        mock(CategoryRepository.class), mock(ContentItemRepository.class), mock(ContentTypeRepository.class),
        mock(MediaAssetRepository.class), mock(WorkflowDefinitionRepository.class), mock(WorkflowTaskRepository.class),
        mock(NotificationRepository.class), mock(TemplateProjectRepository.class),
        mock(SiteTemplateRepository.class), mock(SiteTemplateAclRepository.class),
        mock(BuildJobRepository.class),
        mock(ReleaseRepository.class), mock(DeployTargetRepository.class), mock(DeploymentRepository.class),
        mock(BuildArtifactRepository.class));

    @Test
    void checkDelegatesToCapabilityEvaluator() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        when(evaluator.has(userId, site, "write")).thenReturn(true);
        assertThat(authorization.check(auth(userId), site, "write")).isTrue();
        assertThat(authorization.check(auth(userId), UUID.randomUUID(), "write")).isFalse();
    }

    @Test
    void templateWorkAllowsWriteOrTemplate() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        when(evaluator.has(userId, site, SiteCapabilities.TEMPLATE)).thenReturn(true);
        assertThat(authorization.templateWork(auth(userId), site)).isTrue();
    }

    @Test
    void templateWorkAllowsWriteScopedToken() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        when(evaluator.has(userId, site, SiteCapabilities.TEMPLATE)).thenReturn(false);
        when(evaluator.has(userId, site, SiteCapabilities.WRITE)).thenReturn(true);
        assertThat(authorization.templateWork(tokenAuth(userId, site, Set.of("write")), site)).isTrue();
    }

    @Test
    void tokenScopeMissIsTokenScopeDenied() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        when(evaluator.has(userId, site, "write")).thenReturn(true);
        assertThatThrownBy(() -> authorization.check(tokenAuth(userId, site, Set.of("read")), site, "write"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.TOKEN_SCOPE_DENIED);
    }

    @Test
    void tokenSiteMismatchIsTokenScopeDenied() {
        UUID userId = UUID.randomUUID(), allowed = UUID.randomUUID(), other = UUID.randomUUID();
        when(evaluator.has(userId, other, "read")).thenReturn(true);
        assertThatThrownBy(() -> authorization.check(tokenAuth(userId, allowed, Set.of()), other, "read"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.TOKEN_SCOPE_DENIED);
    }

    private UsernamePasswordAuthenticationToken auth(UUID userId) {
        var details = User.withUsername("tester").password("x").authorities("ROLE_USER").build();
        return UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUserPrincipal(userId, details), "x", details.getAuthorities());
    }

    private UsernamePasswordAuthenticationToken tokenAuth(UUID userId, UUID tokenSiteId, Set<String> scopes) {
        var details = User.withUsername("tester").password("x").authorities("ROLE_USER").build();
        return UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUserPrincipal(userId, details, UUID.randomUUID(), tokenSiteId, scopes),
            "N/A", details.getAuthorities());
    }
}
