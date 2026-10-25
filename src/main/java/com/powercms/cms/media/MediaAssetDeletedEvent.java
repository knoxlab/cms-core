package com.powercms.cms.media;

import java.util.UUID;

/**
 * 媒体资产删除事件（FR-CM-MEDIA-011）：资产物理删除前携带其关联内容项 ID 发布，
 * content 包监听联动删除对应 MEDIA 内容项。
 */
public record MediaAssetDeletedEvent(UUID siteId, UUID assetId, UUID contentItemId) {}
