package com.powercms.cms.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.outbox.repo.OutboxEventRepository;
import com.powercms.cms.outbox.entity.OutboxEventEntity;
import com.powercms.cms.webhook.entity.WebhookDeliveryEntity;
import com.powercms.cms.webhook.entity.WebhookSubscriptionEntity;
import com.powercms.cms.webhook.repo.WebhookDeliveryRepository;
import com.powercms.cms.webhook.repo.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox 中继消费者（规格书 9.2）+ Webhook 签名投递（FR-API-007）。
 * 扫描 published_at IS NULL 的 Outbox 事件，为每个匹配订阅落一条投递记录并标记事件已发布；
 * 再由独立调度按指数退避重试投递，超过 maxAttempts 进入死信（status=DEAD）。
 */
@Service
public class WebhookRelayService {

    private static final Logger log = LoggerFactory.getLogger(WebhookRelayService.class);
    public static final String HEADER_EVENT_ID = "X-PowerCMS-Event-Id";
    public static final String HEADER_EVENT_TYPE = "X-PowerCMS-Event-Type";
    public static final String HEADER_SIGNATURE = "X-PowerCMS-Signature";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final int MAX_BATCH = 200;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final OutboxEventRepository outboxRepo;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final ObjectMapper mapper;
    private final HttpClient client;

    WebhookRelayService(OutboxEventRepository outboxRepo,
                        WebhookSubscriptionRepository subscriptions,
                        WebhookDeliveryRepository deliveries,
                        ObjectMapper mapper,
                        @Value("${powercms.webhook.connect-timeout:3s}") Duration connectTimeout) {
        this.outboxRepo = outboxRepo;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /**
     * 事务内：扫描未发布的 Outbox 事件 → 匹配活跃订阅 → 落投递记录 → 标记已发布。
     * 至少一次：同一 (subscription, event) 唯一约束保证重复扫描不会重复投递。
     */
    @Transactional
    public void relayOnce() {
        List<OutboxEventEntity> pending = outboxRepo.findByPublishedAtIsNullOrderByOccurredAt();
        if (pending.isEmpty()) return;
        int created = 0;
        for (OutboxEventEntity event : pending) {
            UUID eventId = parseEventId(event);
            for (WebhookSubscriptionEntity sub : subscriptions.findBySiteIdAndActiveTrue(siteOf(event))) {
                if (!sub.accepts(event.getEventType())) continue;
                if (deliveries.existsBySubscriptionIdAndEventId(sub.getId(), eventId)) continue;
                deliveries.save(WebhookDeliveryEntity.create(
                    sub.getId(), eventId, event.getEventType(), event.getPayload(),
                    defaultMaxAttempts(sub), Instant.now()));
                created++;
            }
            event.setPublishedAt(Instant.now());
        }
        if (created > 0) {
            log.debug("webhook relay created {} delivery tasks from {} outbox events", created, pending.size());
        }
    }

    /** 定时中继入口；独立于投递调度，避免长事务持锁。 */
    @Scheduled(fixedDelayString = "${powercms.webhook.relay-ms:5000}")
    public void relay() {
        try {
            relayOnce();
        } catch (Exception ex) {
            log.warn("webhook relay scan failed: {}", ex.getMessage());
        }
    }

    /** 投递调度：到期任务逐条签名 POST；2xx 成功，否则退避重试直至死信。 */
    @Scheduled(fixedDelayString = "${powercms.webhook.dispatch-ms:2000}")
    public void dispatchDue() {
        List<WebhookDeliveryEntity> due = deliveries.findDueBatch(Instant.now(), PageRequest.of(0, MAX_BATCH));
        for (WebhookDeliveryEntity task : due) {
            attempt(task.getId(), false);
        }
    }

    /** 单次投递尝试。scheduleRetry=true 时失败后立即安排下一次退避（供测试驱动）。 */
    @Transactional
    public void attempt(UUID deliveryId, boolean scheduleRetry) {
        var delivery = scheduleRetry
            ? deliveries.findByIdForUpdate(deliveryId).orElse(null)
            : deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || !WebhookDeliveryEntity.STATUS_PENDING.equals(delivery.getStatus())) return;
        var subscription = subscriptions.findById(delivery.getSubscriptionId()).orElse(null);
        if (subscription == null || !subscription.isActive()) {
            delivery.recordFailure(delivery.getMaxAttempts(), "subscription inactive or missing", null);
            deliveries.save(delivery);
            return;
        }
        String body = envelope(delivery, subscription);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(subscription.getUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HEADER_EVENT_ID, delivery.getEventId().toString())
                .header(HEADER_EVENT_TYPE, delivery.getEventType())
                .header(HEADER_SIGNATURE, SIGNATURE_PREFIX + hmacSha256(subscription.getSecret(), body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.recordSuccess(response.statusCode());
            } else {
                delivery.recordFailure(delivery.getMaxAttempts(),
                    "HTTP " + response.statusCode(), response.statusCode());
            }
            deliveries.save(delivery);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            delivery.recordFailure(delivery.getMaxAttempts(), "interrupted", null);
            deliveries.save(delivery);
        } catch (Exception ex) {
            delivery.recordFailure(delivery.getMaxAttempts(), ex.getClass().getSimpleName() + ": " + ex.getMessage(), null);
            deliveries.save(delivery);
        }
    }

    /** FR-API-007 验收：消费端可按事件 ID 去重 —— 信封顶层携带 eventId。 */
    private String envelope(WebhookDeliveryEntity delivery, WebhookSubscriptionEntity subscription) {
        try {
            JsonNode payloadNode = mapper.readTree(delivery.getPayload());
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", delivery.getEventId().toString());
            envelope.put("eventType", delivery.getEventType());
            envelope.put("subscriptionId", subscription.getId().toString());
            envelope.put("occurredAt", delivery.getCreatedAt().toString());
            envelope.put("data", payloadNode);
            return mapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            log.warn("webhook payload is not JSON, sending raw: {}", ex.getMessage());
            return delivery.getPayload();
        }
    }

    static String hmacSha256(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    private UUID parseEventId(OutboxEventEntity event) {
        try {
            JsonNode node = mapper.readTree(event.getPayload());
            if (node.hasNonNull("eventId")) return UUID.fromString(node.get("eventId").asText());
        } catch (Exception ignored) {
        }
        return event.getId();
    }

    private UUID siteOf(OutboxEventEntity event) {
        try {
            JsonNode node = mapper.readTree(event.getPayload());
            if (node.hasNonNull("siteId")) return UUID.fromString(node.get("siteId").asText());
        } catch (Exception ignored) {
        }
        return null;
    }

    private int defaultMaxAttempts(WebhookSubscriptionEntity sub) {
        // V38 未引入每订阅重试配置时统一默认值；后续可在订阅上扩展 max_attempts 列。
        return 5;
    }

    /** 测试与运维手动触发一次到期投递。 */
    @Transactional
    public boolean deliverPending(UUID deliveryId) {
        var delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || !WebhookDeliveryEntity.STATUS_PENDING.equals(delivery.getStatus())) return false;
        if (delivery.getNextAttemptAt() != null && delivery.getNextAttemptAt().isAfter(Instant.now())) {
            delivery.markDueNow();
            deliveries.save(delivery);
        }
        attempt(deliveryId, false);
        return true;
    }

    public long deadLetterCount() {
        return deliveries.countByStatus(WebhookDeliveryEntity.STATUS_DEAD);
    }

    /** 死信重放：仅 DEAD 状态可重置为待投递并立即尝试。 */
    @Transactional
    public boolean retryDeadLetter(UUID deliveryId) {
        var delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || !WebhookDeliveryEntity.STATUS_DEAD.equals(delivery.getStatus())) return false;
        delivery.retryAsPending();
        deliveries.save(delivery);
        attempt(deliveryId, false);
        return true;
    }
}
