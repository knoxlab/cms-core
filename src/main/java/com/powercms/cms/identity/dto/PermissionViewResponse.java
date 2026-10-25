package com.powercms.cms.identity.dto;

import java.util.List;
import java.util.UUID;

/**
 * GET /permissions 响应（spec 9.6 / 4.7 UI）：直接 ACL + 有效 ACL + 继承来源。
 */
public record PermissionViewResponse(
    String objectType,
    UUID objectId,
    boolean inherit,
    String sourceType,
    UUID sourceId,
    List<Entry> directAcl,
    List<Entry> effectiveAcl) {

    public record Entry(String principalType, UUID principalId, List<String> permissions) {}
}
