package com.powercms.cms.identity.dto;

import java.util.List;
import java.util.UUID;

public record MeResponse(
    UUID userId,
    String username,
    String displayName,
    String email,
    String status,
    /** BACKEND / FRONTEND（spec 5.1）。 */
    String kind,
    /** 当前会话登录通道：BACKEND / FRONTEND（spec 5.4、附录 D）。 */
    String loginMode,
    /** 界面语言偏好（FR-I18N-004）：受支持标签或 null（跟随组织默认）。 */
    String language,
    List<String> organizationRoles,
    /** 后台工具布局 code 并集（spec 4.8）。 */
    List<String> layouts,
    List<SiteAccess> sites,
    /** 密码是否已过期（spec 5.6 / P1），供改密引导。 */
    boolean passwordExpired,
    /** 资料内容字段（后台 → system.user-profile；前台 → system.frontend-user，spec 5.2）。 */
    java.util.Map<String, Object> profile,
    /** 已绑定的会员组（tierLevel 不为空的角色），名称来自后台角色设置。 */
    List<MemberGroup> memberGroups
) {
    public record SiteAccess(
        UUID siteId,
        String siteKey,
        String siteName,
        List<String> roles,
        List<String> capabilities
    ) {}

    public record MemberGroup(String code, String name) {}
}
