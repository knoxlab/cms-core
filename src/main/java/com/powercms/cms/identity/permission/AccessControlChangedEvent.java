package com.powercms.cms.identity.permission;

import java.util.UUID;

/** ACL 或条件策略已提交。失效监听在事务提交后处理。 */
public record AccessControlChangedEvent(UUID siteId, String objectType, UUID objectId) {}
