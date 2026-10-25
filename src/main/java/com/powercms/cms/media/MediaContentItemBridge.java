package com.powercms.cms.media;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * media 包 → content 包的窄桥（FR-CM-MEDIA-011）：content 包同步 MEDIA 内容项时
 * 读取资产详情并回填 content_item_id，避免 content 包直接依赖 media repository 全量接口，
 * 也不引入 media → content 循环依赖。
 */
public interface MediaContentItemBridge {

    /** 供内容项同步所需的资产详情（storageKey 取最新版本账本）。 */
    record AssetDetail(UUID id, UUID siteId, String title, String filename, String mimeType,
                       long fileSize, String contentHash, String storageKey, String status,
                       String kind, UUID mediaTypeId, String metadata, UUID contentItemId,
                       String transcript, String transcriptLang, String transcriptModel) {}

    Optional<AssetDetail> findById(UUID assetId);

    /** 已归类但尚无内容项的资产（ApplicationReady 存量补齐）。 */
    List<AssetDetail> findAllClassifiedWithoutContentItem();

    /** 内容项创建后回填关联。 */
    void linkContentItem(UUID assetId, UUID contentItemId);
}
