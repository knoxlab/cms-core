package com.powercms.cms.content;

import java.util.UUID;

/**
 * 内容创建/版本保存/状态变更/行内编辑事务提交后发布，
 * 供管理索引做增量同步（FR-SEARCH-001/006）。
 */
public record ContentChangedEvent(UUID siteId, UUID contentItemId) {}
