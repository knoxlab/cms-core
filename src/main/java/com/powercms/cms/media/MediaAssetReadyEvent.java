package com.powercms.cms.media;

import java.util.UUID;

/**
 * 媒体资产处理就绪事件（FR-CM-MEDIA-011）：Knox 对账推进资产至 ACTIVE 并写入
 * 处理元数据后发布，content 包监听更新对应 MEDIA 内容项的字段与索引。
 *
 * <p>event 为触发本次刷新的 Knox webhook 事件名（如 metadata.obtained / poster.obtained），
 * 由对账轮询/补查触发时为空；WEBHOOK 生命周期钩子据此按监听事件筛选执行。
 */
public record MediaAssetReadyEvent(UUID siteId, UUID assetId, String event) {
    public MediaAssetReadyEvent(UUID siteId, UUID assetId) {
        this(siteId, assetId, null);
    }
}
