package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.RoleEntity;
import com.powercms.cms.identity.entity.SiteAuthorizationPolicyEntity;
import com.powercms.cms.identity.entity.UserRoleEntity;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.SiteAuthorizationPolicyRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Shared site/org capability resolution for APIs, {@code SiteAuthorization} and {@code /me}.
 */
@Component
public class SiteCapabilityEvaluator {

    public record SiteRoleView(UUID siteId, UUID roleId, String roleCode, String roleName) {}

    private final UserRoleRepository userRoles;
    private final RoleRepository roles;
    private final SiteAuthorizationPolicyRepository policies;

    public SiteCapabilityEvaluator(UserRoleRepository userRoles, RoleRepository roles,
                                   SiteAuthorizationPolicyRepository policies) {
        this.userRoles = userRoles;
        this.roles = roles;
        this.policies = policies;
    }

    public boolean isSystemAdmin(UUID userId) {
        if (userId == null) return false;
        return loadBindings(userId).stream().anyMatch(this::isOrgSystemAdmin);
    }

    public boolean isMigrationAdmin(UUID userId) {
        if (userId == null) return false;
        return loadBindings(userId).stream().anyMatch(binding ->
            binding.getSiteId() == null
                && BuiltInRoleCatalog.MIGRATION_ADMIN.equals(roleCode(binding)));
    }

    public boolean has(UUID userId, UUID siteId, String permission) {
        if (userId == null || siteId == null) return false;
        String perm = SiteCapabilities.normalize(permission);
        if (perm.isBlank()) return false;
        if (isSystemAdmin(userId)) return true;
        if (SiteCapabilities.MIGRATE.equals(perm) && isMigrationAdmin(userId)) return true;
        return capabilities(userId, siteId).contains(perm);
    }

    public Set<String> capabilities(UUID userId, UUID siteId) {
        if (userId == null || siteId == null) return Set.of();
        if (isSystemAdmin(userId)) return SiteCapabilities.ALL;
        SiteAuthorizationPolicyEntity policy = policies.findById(siteId).orElse(null);
        LinkedHashSet<String> caps = new LinkedHashSet<>();
        for (UserRoleEntity binding : loadBindings(userId)) {
            if (!siteId.equals(binding.getSiteId())) continue;
            RoleEntity role = roles.findById(binding.getRoleId()).orElse(null);
            if (role != null) caps.addAll(capabilitiesOf(role, policy));
        }
        return SiteCapabilities.copyOf(caps);
    }

    public List<SiteRoleView> siteRoles(UUID userId) {
        List<SiteRoleView> views = new ArrayList<>();
        for (UserRoleEntity binding : loadBindings(userId)) {
            RoleEntity role = roles.findById(binding.getRoleId()).orElse(null);
            if (role == null) continue;
            views.add(new SiteRoleView(binding.getSiteId(), role.getId(),
                BuiltInRoleCatalog.normalize(role.getCode()), role.getName()));
        }
        return views;
    }

    public Set<String> organizationRoleCodes(UUID userId) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (UserRoleEntity binding : loadBindings(userId)) {
            if (binding.getSiteId() != null) continue;
            String code = roleCode(binding);
            if (!code.isBlank()) codes.add(code);
        }
        return codes;
    }

    public Set<String> siteRoleCodes(UUID userId, UUID siteId) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (UserRoleEntity binding : loadBindings(userId)) {
            if (!siteId.equals(binding.getSiteId())) continue;
            String code = roleCode(binding);
            if (!code.isBlank()) codes.add(code);
        }
        return codes;
    }

    Set<String> capabilitiesOf(RoleEntity role, SiteAuthorizationPolicyEntity policy) {
        String code = BuiltInRoleCatalog.normalize(role.getCode());
        LinkedHashSet<String> caps = new LinkedHashSet<>();
        if (role.isBuiltIn() || BuiltInRoleCatalog.isBuiltIn(code)) {
            caps.addAll(BuiltInRoleCatalog.capabilities(code));
            if (policy != null) {
                if (policy.isReviewerCanWrite() && BuiltInRoleCatalog.REVIEWER.equals(code)) {
                    caps.add(SiteCapabilities.WRITE);
                }
                if (policy.isPublisherCanWrite() && BuiltInRoleCatalog.PUBLISHER.equals(code)) {
                    caps.add(SiteCapabilities.WRITE);
                }
            }
        } else {
            caps.addAll(SiteCapabilities.parse(role.getCapabilitiesJson()));
        }
        return caps;
    }

    private boolean isOrgSystemAdmin(UserRoleEntity binding) {
        return binding.getSiteId() == null
            && BuiltInRoleCatalog.SYSTEM_ADMIN.equals(roleCode(binding));
    }

    private String roleCode(UserRoleEntity binding) {
        RoleEntity role = roles.findById(binding.getRoleId()).orElse(null);
        return role == null ? "" : BuiltInRoleCatalog.normalize(role.getCode());
    }

    private List<UserRoleEntity> loadBindings(UUID userId) {
        List<UserRoleEntity> bindings = userRoles.findByUserId(userId);
        return bindings == null ? List.of() : bindings.stream().filter(Objects::nonNull).toList();
    }
}
