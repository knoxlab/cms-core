package com.powercms.cms.identity.dto;

/**
 * GET/PUT /organization/settings 响应（spec 9.7 / 5.5）。
 */
public record OrganizationSettingsResponse(
    String loginIdentifier,
    String portalUrl,
    String mailDomain,
    String fromEmail,
    String defaultLanguage,
    String defaultTimezone,
    String branding
) {}
