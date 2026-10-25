package com.powercms.cms.site.dto;

import com.powercms.cms.site.entity.SiteEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SiteResponse(
    UUID id, String siteKey, String name, String description,
    String defaultLocale, String timezone, String status,
    Instant createdAt, Instant updatedAt,
    List<EnvInfo> environments
) {
    public record EnvInfo(UUID id, String name, String type) {}

    public static SiteResponse from(SiteEntity s, List<EnvInfo> envs) {
        return new SiteResponse(s.getId(), s.getSiteKey(), s.getName(), s.getDescription(),
            s.getDefaultLocale(), s.getTimezone(), s.getStatus(),
            s.getCreatedAt(), s.getUpdatedAt(), envs);
    }
}
