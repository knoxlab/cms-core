package com.powercms.cms.media;

import java.util.UUID;

/**
 * 媒体资产登记完成事件（FR-CM-MEDIA-011）：上传建账 + 归类落库后发布，
 * 由 content 包监听同步创建 MEDIA 内容项。事件携带 assetId，监听端经
 * {@link MediaContentItemBridge} 读取资产最新状态，保证幂等。
 */
public record MediaAssetRegisteredEvent(UUID siteId, UUID assetId) {}
