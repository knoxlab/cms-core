package com.powercms.cms.identity.dto;

import java.util.UUID;

/**
 * 禁用用户请求（spec 5.3 引用迁移）：用户仍为内容 owner 或工作流待办受理人时，
 * 必须指定 {@code reassignTo} 替代用户，禁用前迁移引用。
 */
public record DisableUserRequest(UUID reassignTo) {
}
