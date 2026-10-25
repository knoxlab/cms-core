package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.SiteAuthorizationPolicyRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityWorkflowUserAccessTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SiteCapabilityEvaluator capabilities = mock(SiteCapabilityEvaluator.class);
    private final ObjectPermissionService objectPermissions = mock(ObjectPermissionService.class);

    private IdentityWorkflowUserAccess access() {
        return new IdentityWorkflowUserAccess(
            users, mock(UserRoleRepository.class), mock(RoleRepository.class),
            capabilities, mock(SiteAuthorizationPolicyRepository.class), objectPermissions);
    }

    private UserEntity user(UUID userId, String username) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername(username);
        user.setStatus(UserEntity.STATUS_ACTIVE);
        return user;
    }

    @Test
    void editorIsNotAReviewer() {
        IdentityWorkflowUserAccess access = access();
        UUID site = UUID.randomUUID(), userId = UUID.randomUUID();
        when(users.findByUsername("editor1")).thenReturn(Optional.of(user(userId, "editor1")));
        when(capabilities.has(userId, site, SiteCapabilities.REVIEW)).thenReturn(false);

        assertThat(access.canReview("editor1", site)).isFalse();
    }

    /** 待办可见性：内容 view 权利按对象 ACL / 站点能力判定（与 `content(..., 'read')` 同口径）。 */
    @Test
    void contentVisibilityUsesObjectViewRight() {
        IdentityWorkflowUserAccess access = access();
        UUID userId = UUID.randomUUID(), contentId = UUID.randomUUID();
        when(users.findByUsername("reviewer1")).thenReturn(Optional.of(user(userId, "reviewer1")));
        when(objectPermissions.hasRight(userId, ObjectType.CONTENT, contentId, ObjectType.VIEW))
            .thenReturn(true, false);

        assertThat(access.canViewContent("reviewer1", contentId)).isTrue();
        assertThat(access.canViewContent("reviewer1", contentId)).isFalse();
    }

    @Test
    void contentVisibilityDeniedForUnknownOrDisabledUser() {
        IdentityWorkflowUserAccess access = access();
        UUID userId = UUID.randomUUID();
        UserEntity disabled = user(userId, "gone");
        disabled.setStatus("DISABLED");
        when(users.findByUsername("gone")).thenReturn(Optional.of(disabled));

        assertThat(access.canViewContent("gone", UUID.randomUUID())).isFalse();
        assertThat(access.canViewContent("nobody", UUID.randomUUID())).isFalse();
        assertThat(access.canViewContent(null, UUID.randomUUID())).isFalse();
    }
}
