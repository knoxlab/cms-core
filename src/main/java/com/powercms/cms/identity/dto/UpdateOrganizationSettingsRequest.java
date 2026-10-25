package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PUT /organization/settings 请求体（spec 9.7）。
 */
public record UpdateOrganizationSettingsRequest(
    @Pattern(regexp = "USERNAME|EMAIL", message = "loginIdentifier 必须是 USERNAME 或 EMAIL")
    String loginIdentifier,
    @Size(max = 500) String portalUrl,
    @Size(max = 200) String mailDomain,
    @Size(max = 320) String fromEmail,
    @Size(max = 20) String defaultLanguage,
    @Size(max = 60) String defaultTimezone,
    @Size(max = 2000) String branding
) {}
