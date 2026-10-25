package com.powercms.cms.identity.dto;

import com.powercms.cms.identity.entity.UserEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
    UUID id, String username, String displayName, String email,
    String status, String kind, UUID homeSiteId, UUID profileContentId,
    int failedLogins, Instant lastLoginAt, String lastLoginIp, String lastLoginMode,
    Instant passwordChangedAt, boolean externallyManaged,
    Instant createdAt, Instant updatedAt,
    String language,
    List<RoleAssignment> roles
) {
    public static UserResponse from(UserEntity user, List<RoleAssignment> roles) {
        return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
            user.getEmail(), user.getStatus(), user.getKind(), user.getHomeSiteId(),
            user.getProfileContentId(), user.getFailedLogins(),
            user.getLastLoginAt(), user.getLastLoginIp(), user.getLastLoginMode(),
            user.getPasswordChangedAt(), user.isExternallyManaged(),
            user.getCreatedAt(), user.getUpdatedAt(), user.getLanguage(), roles);
    }

    public record RoleAssignment(UUID roleId, String roleCode, String roleName, UUID siteId) {}
}
