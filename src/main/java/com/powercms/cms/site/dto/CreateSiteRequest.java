package com.powercms.cms.site.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSiteRequest(
    @NotBlank @Size(max = 100) String siteKey,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 1000) String description,
    @Size(max = 20) String defaultLocale,
    @Size(max = 60) String timezone,
    java.util.UUID initialAdminUserId
) {}
