package com.powercms.cms.identity.dto;

import com.powercms.cms.identity.entity.RoleEntity;

import java.util.List;
import java.util.UUID;

public record RoleResponse(
    UUID id, String code, String name, String description, boolean builtIn,
    String scope, List<String> capabilities, List<String> layouts, Integer tierLevel
) {
    public static RoleResponse from(RoleEntity r, List<String> capabilities, List<String> layouts) {
        return new RoleResponse(r.getId(), r.getCode(), r.getName(), r.getDescription(),
            r.isBuiltIn(), r.getScope(), capabilities == null ? List.of() : List.copyOf(capabilities),
            layouts == null ? List.of() : List.copyOf(layouts), r.getTierLevel());
    }
}
