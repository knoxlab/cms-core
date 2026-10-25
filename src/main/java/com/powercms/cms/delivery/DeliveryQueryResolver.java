package com.powercms.cms.delivery;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 动态 schema 的数据解析入口：复用 DeliveryService 的已部署 Release 读取路径（FR-API-002）。
 * 返回 DeliveryView（含 media 摘要，FR-KNX-040）。
 */
@Component
public class DeliveryQueryResolver {

    private final DeliveryService deliveryService;

    public DeliveryQueryResolver(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    public List<DeliveryService.DeliveryView> contents(UUID siteId, UUID contentTypeId, UUID categoryId,
                                                       Integer page, Integer size) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safeSize = Math.min(Math.max(size == null ? 20 : size, 1), 100);
        return deliveryService.listPublishedViews(siteId, categoryId, safePage, safeSize).stream()
            .filter(item -> contentTypeId == null || contentTypeId.equals(item.contentTypeId()))
            .toList();
    }

    public DeliveryService.DeliveryView content(UUID siteId, UUID id) {
        try {
            return deliveryService.getViewById(siteId, id);
        } catch (RuntimeException notFound) {
            return null;
        }
    }
}
