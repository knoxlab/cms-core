package com.powercms.cms.delivery.web;

import com.powercms.cms.identity.permission.AccessCacheScope;
import com.powercms.cms.delivery.DeliveryCachePolicy;
import com.powercms.cms.delivery.DeliveryService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 公开交付 REST API（FR-API-002~005）：无需认证，返回已部署 Release 中的已发布内容。
 * 响应体为 DeliveryView：DeliveryItem 字段 + media 摘要（FR-KNX-040）。
 */
@RestController
@RequestMapping("/api/v1/delivery")
class DeliveryController {

    private final DeliveryService service;

    DeliveryController(DeliveryService service) { this.service = service; }

    @GetMapping("/sites/{siteId}/contents")
    ResponseEntity<List<DeliveryService.DeliveryView>> list(
        @PathVariable UUID siteId,
        @RequestParam(required = false) UUID categoryId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Principal principal) {

        var items = service.listPublishedViews(siteId, categoryId, page, size);
        var lastModified = items.stream().map(DeliveryService.DeliveryView::updatedAt)
            .max(Instant::compareTo);
        var etag = lastModified.map(i -> "W/\"" + i.toEpochMilli() + "\"").orElse("W/\"0\"");

        // DEF-DELIVERY-02：空集（无已部署 Release / 该分类下无内容）**不得**设置 Last-Modified：
        // HttpHeaders.setInstant(null) 抛 NPE → 交付列表在「本就该返回空」的正确状态下 500。
        // 空集仍然 200 + 空数组（FR-API-002：未发布不得返回，而不是报错），ETag 用 W/"0" 保持可缓存。
        var builder = ResponseEntity.ok()
            .eTag(etag)
            .header("Cache-Control", cacheControl(principal))
            .header("Vary", "Cookie");
        lastModified.ifPresent(builder::lastModified);
        return builder.body(items);
    }

    @GetMapping("/sites/{siteId}/contents/{contentId}")
    ResponseEntity<DeliveryService.DeliveryView> get(
        @PathVariable UUID siteId, @PathVariable UUID contentId, Principal principal) {

        var item = service.getViewById(siteId, contentId);
        var etag = "W/\"" + item.updatedAt().toEpochMilli() + "\"";
        return ResponseEntity.ok()
            .eTag(etag)
            .header("Cache-Control", cacheControl(principal))
            .header("Vary", "Cookie")
            .lastModified(item.updatedAt())
            .body(item);
    }

    private static String cacheControl(Principal principal) {
        boolean identified = principal instanceof Authentication auth
            && auth.getPrincipal() instanceof AuthenticatedUserPrincipal;
        if (!identified) {
            var current = SecurityContextHolder.getContext().getAuthentication();
            identified = current != null && current.getPrincipal() instanceof AuthenticatedUserPrincipal;
        }
        return identified
            ? DeliveryCachePolicy.memberPrivate()
            : AccessCacheScope.shared()
                ? DeliveryCachePolicy.anonymousPublic()
                : DeliveryCachePolicy.unshared();
    }
}
