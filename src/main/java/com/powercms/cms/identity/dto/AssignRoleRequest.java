package com.powercms.cms.identity.dto;

import java.util.UUID;

public record AssignRoleRequest(UUID roleId, String roleCode, UUID siteId) {}
