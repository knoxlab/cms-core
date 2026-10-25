package com.powercms.cms.identity.dto;

import java.util.UUID;

/**
 * GET /permissions/check 响应（spec 9.6）：当前主体是否具备某权利（供 UI）。
 */
public record PermissionCheckResponse(String objectType, UUID objectId, String right, boolean granted) {}
