package com.powercms.cms.site;

import java.util.UUID;

/**
 * 站点激活事件：SiteService.create 成功后发布。
 * 媒体类型种子（MediaTypeSeedService）等站点级初始化能力监听此事件（FR-CM-MEDIA-002）。
 */
public record SiteCreatedEvent(UUID siteId, UUID actorUserId) {}
