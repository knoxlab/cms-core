package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateApiTokenRequest(
    @NotBlank @Size(max = 100) String name,
    UUID siteId,
    List<String> scopes,
    @NotNull Instant expiresAt,
    @Size(max = 500) String allowedFrom
) {}
