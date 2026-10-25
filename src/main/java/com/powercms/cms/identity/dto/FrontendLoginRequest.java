package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 前台登录请求（spec 5.4）：必须携带站点上下文 siteKey 或 host。
 */
public record FrontendLoginRequest(
    @NotBlank String username,
    @NotBlank String password,
    /** 站点标识（siteKey）；未提供时尝试按 Host 解析站点。 */
    String siteKey
) {}
