package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.RoleEntity;
import com.powercms.cms.identity.entity.SiteAuthorizationPolicyEntity;
import com.powercms.cms.identity.entity.UserRoleEntity;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.SiteAuthorizationPolicyRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteCapabilityEvaluatorTest {

    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final SiteAuthorizationPolicyRepository policies = mock(SiteAuthorizationPolicyRepository.class);
    private final SiteCapabilityEvaluator evaluator = new SiteCapabilityEvaluator(userRoles, roles, policies);

    @BeforeEach
    void stubPolicy() {
        when(policies.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void editorCanWriteButNotReviewOrDeploy() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        bind(userId, site, "editor", true);
        assertThat(evaluator.has(userId, site, SiteCapabilities.WRITE)).isTrue();
        assertThat(evaluator.has(userId, site, SiteCapabilities.READ)).isTrue();
        assertThat(evaluator.has(userId, site, SiteCapabilities.REVIEW)).isFalse();
        assertThat(evaluator.has(userId, site, SiteCapabilities.DEPLOY)).isFalse();
        assertThat(evaluator.has(userId, UUID.randomUUID(), SiteCapabilities.READ)).isFalse();
    }

    @Test
    void reviewerCannotWriteUntilPolicyAllows() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        bind(userId, site, "reviewer", true);
        assertThat(evaluator.has(userId, site, SiteCapabilities.REVIEW)).isTrue();
        assertThat(evaluator.has(userId, site, SiteCapabilities.WRITE)).isFalse();

        SiteAuthorizationPolicyEntity policy = SiteAuthorizationPolicyEntity.defaults(site);
        policy.setReviewerCanWrite(true);
        when(policies.findById(site)).thenReturn(Optional.of(policy));
        assertThat(evaluator.has(userId, site, SiteCapabilities.WRITE)).isTrue();
    }

    @Test
    void publisherCanDeploy() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        bind(userId, site, "publisher", true);
        assertThat(evaluator.has(userId, site, SiteCapabilities.DEPLOY)).isTrue();
        assertThat(evaluator.has(userId, site, SiteCapabilities.REVIEW)).isTrue();
        assertThat(evaluator.has(userId, site, SiteCapabilities.WRITE)).isFalse();
        assertThat(evaluator.has(userId, site, "site-admin")).isFalse();
    }

    @Test
    void templateEngineerHasTemplateNotWrite() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        bind(userId, site, "template-engineer", true);
        assertThat(evaluator.has(userId, site, SiteCapabilities.TEMPLATE)).isTrue();
        assertThat(evaluator.has(userId, site, SiteCapabilities.WRITE)).isFalse();
    }

    @Test
    void systemAdminBypassesSite() {
        UUID userId = UUID.randomUUID();
        bind(userId, null, "system-admin", true);
        assertThat(evaluator.has(userId, UUID.randomUUID(), SiteCapabilities.DEPLOY)).isTrue();
        assertThat(evaluator.isSystemAdmin(userId)).isTrue();
    }

    @Test
    void customRoleWithoutCapabilitiesIsDenied() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        bind(userId, site, "custom-publisher", false);
        assertThat(evaluator.has(userId, site, SiteCapabilities.READ)).isFalse();
    }

    @Test
    void customRoleWithReadWriteMatchesEditorContentSide() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID(), roleId = UUID.randomUUID();
        RoleEntity role = RoleEntity.create(UUID.randomUUID(), "desk-editor", "Desk", null, false, "SITE", "[\"read\",\"write\"]");
        when(userRoles.findByUserId(userId)).thenReturn(List.of(UserRoleEntity.of(userId, roleId, site)));
        when(roles.findById(roleId)).thenReturn(Optional.of(role));
        assertThat(evaluator.has(userId, site, SiteCapabilities.WRITE)).isTrue();
        assertThat(evaluator.has(userId, site, SiteCapabilities.REVIEW)).isFalse();
        assertThat(evaluator.has(userId, site, SiteCapabilities.DEPLOY)).isFalse();
    }

    @Test
    void adminAliasAcceptsSiteAdminPermissionString() {
        UUID userId = UUID.randomUUID(), site = UUID.randomUUID();
        bind(userId, site, "site-admin", true);
        assertThat(evaluator.has(userId, site, "admin")).isTrue();
        assertThat(evaluator.has(userId, site, "site-admin")).isTrue();
        assertThat(evaluator.has(userId, site, SiteCapabilities.MODEL)).isTrue();
    }

    private void bind(UUID userId, UUID siteId, String code, boolean builtIn) {
        UUID roleId = UUID.randomUUID();
        when(userRoles.findByUserId(userId)).thenReturn(List.of(UserRoleEntity.of(userId, roleId, siteId)));
        when(roles.findById(roleId)).thenReturn(Optional.of(
            RoleEntity.create(UUID.randomUUID(), code, code, null, builtIn, BuiltInRoleCatalog.scope(code), null)));
    }
}
