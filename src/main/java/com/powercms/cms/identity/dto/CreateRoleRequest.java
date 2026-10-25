package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateRoleRequest(
    @NotBlank @Size(max = 100) String code,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 500) String description,
    List<String> capabilities,
    List<String> layouts,
    Integer tierLevel
) {}
