package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateRoleCapabilitiesRequest(@NotNull List<String> capabilities) {}
