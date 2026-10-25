package com.powercms.cms.identity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiTokenCreatedResponse(
    UUID id,
    String publicId,
    String name,
    UUID siteId,
    List<String> scopes,
    Instant expiresAt,
    String allowedFrom,
    Instant createdAt,
    String token
) {}
