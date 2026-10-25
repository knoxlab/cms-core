package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.OrganizationSettingsEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 桥接 Spring Security UserDetailsService：通过用户名查询用户、角色和状态（FR-IAM-002）。
 */
@Component
class IdentityUserDetailsService implements UserDetailsService {

    private final UserRepository userRepo;
    private final UserRoleRepository userRoleRepo;
    private final RoleRepository roleRepo;
    private final IdentityProperties properties;
    private final OrganizationSettingsService settingsService;

    IdentityUserDetailsService(UserRepository userRepo, UserRoleRepository userRoleRepo,
                               RoleRepository roleRepo, IdentityProperties properties,
                               OrganizationSettingsService settingsService) {
        this.userRepo = userRepo;
        this.userRoleRepo = userRoleRepo;
        this.roleRepo = roleRepo;
        this.properties = properties;
        this.settingsService = settingsService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity user = resolveUser(username)
            .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));

        List<SimpleGrantedAuthority> authorities = userRoleRepo.findByUserId(user.getId()).stream()
            .filter(ur -> ur.getSiteId() == null)
            .map(ur -> roleRepo.findById(ur.getRoleId()).orElse(null))
            .filter(r -> r != null)
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getCode()))
            .toList();

        UserDetails details = User.builder()
            .username(user.getUsername())
            .password(user.getPasswordHash())
            .authorities(authorities)
            .disabled(UserEntity.STATUS_DISABLED.equals(user.getStatus())
                || UserEntity.STATUS_PENDING.equals(user.getStatus()))
            .accountLocked(UserEntity.STATUS_LOCKED.equals(user.getStatus()))
            .credentialsExpired(credentialsExpired(user, properties.getPasswordExpireDays()))
            .build();
        return new AuthenticatedUserPrincipal(user.getId(), details);
    }

    static boolean credentialsExpired(UserEntity user, int expireDays) {
        if (expireDays <= 0 || user.getPasswordChangedAt() == null) return false;
        return user.getPasswordChangedAt().plus(expireDays, ChronoUnit.DAYS).isBefore(Instant.now());
    }

    /**
     * 按组织 loginIdentifier 解析登录标识（spec 8.8 / AC-IAM-019）：
     * EMAIL 时仅按邮箱、USERNAME 时仅按用户名，不做跨模式回退。
     */
    private java.util.Optional<UserEntity> resolveUser(String loginId) {
        java.util.UUID orgId = settingsService.defaultOrganizationId();
        String identifier = settingsService.loginIdentifier(orgId);
        return OrganizationSettingsEntity.LOGIN_IDENTIFIER_EMAIL.equals(identifier)
            ? userRepo.findByEmailIgnoreCase(loginId)
            : userRepo.findByUsername(loginId);
    }
}
