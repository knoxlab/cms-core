package com.powercms.cms.webhook;

import com.powercms.cms.webhook.entity.WebhookSubscriptionEntity;
import com.powercms.cms.webhook.repo.WebhookDeliveryRepository;
import com.powercms.cms.webhook.repo.WebhookSubscriptionRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class WebhookSubscriptionService {

    /** FR-API-007：内容批准、Release 创建、构建、部署、回滚事件（Outbox 事件类型）。 */
    public static final Set<String> ALLOWED_EVENTS = Set.of(
        "CONTENT_PUBLISHED",
        "review-task.approved",
        "review-task.rejected",
        "RELEASE_CREATED",
        "RELEASE_DEPLOYED",
        "BUILD_SUCCEEDED",
        "BUILD_FAILED");

    private static final int MAX_SUBSCRIPTIONS_PER_SITE = 50;

    /** 社区版 Webhook 订阅上限（矩阵 H4：≤3 个）。 */
    static final int COMMUNITY_MAX_WEBHOOKS = 3;

    private final WebhookSubscriptionRepository repo;
    private final WebhookDeliveryRepository deliveries;

    public WebhookSubscriptionService(WebhookSubscriptionRepository repo, WebhookDeliveryRepository deliveries) {
        this.repo = repo;
        this.deliveries = deliveries;
    }

    public record SubscriptionInfo(UUID id, UUID siteId, String name, String url,
                                   List<String> events, boolean active) {}

    public record SaveRequest(String name, String url, List<String> events, Boolean active, String secret) {}

    public record DeliveryInfo(UUID id, UUID eventId, String eventType, String status,
                               int attempts, Integer responseStatus, String lastError) {}

    public SubscriptionInfo create(UUID siteId, SaveRequest request) {
        if (repo.countByActiveTrue() >= COMMUNITY_MAX_WEBHOOKS) {
            throw new BusinessException(ErrorCodes.FORBIDDEN,
                "社区版最多启用 " + COMMUNITY_MAX_WEBHOOKS + " 个 Webhook 订阅");
        }
        validate(siteId, request);
        var entity = new WebhookSubscriptionEntity();
        entity.setSiteId(siteId);
        apply(entity, request, null);
        return toInfo(repo.save(entity));
    }

    public SubscriptionInfo update(UUID siteId, UUID id, SaveRequest request) {
        var entity = requireOwned(siteId, id);
        validate(siteId, request);
        apply(entity, request, request.secret());
        return toInfo(repo.save(entity));
    }

    public void delete(UUID siteId, UUID id) {
        requireOwned(siteId, id);
        repo.deleteById(id);
    }

    public List<SubscriptionInfo> list(UUID siteId) {
        return repo.findBySiteIdOrderByNameAsc(siteId).stream().map(this::toInfo).toList();
    }

    public List<DeliveryInfo> recentDeliveries(UUID siteId, UUID subscriptionId, int limit) {
        requireOwned(siteId, subscriptionId);
        return deliveries.findBySubscriptionIdOrderByCreatedAtDesc(
                subscriptionId, org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
            .stream()
            .map(this::toDeliveryInfo)
            .toList();
    }

    private void apply(WebhookSubscriptionEntity entity, SaveRequest request, String existingSecret) {
        entity.setName(request.name().trim());
        entity.setUrl(request.url().trim());
        entity.setEvents(joinEvents(request.events()));
        entity.setActive(request.active() == null || request.active());
        if (request.secret() != null && !request.secret().isBlank()) {
            entity.setSecret(request.secret().trim());
        } else if (entity.getSecret() == null && existingSecret == null) {
            entity.setSecret(randomSecret());
        }
    }

    private void validate(UUID siteId, SaveRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "订阅名称不能为空");
        }
        if (request.url() == null) throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "回调地址不能为空");
        URI uri = parseUri(request.url().trim());
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "回调地址必须是 http/https");
        }
        if (request.events() != null) {
            for (String event : request.events()) {
                if (!ALLOWED_EVENTS.contains(event)) {
                    throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不支持的事件类型: " + event);
                }
            }
            if (new LinkedHashSet<>(request.events()).size() > WebhookSubscriptionEntity.MAX_EVENTS) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "事件白名单过多");
            }
        }
        long count = repo.findBySiteIdOrderByNameAsc(siteId).size();
        if (count >= MAX_SUBSCRIPTIONS_PER_SITE) {
            throw new BusinessException(ErrorCodes.CONFLICT, "每站点订阅数已达上限");
        }
    }

    private static URI parseUri(String value) {
        try { return new URI(value); }
        catch (URISyntaxException ex) { throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "回调地址不合法"); }
    }

    private static String joinEvents(List<String> events) {
        if (events == null || events.isEmpty()) return "";
        return String.join(",", new LinkedHashSet<>(events));
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private WebhookSubscriptionEntity requireOwned(UUID siteId, UUID id) {
        var entity = repo.findById(id).orElseThrow(() ->
            new BusinessException(ErrorCodes.NOT_FOUND, "Webhook 订阅不存在"));
        if (!siteId.equals(entity.getSiteId())) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "Webhook 订阅不存在");
        }
        return entity;
    }

    private SubscriptionInfo toInfo(WebhookSubscriptionEntity e) {
        List<String> events = e.getEvents() == null || e.getEvents().isBlank()
            ? List.of()
            : Arrays.stream(e.getEvents().split(",")).map(String::trim).toList();
        return new SubscriptionInfo(e.getId(), e.getSiteId(), e.getName(), e.getUrl(), events, e.isActive());
    }

    private DeliveryInfo toDeliveryInfo(com.powercms.cms.webhook.entity.WebhookDeliveryEntity d) {
        return new DeliveryInfo(d.getId(), d.getEventId(), d.getEventType(), d.getStatus(),
            d.getAttempts(), d.getResponseStatus(), d.getLastError());
    }
}
