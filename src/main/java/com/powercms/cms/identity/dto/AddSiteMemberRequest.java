package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record AddSiteMemberRequest(@NotBlank String username, @NotBlank String roleCode) {}
