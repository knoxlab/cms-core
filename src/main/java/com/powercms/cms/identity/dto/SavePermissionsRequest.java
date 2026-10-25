package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * PUT /permissions 请求体（spec 9.6）：保存角色/用户权利，可选打破继承。
 * entries 允许为空：仅以 breakInheritance=true 触发「打破继承 + 快照复制」时无需携带条目。
 * 打破继承合并时，条目 permissions 为空表示将该主体从快照中移除（显式取消继承权利）。
 */
public record SavePermissionsRequest(
    boolean breakInheritance,
    @NotNull List<Entry> entries) {

    public record Entry(
        @NotBlank String principalType,
        @NotNull UUID principalId,
        @NotNull List<String> permissions) {}
}
