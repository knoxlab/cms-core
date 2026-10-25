package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.entity.SiteAuthorizationPolicyEntity;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.SiteAuthorizationPolicyRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import com.powercms.cms.workflow.WorkflowUserAccess;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class IdentityWorkflowUserAccess implements WorkflowUserAccess {

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final RoleRepository roles;
    private final SiteCapabilityEvaluator capabilities;
    private final SiteAuthorizationPolicyRepository policies;
    private final ObjectPermissionService objectPermissions;

    IdentityWorkflowUserAccess(UserRepository users, UserRoleRepository userRoles, RoleRepository roles,
                               SiteCapabilityEvaluator capabilities,
                               SiteAuthorizationPolicyRepository policies,
                               ObjectPermissionService objectPermissions) {
        this.users = users;
        this.userRoles = userRoles;
        this.roles = roles;
        this.capabilities = capabilities;
        this.policies = policies;
        this.objectPermissions = objectPermissions;
    }

    @Override
    public boolean canReview(String username, UUID siteId) {
        if (username == null || username.isBlank() || siteId == null) return false;
        var user = users.findByUsername(username).orElse(null);
        if (user == null || !UserEntity.STATUS_ACTIVE.equals(user.getStatus())) return false;
        return capabilities.has(user.getId(), siteId, SiteCapabilities.REVIEW);
    }

    /**
     * 内容 view 权利判定：与 {@code @siteAuthorization.content(..., 'read')} 同一口径
     * （对象 ACL 打破继承后按 ACL，否则回退站点能力），只是把登录态换成用户名。
     */
    @Override
    public boolean canViewContent(String username, UUID contentId) {
        if (username == null || username.isBlank() || contentId == null) return false;
        var user = users.findByUsername(username).orElse(null);
        if (user == null || !UserEntity.STATUS_ACTIVE.equals(user.getStatus())) return false;
        return objectPermissions.hasRight(user.getId(), ObjectType.CONTENT, contentId, ObjectType.VIEW);
    }

    @Override
    public Optional<String> findSiteAdminUsername(UUID siteId) {
        if (siteId == null) return Optional.empty();
        return userRoles.findBySiteId(siteId).stream()
            .filter(binding -> roles.findById(binding.getRoleId())
                .map(r -> BuiltInRoleCatalog.SITE_ADMIN.equals(BuiltInRoleCatalog.normalize(r.getCode())))
                .orElse(false))
            .map(binding -> users.findById(binding.getUserId()).orElse(null))
            .filter(u -> u != null && UserEntity.STATUS_ACTIVE.equals(u.getStatus()))
            .map(UserEntity::getUsername)
            .findFirst();
    }

    @Override
    public boolean isPeerReviewRequired(UUID siteId) {
        if (siteId == null) return false;
        return policies.findById(siteId).map(SiteAuthorizationPolicyEntity::isPeerReviewRequired).orElse(false);
    }
}
