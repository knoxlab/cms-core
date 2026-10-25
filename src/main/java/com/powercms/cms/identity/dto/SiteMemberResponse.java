package com.powercms.cms.identity.dto;

import java.util.List;
import java.util.UUID;

public record SiteMemberResponse(
    UUID userId,
    String username,
    String displayName,
    String email,
    String status,
    List<UserResponse.RoleAssignment> roles
) {}
