package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOAuthClientRequest(
    @NotBlank @Size(max = 100) String name,
    @NotEmpty List<String> redirectUris,
    List<String> scopes
) {}
