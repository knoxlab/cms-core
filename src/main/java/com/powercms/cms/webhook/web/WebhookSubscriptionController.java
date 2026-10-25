package com.powercms.cms.webhook.web;

import com.powercms.cms.webhook.WebhookRelayService;
import com.powercms.cms.webhook.WebhookSubscriptionService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Webhook 订阅管理 API（FR-API-007），站点级 site-admin 权限。
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/webhooks")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService service;
    private final WebhookRelayService relay;

    public WebhookSubscriptionController(WebhookSubscriptionService service, WebhookRelayService relay) {
        this.service = service;
        this.relay = relay;
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping
    List<WebhookSubscriptionService.SubscriptionInfo> list(@PathVariable UUID siteId) {
        return service.list(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PostMapping
    ResponseEntity<WebhookSubscriptionService.SubscriptionInfo> create(
        @PathVariable UUID siteId, @RequestBody WebhookSubscriptionService.SaveRequest request) {
        return ResponseEntity.status(201).body(service.create(siteId, request));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PutMapping("/{id}")
    WebhookSubscriptionService.SubscriptionInfo update(@PathVariable UUID siteId, @PathVariable UUID id,
                                                       @RequestBody WebhookSubscriptionService.SaveRequest request) {
        return service.update(siteId, id, request);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID siteId, @PathVariable UUID id) {
        service.delete(siteId, id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/{id}/deliveries")
    List<WebhookSubscriptionService.DeliveryInfo> deliveries(@PathVariable UUID siteId, @PathVariable UUID id,
                                                             @RequestParam(defaultValue = "20") int limit) {
        return service.recentDeliveries(siteId, id, limit);
    }

    /** 死信重放：将 DEAD 状态投递重置为待投递并立即触发一次。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PostMapping("/{id}/deliveries/{deliveryId}/retry")
    ResponseEntity<Void> retryDelivery(@PathVariable UUID siteId, @PathVariable UUID id,
                                       @PathVariable UUID deliveryId) {
        if (!relay.retryDeadLetter(deliveryId)) {
            throw new BusinessException(ErrorCodes.INVALID_TASK_STATE, "投递记录不存在或不可重试");
        }
        return ResponseEntity.accepted().build();
    }
}
