package com.powercms.cms.model;

import java.util.UUID;

/** 已发布内容模型版本变化，交付层据此重建站点 GraphQL schema（FR-API-003）。 */
public record ContentTypePublishedEvent(UUID siteId, UUID contentTypeId, int publishedVersion) {}
